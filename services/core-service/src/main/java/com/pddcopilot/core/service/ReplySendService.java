package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.SendRepository;
import com.pddcopilot.core.support.WorkflowValues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Own approved commands, explicit retries and platform result persistence. */
@Service
public class ReplySendService {
  private final BusinessStore store;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final SendRepository repository;
  private final SendPolicy policy;
  private final ReplyPartRecorder parts;

  public ReplySendService(
      BusinessStore store,
      SendRepository repository,
      SendPolicy policy,
      ReplyPartRecorder parts,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.repository = repository;
    this.store = store;
    this.batches = batches;
    this.policy = policy;
    this.parts = parts;
  }

  public Map<String, Object> command(String commandId) {
    var command = repository.findApprovedCommand(commandId);
    store.requireShopAccess((String) command.get("shop_id"));
    return command;
  }

  @Transactional
  public Map<String, Object> retry(String commandId, String actor) {
    var command = command(commandId);
    var conversation = store.scopedConversation((String) command.get("conversation_id"), true);
    boolean manual = "manual".equals(command.get("origin"));
    if (!(manual ? "transferred" : "open").equals(conversation.get("state")))
      throw conflict("CONVERSATION_VERSION_CONFLICT");
    if ((!manual && !"send_failed".equals(command.get("draft_state")))
        || asLong(command.get("approved_conversation_revision"))
            != asLong(conversation.get("revision"))) throw conflict("SEND_NOT_RETRYABLE");
    policy.requireOnline(conversation.get("id").toString());
    command = repository.lockCommand(commandId);
    if (!"send_failed".equals(command.get("state"))) throw conflict("SEND_NOT_RETRYABLE");
    repository.prepareRetry(nowMillis(), commandId);
    repository.requeueSendJob(nowMillis(), commandId);
    repository.markDraftApproved(command.get("draft_id"));
    store.recordAudit("send.retry", commandId, actor, commandId);
    return Map.of("state", "approved");
  }

  @Transactional
  public void complete(String commandId, Map<String, Object> result) {
    repository.lockShop();
    String state = Objects.toString(result.get("state"), "send_unknown");
    if (!Set.of("sent", "send_failed", "send_unknown").contains(state)) state = "send_unknown";
    var command = command(commandId);
    boolean hasCards =
        !com.pddcopilot.core.support.ProductCards.validate(
                store.fromJson(command.get("product_cards").toString()))
            .isEmpty();
    if (hasCards && result.containsKey("parts")) parts.record(command, result);
    else if (hasCards && "sent".equals(state))
      throw new IllegalStateException("SEND_PART_RECEIPT_MISMATCH");
    repository.recordCommandResult(
        state,
        store.toJson(result),
        result.get("error_code"),
        WorkflowValues.nowMillis(),
        commandId);
    repository.recordDraftResult(state, WorkflowValues.nowMillis(), command.get("draft_id"));
    repository.recordMessageResult(state, command.get("draft_id"));
    if ("sent".equals(state)) {
      batches.delivered(commandId, nowMillis());
      if (hasCards) {
        store.recordAudit("send." + state, commandId, "connector", commandId);
        return;
      }
      String event = "reply-" + commandId;
      String platformId =
          result.get("platform_message_id") instanceof String value && value.matches("[0-9]{1,200}")
              ? value
              : null;
      var local = repository.findOutboundMessage(store.shopId(), event);
      var echoed =
          platformId == null
              ? List.<Map<String, Object>>of()
              : repository.platformMessage(store.shopId(), platformId);
      if (!echoed.isEmpty()) {
        var row = echoed.get(0);
        if (!"outbound".equals(row.get("direction"))
            || !command.get("conversation_id").equals(row.get("conversation_id"))
            || !command.get("final_content").equals(row.get("content"))
            || (row.get("event_id").toString().startsWith("reply-")
                && !event.equals(row.get("event_id")))
            || (!local.isEmpty() && !local.get(0).get("id").equals(row.get("id"))))
          throw new IllegalStateException("SEND_RECEIPT_ID_CONFLICT");
        repository.bindOutbound(row.get("id"), event, platformId);
      } else if (!local.isEmpty()) {
        Object previous = local.get(0).get("platform_message_id");
        if (platformId != null && previous != null && !platformId.equals(previous))
          throw new IllegalStateException("SEND_RECEIPT_ID_CONFLICT");
        repository.bindOutbound(local.get(0).get("id"), event, platformId);
      } else
        repository.insertOutboundMessage(
            WorkflowValues.newId(),
            store.shopId(),
            command.get("conversation_id"),
            platformId,
            event,
            command.get("final_content"),
            java.time.OffsetDateTime.now().toString(),
            WorkflowValues.sha256((String) command.get("final_content")),
            WorkflowValues.nowMillis());
    }
    store.recordAudit("send." + state, commandId, "connector", commandId);
  }
}
