package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.ApprovalRepository;
import com.pddcopilot.core.repository.BusinessStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Validate draft versions and record the reviewer's immutable decision. */
@Service
public class ReplyApprovalService {
  private final BusinessStore store;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final ApprovalRepository repository;
  private final SendPolicy policy;

  public ReplyApprovalService(
      BusinessStore store,
      ApprovalRepository repository,
      SendPolicy policy,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.repository = repository;
    this.store = store;
    this.batches = batches;
    this.policy = policy;
  }

  @Transactional
  public Map<String, Object> decide(String draftId, Contracts.Decision input, String actor) {
    var preliminary = repository.findDraftConversation(draftId);
    var conversation = store.scopedConversation((String) preliminary.get("conversation_id"), true);
    var draft = repository.lockDraft(draftId);
    var old = repository.findDecision(draftId, input.revision());
    String text = input.finalContent() == null ? "" : input.finalContent().strip();
    if (old.isEmpty() && asLong(draft.get("revision")) != input.revision())
      throw conflict("DRAFT_VERSION_CONFLICT");
    var cards =
        com.pddcopilot.core.support.ProductCards.select(
            com.pddcopilot.core.support.ProductCards.validate(
                store.fromJson(draft.get("product_cards").toString())),
            input.productIds());
    if (!"approve".equals(input.decision()) && !cards.isEmpty())
      throw conflict("INVALID_PRODUCT_CARDS");
    if (!old.isEmpty()) {
      var decision = old.get(0);
      if (!decision.get("decision").equals(input.decision())
          || !decision.get("final_content").equals(text)
          || !decision.get("reviewer").equals(actor)
          || !store.fromJson(decision.get("product_cards").toString()).equals(cards))
        throw conflict("DECISION_CONFLICT");
      return Map.of("approval_id", decision.get("id"), "state", draft.get("state"));
    }
    if (asLong(draft.get("revision")) != input.revision()
        || !"waiting_human".equals(draft.get("state"))
        || asLong(draft.get("conversation_revision")) != asLong(conversation.get("revision"))
        || !"open".equals(conversation.get("state"))) throw conflict("DRAFT_VERSION_CONFLICT");
    if ("transfer".equals(input.decision())
        && !repository.findPendingSends(conversation.get("id")).isEmpty())
      throw conflict("SEND_IN_PROGRESS");
    if ("approve".equals(input.decision())) {
      com.pddcopilot.core.support.ReplySafety.validate(text, true);
      if (!"draft".equals(draft.get("outcome"))) throw conflict("HUMAN_HANDLING_REQUIRED");
      if (text.isBlank())
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_REPLY");
      policy.requireOnline(conversation.get("id").toString());
    }
    String approvalId = newId();
    repository.insertDecision(
        approvalId, draftId, input.revision(), input.decision(), text, actor, nowMillis());
    repository.saveCards(approvalId, store.toJson(cards));
    String state =
        switch (input.decision()) {
          case "approve" -> "approved";
          case "reject" -> "rejected";
          default -> "transferred";
        };
    repository.updateDraftState(state, nowMillis(), draftId);
    repository.updateMessageState(state, draft.get("message_id"));
    if ("approve".equals(input.decision())) {
      String command = newId();
      repository.insertSendCommand(command, approvalId, draftId, input.revision(), nowMillis());
      batches.bindDraft(command, draft.get("run_id"));
      store.enqueueJob("send", command);
    } else if ("transfer".equals(input.decision())) {
      repository.transferConversation(conversation.get("id"));
      batches.supersedeQueued(conversation.get("id"));
    }
    store.recordAudit("draft." + input.decision(), draftId, actor, approvalId);
    return Map.of("approval_id", approvalId, "state", state);
  }
}
