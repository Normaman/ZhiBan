package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConversationRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read the workbench and coordinate human takeover. */
@Service
public class ConversationService {
  private final BusinessStore store;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final ConversationRepository repository;
  private final ReadReceiptService receipts;
  private final com.pddcopilot.core.repository.ManualReplyRepository manual;
  private final SendPolicy sendPolicy;

  public ConversationService(
      BusinessStore store,
      ConversationRepository repository,
      ReadReceiptService receipts,
      com.pddcopilot.core.repository.ManualReplyRepository manual,
      SendPolicy sendPolicy,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.repository = repository;
    this.store = store;
    this.batches = batches;
    this.receipts = receipts;
    this.manual = manual;
    this.sendPolicy = sendPolicy;
  }

  public List<Map<String, Object>> list(String reader) {
    return repository.listConversations(store.shopId(), reader);
  }

  public Map<String, Object> detail(String conversationId, String reader) {
    var conversation = store.scopedConversation(conversationId, false);
    var viewed =
        repository.readMessageIds(conversationId, reader).stream()
            .map(row -> row.get("message_id"))
            .collect(java.util.stream.Collectors.toSet());
    var marker = receipts.marker((String) conversation.get("buyer_ref"));
    var messages = repository.listMessages(conversationId);
    messages.forEach(
        message -> {
          Object raw = message.remove("media_json");
          message.put("media", raw instanceof String json ? store.fromJson(json) : null);
          Object platformId = message.remove("platform_message_id");
          message.put("workbench_read", viewed.contains(message.get("id")));
          if ("outbound".equals(message.get("direction"))) {
            message.put("buyer_read_state", ReadReceiptService.state((String) platformId, marker));
            message.put(
                "buyer_read_reason",
                platformId == null
                    ? "message_id_missing"
                    : marker == null
                        ? "receipt_missing"
                        : "unknown".equals(message.get("buyer_read_state"))
                            ? "outside_supported_range"
                            : "platform_receipt");
            message.put(
                "buyer_read_observed_at", marker == null ? null : marker.get("observed_at"));
          }
        });
    conversation.put("messages", messages);
    conversation.put("pending_questions", batches.pending(conversationId));
    var drafts = repository.listDrafts(conversationId);
    drafts.forEach(
        draft -> {
          draft.put("covered_questions", batches.draftCoverage(draft.get("run_id")));
          draft.put(
              "question_results", batches.result(draft.get("run_id")).get("question_results"));
          draft.put("citations", store.fromJson((String) draft.get("citations")));
          draft.put("risk_codes", store.fromJson((String) draft.get("risk_codes")));
          draft.put("product_cards", store.fromJson(draft.get("product_cards").toString()));
        });
    conversation.put("drafts", drafts);
    var commands = repository.listSendCommands(conversationId);
    commands.forEach(
        c -> {
          c.put("product_cards", store.fromJson(c.get("product_cards").toString()));
          if (c.get("receipt") instanceof String json) c.put("receipt", store.fromJson(json));
        });
    conversation.put("send_commands", commands);
    conversation.put("manual_replies", manual.list(conversationId));
    conversation.put("send_enabled", sendPolicy.enabledFor(conversationId));
    var runs = repository.listAgentRuns(conversationId);
    runs.forEach(
        run -> {
          Object raw = run.remove("result");
          if (raw instanceof String json && store.fromJson(json) instanceof Map<?, ?> result) {
            for (String field :
                List.of(
                    "engine",
                    "model",
                    "tool_trace",
                    "model_calls",
                    "history_truncated",
                    "missing_requirements",
                    "verification",
                    "understanding",
                    "order_context",
                    "model_config_version")) {
              if (result.get(field) != null) run.put(field, result.get(field));
            }
          }
        });
    conversation.put("agent_runs", runs);
    return conversation;
  }

  @Transactional
  public Map<String, Object> markRead(String conversationId, List<String> ids, String reader) {
    store.scopedConversation(conversationId, true);
    var unique = new LinkedHashSet<>(ids);
    unique.forEach(id -> repository.readableMessage(id, conversationId));
    unique.forEach(id -> repository.markRead(id, reader, nowMillis()));
    return Map.of(
        "unread_count",
        repository.unreadCount(conversationId, reader),
        "platform_read_synced",
        false);
  }

  @Transactional
  public Map<String, Object> resume(String conversationId, long revision, String actor) {
    var conversation = store.scopedConversation(conversationId, true);
    long current = asLong(conversation.get("revision"));
    if ("open".equals(conversation.get("state")) && current == revision + 1)
      return Map.of("state", "open", "revision", current);
    if (current != revision) throw conflict("CONVERSATION_VERSION_CONFLICT");
    if ("open".equals(conversation.get("state")))
      return Map.of("state", "open", "revision", current);
    if (!repository.findPendingSends(conversationId).isEmpty()) throw conflict("SEND_IN_PROGRESS");
    if (repository.hasUnknownSend(conversationId)) throw conflict("SEND_RESULT_UNRESOLVED");
    repository.resumeConversation(conversationId);
    store.recordAudit("conversation.resumed", conversationId, actor, conversationId);
    return Map.of("state", "open", "revision", current + 1);
  }

  @Transactional
  public Map<String, Object> transfer(String conversationId, String actor) {
    var conversation = store.scopedConversation(conversationId, true);
    if ("transferred".equals(conversation.get("state"))) return Map.of("state", "transferred");
    if (!repository.findPendingSends(conversationId).isEmpty()) throw conflict("SEND_IN_PROGRESS");
    repository.transferConversation(conversationId);
    repository.transferDrafts(conversationId);
    batches.supersedeQueued(conversationId);
    store.recordAudit("conversation.transferred", conversationId, actor, conversationId);
    return Map.of("state", "transferred");
  }

  public List<Map<String, Object>> auditEvents() {
    return repository.listAuditEvents(store.shopId());
  }
}
