package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.AgentExecutionInput;
import com.pddcopilot.core.repository.AgentExecutionRepository;
import com.pddcopilot.core.repository.BusinessStore;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinate Python replicas without local files or a second source of business truth. */
@Service
public class AgentExecutionService {
  private static final long LEASE_MS = 100_000;
  private final AgentExecutionRepository repository;
  private final BusinessStore store;

  public AgentExecutionService(AgentExecutionRepository repository, BusinessStore store) {
    this.repository = repository;
    this.store = store;
  }

  @Transactional
  public Map<String, Object> claim(String id, AgentExecutionInput.Request input) {
    var run = repository.lockRun(id);
    var identity = Map.of(
        "run_id", id, "shop_id", run.get("shop_id"),
        "conversation_id", run.get("conversation_id"), "message_id", run.get("message_id"),
        "knowledge_version", Objects.toString(run.get("knowledge_version"), ""));
    var requested = Map.of(
        "run_id", input.runId(), "shop_id", input.shopId(),
        "conversation_id", input.conversationId(), "message_id", input.messageId(),
        "knowledge_version", input.knowledgeVersion());
    if (!identity.equals(requested)) throw conflict("RUN_SCOPE_MISMATCH");
    var cached = repository.find(id);
    if (cached != null) {
      if (!requested.equals(store.fromJson(cached.get("request_payload").toString())))
        throw conflict("RUN_PAYLOAD_CONFLICT");
      if (cached.get("result") != null)
        return Map.of("state", "completed", "result", store.fromJson(cached.get("result").toString()));
    }
    if (!Set.of("queued", "running").contains(run.get("state")))
      throw conflict("RUN_NOT_EXECUTABLE");
    long now = nowMillis();
    if (cached != null && asLong(cached.get("lease_until")) > now)
      return Map.of("state", "running");
    String lease = newId();
    if (cached == null) repository.insert(id, store.toJson(requested), lease, now + LEASE_MS);
    else repository.lease(id, lease, now + LEASE_MS);
    return Map.of("state", "claimed", "lease_id", lease);
  }

  @Transactional
  public void complete(String id, AgentExecutionInput.Completion input) {
    var run = repository.lockRun(id);
    var cached = requireOwner(id, input.leaseId());
    String serialized = store.toJson(input.result());
    if (cached.get("result") != null) {
      if (!store.fromJson(cached.get("result").toString()).equals(store.fromJson(serialized)))
        throw conflict("RUN_RESULT_CONFLICT");
      return;
    }
    var result = input.result();
    if (!id.equals(result.get("run_id")) || !run.get("message_id").equals(result.get("message_id"))
        || !Objects.toString(run.get("knowledge_version"), "").equals(
            Objects.toString(result.get("knowledge_version"), ""))
        || !(result.get("conversation_revision") instanceof Number revision)
        || asLong(run.get("conversation_revision")) != revision.longValue()
        || !(result.get("outcome") instanceof String outcome)
        || !Set.of("draft", "needs_human", "insufficient_evidence").contains(outcome)
        || !(result.get("content") instanceof String content) || content.length() > 4000
        || !(result.get("citations") instanceof java.util.List<?>)
        || !(result.get("risk_codes") instanceof java.util.List<?>)
        || serialized.length() > 2_000_000)
      throw conflict("INVALID_DRAFT_RESULT");
    repository.complete(id, serialized, nowMillis());
  }

  @Transactional
  public void release(String id, AgentExecutionInput.Release input) {
    repository.lockRun(id);
    var cached = repository.find(id);
    if (cached != null && input.leaseId().equals(cached.get("lease_id")) && cached.get("result") == null)
      repository.lease(id, null, 0);
  }

  private Map<String, Object> requireOwner(String id, String lease) {
    var cached = repository.find(id);
    if (cached == null || !lease.equals(cached.get("lease_id"))
        || (cached.get("result") == null && asLong(cached.get("lease_until")) <= nowMillis()))
      throw conflict("RUN_LEASE_LOST");
    return cached;
  }
}
