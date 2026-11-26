package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.JobRepository;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable leases and retry policy. An expired claim cannot overwrite its successor's result. */
@Service
public class JobService {
  private static final long LEASE_MS = Duration.ofMinutes(2).toMillis();
  private static final long RETRY_DELAY_MS = Duration.ofSeconds(3).toMillis();
  private static final int MAX_ATTEMPTS = 4;
  private final BusinessStore store;
  private final JobRepository repository;
  private final AgentRunService runs;
  private final ReplySendService sends;
  private final ProductService products;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final com.pddcopilot.core.repository.ProductRepository productRepository;

  public JobService(
      BusinessStore store,
      AgentRunService runs,
      ReplySendService sends,
      JobRepository repository,
      ProductService products,
      com.pddcopilot.core.repository.ProductRepository productRepository,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.repository = repository;
    this.store = store;
    this.runs = runs;
    this.sends = sends;
    this.products = products;
    this.productRepository = productRepository;
    this.batches = batches;
  }

  @Transactional
  public Map<String, Object> claim() {
    var rows = repository.claimableJobs(nowMillis(), nowMillis());
    for (var candidate : rows) {
      var job = claimCandidate(candidate);
      if (job != null) return job;
    }
    return null;
  }

  @Transactional
  public Map<String, Object> claim(String jobId) {
    var rows = repository.findJob(jobId);
    return rows.isEmpty() ? null : claimCandidate(rows.get(0));
  }

  private Map<String, Object> claimCandidate(Map<String, Object> candidate) {
    Map<String, Object> conversation = null;
    if ("generate".equals(candidate.get("kind"))) {
      conversation = repository.generationConversation(candidate.get("reference_id"));
      if (conversation == null) return null;
    }
    var job = repository.tryLockJob(candidate.get("id"));
    if (job == null) return null;
    long now = nowMillis();
    if (!("pending".equals(job.get("state")) && asLong(job.get("available_at")) <= now)
        && !("running".equals(job.get("state")) && asLong(job.get("locked_until")) < now))
      return null;
    if (conversation != null) {
      if (repository.generationBusy(conversation.get("id"), job.get("id"), now)) return null;
      if (!repository.generationCurrent(job.get("reference_id"), conversation)) {
        repository.discard(job.get("id"), job.get("reference_id"));
        return null;
      }
    }
    if (conversation != null && asLong(job.get("attempts")) == 0) {
      batches.refreshSnapshot(job.get("reference_id"), conversation.get("id"));
      if (batches.coverage(job.get("reference_id")).isEmpty()) {
        repository.discard(job.get("id"), job.get("reference_id"));
        return null;
      }
    }
    job.put("claim_id", newId());
    int attempts = ((Number) job.get("attempts")).intValue() + 1;
    job.put("attempts", attempts);
    repository.claimJob(attempts, nowMillis() + LEASE_MS, job.get("claim_id"), job.get("id"));
    switch (job.get("kind").toString()) {
      case "send" -> {
        repository.markCommandSending(nowMillis(), job.get("reference_id"));
        repository.markDraftSending(job.get("reference_id"));
      }
      case "generate" -> repository.markRunRunning(job.get("reference_id"));
      case "product_detail" -> productRepository.markRunning(job.get("reference_id"));
      default -> throw new IllegalStateException("UNKNOWN_JOB_KIND");
    }
    return job;
  }

  @Transactional
  public void deferDelivery(String jobId) {
    var rows = repository.findJob(jobId);
    if (rows.isEmpty()) return;
    var current = rows.get(0);
    long now = nowMillis();
    boolean pending = "pending".equals(current.get("state"));
    boolean expired = "running".equals(current.get("state"))
        && asLong(current.get("locked_until")) < now;
    if (pending || expired) {
      long due = Math.max(asLong(current.get("available_at")), now + RETRY_DELAY_MS);
      store.enqueueJobEvent(jobId, due);
    }
  }

  @Transactional
  public int reconcileUndelivered() {
    long now = nowMillis();
    var rows = repository.findUndelivered(now, now - Duration.ofMinutes(5).toMillis(), 100);
    for (var row : rows) {
      store.enqueueJobEvent(row.get("id").toString(), Math.max(now, asLong(row.get("available_at"))));
    }
    return rows.size();
  }

  public boolean owns(Map<String, Object> job) {
    var current = repository.lockJob(job.get("id"));
    return "running".equals(current.get("state"))
        && job.get("claim_id").equals(current.get("claim_id"));
  }

  @Transactional
  public boolean complete(Map<String, Object> job, Map<String, Object> result) {
    if (!owns(job)) return false;
    String reference = (String) job.get("reference_id");
    if ("generate".equals(job.get("kind"))) runs.complete(reference, result);
    else if ("send".equals(job.get("kind"))) sends.complete(reference, result);
    else if ("product_detail".equals(job.get("kind"))) products.complete(reference, result);
    else throw new IllegalStateException("UNKNOWN_JOB_KIND");
    repository.completeJob(job.get("id"), job.get("claim_id"));
    return true;
  }

  @Transactional
  public boolean fail(Map<String, Object> job) {
    try (var scope = com.pddcopilot.core.support.ShopScope.open(job.get("shop_id").toString())) {
      return failScoped(job);
    }
  }

  private boolean failScoped(Map<String, Object> job) {
    if (!owns(job)) return false;
    boolean terminal = asLong(job.get("attempts")) >= MAX_ATTEMPTS;
    repository.scheduleRetry(
        terminal ? "failed" : "pending",
        nowMillis() + RETRY_DELAY_MS,
        job.get("id"),
        job.get("claim_id"));
    if (!terminal) store.enqueueJobEvent(job.get("id").toString(), nowMillis() + RETRY_DELAY_MS);
    String reference = (String) job.get("reference_id");
    if (terminal && "send".equals(job.get("kind"))) {
      sends.complete(
          reference, Map.of("state", "send_unknown", "error_code", "CONNECTOR_RESULT_UNCONFIRMED"));
    } else if (terminal && "product_detail".equals(job.get("kind"))) {
      products.fail(reference);
    } else if (terminal) {
      repository.failRun(reference);
      repository.failMessage(reference);
    }
    return true;
  }
}
