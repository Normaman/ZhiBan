package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.AgentRunRepository;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.support.WorkflowValues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Create generation runs, pin knowledge snapshots and discard stale results. */
@Service
public class AgentRunService {
  private final BusinessStore store;
  private final AgentRunRepository repository;
  private final ProductContextResolver productContexts;
  private final OrderContextResolver orderContexts;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final long debounceMs;

  public AgentRunService(
      BusinessStore store,
      AgentRunRepository repository,
      ProductContextResolver productContexts,
      OrderContextResolver orderContexts,
      com.pddcopilot.core.repository.ReplyBatchRepository batches,
      @org.springframework.beans.factory.annotation.Value("${copilot.reply-debounce-ms:2000}")
          long debounceMs) {
    this.repository = repository;
    this.productContexts = productContexts;
    this.orderContexts = orderContexts;
    this.batches = batches;
    this.debounceMs = Math.max(0, Math.min(8000, debounceMs));
    this.store = store;
  }

  @Transactional
  public String start(String messageId, Map<String, Object> conversation, Object version) {
    long now = nowMillis();
    long first = batches.firstQueuedAt(conversation.get("id"), now);
    batches.supersedeQueued(conversation.get("id"));
    String run = newId();
    repository.insertRun(run, messageId, conversation.get("revision"), version, nowMillis());
    repository.markMessageRunning(messageId);
    store.enqueueJob("generate", run);
    batches.snapshot(run, conversation.get("id"));
    batches.defer(run, Math.min(now + debounceMs, first + 8000), first);
    return run;
  }

  @Transactional
  public Map<String, Object> regenerate(String messageId, String actor) {
    return regenerate(messageId, actor, false);
  }

  @Transactional
  public Map<String, Object> regenerate(String messageId, String actor, boolean single) {
    var message = repository.findMessage(messageId);
    var conversation = store.scopedConversation((String) message.get("conversation_id"), true);
    if (!"inbound".equals(message.get("direction"))
        || "system_hint".equals(message.get("message_type"))
        || message.get("platform_message_id") == null
        || !"open".equals(conversation.get("state"))) throw conflict("MESSAGE_NOT_GENERATABLE");
    if (!single && !messageId.equals(repository.latestInbound(conversation.get("id"))))
      throw conflict("MESSAGE_SUPERSEDED");
    if (single
        && batches.pending(conversation.get("id")).stream()
            .noneMatch(q -> messageId.equals(q.get("message_id"))))
      throw conflict("QUESTION_NOT_PENDING");
    var blocked = repository.findDecidedDrafts(messageId);
    if (!blocked.isEmpty()) throw conflict("DRAFT_ALREADY_DECIDED");
    if (!repository.findActiveRuns(messageId, conversation.get("revision")).isEmpty())
      throw conflict("RUN_IN_PROGRESS");
    repository.invalidateDraft(messageId);
    String run =
        start(
            messageId, conversation, repository.findShop(store.shopId()).get("knowledge_version"));
    if (single) batches.single(run, conversation.get("id"));
    store.recordAudit("draft.regenerate", messageId, actor, messageId);
    return Map.of("state", "agent_running");
  }

  public Map<String, Object> context(String runId) {
    var run = repository.findContextRun(runId);
    var message = repository.findContextMessage(run.get("message_id"));
    var conversation = store.scopedConversation((String) message.get("conversation_id"), false);
    var snapshot = repository.findKnowledgeSnapshot(run.get("knowledge_version"), store.shopId());
    var out = new LinkedHashMap<String, Object>();
    out.putAll(run);
    out.put("shop_id", store.shopId());
    out.put("conversation_id", conversation.get("id"));
    out.put("buyer_ref", conversation.get("buyer_ref"));
    out.put("message", message);
    var questions = batches.coverage(runId);
    out.put("pending_questions", questions);
    Object cutoff = run.get("context_at") == null ? run.get("created_at") : run.get("context_at");
    var questionContexts = new LinkedHashMap<String, Object>();
    var questionFacts = new LinkedHashMap<String, Object>();
    var questionOrders = new LinkedHashMap<String, Object>();
    var factCache = new HashMap<String, Map<String, Object>>();
    for (var question : questions) {
      var original = repository.findMessage(question.get("message_id"));
      var product = productContexts.resolve(original, conversation.get("id"), cutoff);
      String questionId = question.get("message_id").toString();
      questionContexts.put(questionId, product);
      questionFacts.put(questionId, facts(product, factCache));
      questionOrders.put(
          questionId, orderContexts.resolve(conversation.get("id"), original.get("created_at")));
    }
    out.put("question_contexts", questionContexts);
    out.put("question_product_facts", questionFacts);
    out.put("question_orders", questionOrders);
    out.put(
        "order_reference",
        orderContexts.resolve(conversation.get("id"), message.get("created_at")));
    var productContext = productContexts.resolve(message, conversation.get("id"), cutoff);
    out.put("product_context", productContext);
    out.put("platform_product_facts", facts(productContext, factCache));
    out.put(
        "knowledge",
        snapshot.isEmpty() ? List.of() : com.pddcopilot.core.support.KnowledgeEntries.active(
            store.fromJson((String) snapshot.get(0).get("snapshot")),asLong(run.get("created_at"))));
    out.put("knowledge_index",snapshot.isEmpty()?List.of():store.fromJson(snapshot.get(0).get("snapshot").toString()));
    out.put("mode", repository.findShopMode(store.shopId()).get("mode"));
    out.put(
        "history",
        repository.listRecentMessages(
            conversation.get("id"),
            run.get("context_at") == null ? run.get("created_at") : run.get("context_at")));
    return out;
  }

  private Map<String, Object> facts(
      Map<String, Object> product, Map<String, Map<String, Object>> cache) {
    if (!(product.get("goods_id") instanceof String goods)) return Map.of();
    return cache.computeIfAbsent(
        goods,
        key -> {
          var rows = repository.productDetail(key);
          if (rows.isEmpty()
              || !(store.fromJson(rows.get(0).get("payload").toString())
                  instanceof Map<?, ?> metadata)) return Map.of();
          var facts = new LinkedHashMap<String, Object>();
          for (String field :
              List.of(
                  "goods_id",
                  "goods_name",
                  "description",
                  "category_path",
                  "attributes",
                  "fetched_at")) facts.put(field, metadata.get(field));
          if (metadata.get("skus") instanceof List<?> skus)
            facts.put(
                "specs",
                skus.stream()
                    .limit(50)
                    .filter(Map.class::isInstance)
                    .map(s -> ((Map<?, ?>) s).get("specs"))
                    .toList());
          return facts;
        });
  }

  @Transactional
  public void complete(String runId, Map<String, Object> result) {
    var run = repository.findResultRun(runId);
    var message = repository.findResultMessage(run.get("message_id"));
    var conversation = store.scopedConversation((String) message.get("conversation_id"), true);
    if (!runId.equals(result.get("run_id"))
        || !run.get("message_id").equals(result.get("message_id")))
      throw new IllegalStateException("RUN_IDENTITY_MISMATCH");
    if (!Set.of("draft", "needs_human", "insufficient_evidence").contains(result.get("outcome"))
        || !(result.get("citations") instanceof List<?>)
        || !(result.get("risk_codes") instanceof List<?>)
        || !(result.get("content") instanceof String content)
        || content.length() > 4000) throw new IllegalStateException("INVALID_DRAFT_RESULT");
    if (result.containsKey("answered_message_ids")) {
      var allowed =
          batches.coverage(runId).stream()
              .map(q -> q.get("message_id"))
              .collect(java.util.stream.Collectors.toSet());
      if (!(result.get("answered_message_ids") instanceof List<?> ids)
          || !allowed.containsAll(ids)
          || new HashSet<>(ids).size() != ids.size()
          || ("draft".equals(result.get("outcome")) && ids.isEmpty())
          || (!"draft".equals(result.get("outcome")) && !ids.isEmpty()))
        throw new IllegalStateException("INVALID_ANSWER_COVERAGE");
    }
    var cards = com.pddcopilot.core.support.ProductCards.validate(result.get("product_cards"));
    if (!cards.isEmpty()
        && (!"draft".equals(result.get("outcome"))
            || cards.stream()
                .anyMatch(
                    card ->
                        ((List<?>) result.get("citations"))
                            .stream()
                                .noneMatch(
                                    c ->
                                        c instanceof Map<?, ?> citation
                                            && "platform_product"
                                                .equals(citation.get("source_type"))
                                            && card.get("goods_id")
                                                .equals(citation.get("source_id"))
                                            && card.get("fetched_at")
                                                .equals(citation.get("fetched_at"))))))
      throw new IllegalStateException("PRODUCT_CARD_EVIDENCE_MISSING");
    repository.completeRun(store.toJson(result), runId);
    if (WorkflowValues.asLong(conversation.get("revision"))
            != WorkflowValues.asLong(run.get("conversation_revision"))
        || !"open".equals(conversation.get("state"))) {
      repository.supersedeRun(runId);
      return;
    }
    var rows = repository.lockMessageDraft(message.get("id"));
    String draftId = rows.isEmpty() ? WorkflowValues.newId() : (String) rows.get(0).get("id");
    long revision = rows.isEmpty() ? 1 : WorkflowValues.asLong(rows.get(0).get("revision")) + 1;
    if (rows.isEmpty())
      repository.insertDraft(
          draftId,
          message.get("id"),
          runId,
          revision,
          conversation.get("revision"),
          result.get("content"),
          store.toJson(result.get("citations")),
          store.toJson(result.get("risk_codes")),
          run.get("knowledge_version"),
          result.get("outcome"),
          WorkflowValues.nowMillis());
    else
      repository.updateDraft(
          runId,
          revision,
          conversation.get("revision"),
          result.get("content"),
          store.toJson(result.get("citations")),
          store.toJson(result.get("risk_codes")),
          run.get("knowledge_version"),
          result.get("outcome"),
          WorkflowValues.nowMillis(),
          draftId);
    repository.markMessageWaiting(message.get("id"));
    repository.saveCards(draftId, store.toJson(cards));
    var riskCodes = (List<?>) result.get("risk_codes");
    String auditEvent =
        riskCodes.stream()
                .anyMatch(
                    code ->
                        Set.of(
                                "MODEL_REQUEST_FAILED",
                                "DRAFT_NOT_SUPPORTED",
                                "INVALID_MODEL_CITATIONS")
                            .contains(code))
            ? "agent.failed"
            : "insufficient_evidence".equals(result.get("outcome"))
                ? "draft.insufficient_evidence"
                : "needs_human".equals(result.get("outcome")) ? "draft.needs_human" : "draft.ready";
    store.recordAudit(auditEvent, draftId, "agent", runId);
  }
}
