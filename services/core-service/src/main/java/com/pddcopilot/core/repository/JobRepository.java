package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.JobMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for JobService. Mutations use the calling service's transaction. */
@Repository
public class JobRepository {
  private final JobMapper mapper;
  private final BusinessStore store;

  public JobRepository(JobMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> claimableJobs(Object timestamp, Object leaseExpiredBefore) {
    return mapper.claimableJobs(timestamp, leaseExpiredBefore);
  }

  public Map<String, Object> generationConversation(Object run) {
    var ids = mapper.findRunConversation(run);
    if (ids.isEmpty()) return null;
    var rows = mapper.tryLockConversation(ids.get(0).get("conversation_id"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> tryLockJob(Object id) {
    var rows = mapper.tryLockJob(id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public List<Map<String, Object>> findJob(Object id) {
    return mapper.findJob(id);
  }

  public long countState(String state) {
    Long count = mapper.countState(state);
    return count == null ? 0 : count;
  }

  public List<Map<String, Object>> findUndelivered(long now, long publicationCooldown, int limit) {
    return mapper.findUndelivered(now, publicationCooldown, limit);
  }

  public boolean generationBusy(Object conversation, Object except, long now) {
    if (mapper.countUnresolvedSends(conversation) > 0) return true;
    return mapper.countOtherActiveGenerations(conversation, except, now) > 0;
  }

  public boolean generationCurrent(Object run, Map<String, Object> conversation) {
    var r = DatabaseRows.required(mapper.generationCurrent(run));
    return "open".equals(conversation.get("state"))
        && ("queued".equals(r.get("state"))
            || ((Number) r.get("conversation_revision")).longValue()
                == ((Number) conversation.get("revision")).longValue())
        && java.util.Set.of("queued", "running").contains(r.get("state"));
  }

  public void discard(Object id, Object run) {
    mapper.discardJob(id);
    mapper.discardRun(run);
  }

  public int claimJob(Object attempts, Object timestamp, Object claimId, Object jobId) {
    return mapper.claimJob(attempts, timestamp, claimId, jobId);
  }

  public int markCommandSending(Object timestamp, Object referenceId) {
    return mapper.markCommandSending(timestamp, referenceId);
  }

  public int markDraftSending(Object referenceId) {
    return mapper.markDraftSending(referenceId);
  }

  public int markRunRunning(Object referenceId) {
    return mapper.markRunRunning(referenceId);
  }

  public Map<String, Object> lockJob(Object jobId) {
    return DatabaseRows.required(mapper.lockJob(jobId));
  }

  public int completeJob(Object jobId, Object claimId) {
    return mapper.completeJob(jobId, claimId);
  }

  public int scheduleRetry(Object availableAt, Object timestamp, Object jobId, Object claimId) {
    return mapper.scheduleRetry(availableAt, timestamp, jobId, claimId);
  }

  public int failRun(Object reference) {
    return mapper.failRun(reference);
  }

  public int failMessage(Object reference) {
    return mapper.failMessage(reference);
  }
}
