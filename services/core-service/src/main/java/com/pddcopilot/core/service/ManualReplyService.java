package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** An explicit human submission is its own immutable approval, independent of Agent drafts. */
@Service
public class ManualReplyService {
  private final BusinessStore store;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final ManualReplyRepository replies;
  private final ConversationRepository conversations;
  private final SendPolicy policy;

  public ManualReplyService(
      BusinessStore store,
      ManualReplyRepository replies,
      ConversationRepository conversations,
      SendPolicy policy,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.store = store;
    this.batches = batches;
    this.replies = replies;
    this.conversations = conversations;
    this.policy = policy;
  }

  @Transactional
  public Map<String, Object> submit(
      String conversationId, Contracts.ManualReply input, String actor) {
    var conversation = store.scopedConversation(conversationId, true);
    String content = input.content().strip();
    Set<String> covered =
        new HashSet<>(input.answeredMessageIds() == null ? List.of() : input.answeredMessageIds());
    if (content.isBlank()) throw conflict("EMPTY_REPLY");
    var prior = replies.find(conversationId, input.requestId());
    if (!prior.isEmpty()) {
      var old = prior.get(0);
      if (!covered.equals(batches.commandCoverage(old.get("command_id")))
          || !content.equals(old.get("content"))
          || !actor.equals(old.get("reviewer"))
          || input.revision() != asLong(old.get("request_revision")))
        throw conflict("IDEMPOTENCY_PAYLOAD_CONFLICT");
      return result(old.get("id"), old.get("command_id"), old.get("state"));
    }
    if (!"transferred".equals(conversation.get("state")))
      throw conflict("MANUAL_TAKEOVER_REQUIRED");
    if (input.revision() != asLong(conversation.get("revision")))
      throw conflict("CONVERSATION_VERSION_CONFLICT");
    if (!conversations.findPendingSends(conversationId).isEmpty())
      throw conflict("SEND_IN_PROGRESS");
    if (conversations.hasUnknownSend(conversationId)) throw conflict("SEND_RESULT_UNRESOLVED");
    policy.requireOnline(conversationId);
    com.pddcopilot.core.support.ReplySafety.validate(content, false);
    String id = newId(), command = newId();
    replies.insert(
        id, conversationId, input.requestId(), input.revision(), content, actor, nowMillis());
    replies.enqueue(command, id, nowMillis());
    batches.bindManual(command, conversationId, covered);
    store.enqueueJob("send", command);
    store.recordAudit("manual_reply.submitted", id, actor, command);
    return result(id, command, "approved");
  }

  private Map<String, Object> result(Object reply, Object command, Object state) {
    return Map.of("manual_reply_id", reply, "send_command_id", command, "state", state);
  }
}
