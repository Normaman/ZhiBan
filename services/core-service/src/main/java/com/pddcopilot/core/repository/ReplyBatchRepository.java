package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ReplyBatchMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * Pending consultations and immutable generation/send coverage; only confirmed delivery resolves
 * them.
 */
@Repository
public class ReplyBatchRepository {
  private final ReplyBatchMapper mapper;
  private final BusinessStore store;

  public ReplyBatchRepository(ReplyBatchMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public void track(Object message, Object conversation) {
    mapper.track(message, conversation);
  }

  public List<Map<String, Object>> pending(Object conversation) {
    return mapper.pending(conversation);
  }

  public List<Map<String, Object>> coverage(Object run) {
    return mapper.coverage(run);
  }

  public void refreshSnapshot(Object run, Object conversation) {
    mapper.refreshSnapshotDeleteReplyRunMessages(run);
    snapshot(run, conversation);
    mapper.refreshSnapshotUpdateAgentRuns(System.currentTimeMillis(), conversation, run);
  }

  public void snapshot(Object run, Object conversation) {
    var anchor = DatabaseRows.required(mapper.snapshotSelectAgentRuns(run));
    if (Boolean.TRUE.equals(anchor.get("single_question"))) {
      mapper.snapshotInsertReplyRunMessages(run, anchor.get("message_id"));
      return;
    }
    long start = ((Number) anchor.get("created_at")).longValue() - 8000;
    var rows =
        mapper.snapshotSelectReplyQuestions(
            conversation, anchor.get("sequence"), start, anchor.get("created_at"));
    for (var row : rows) mapper.snapshotInsertReplyRunMessages2(run, row.get("message_id"));
  }

  public void single(Object run, Object conversation) {
    mapper.single(run);
    refreshSnapshot(run, conversation);
  }

  public Map<?, ?> result(Object run) {
    Object raw = DatabaseRows.required(mapper.result(run)).get("result");
    return raw instanceof String text && store.fromJson(text) instanceof Map<?, ?> value
        ? value
        : Map.of();
  }

  public List<Map<String, Object>> draftCoverage(Object run) {
    var result = result(run);
    var all = coverage(run);
    if (!(result.get("answered_message_ids") instanceof List<?> ids)) return all;
    return all.stream().filter(q -> ids.contains(q.get("message_id"))).toList();
  }

  public long firstQueuedAt(Object conversation, long fallback) {
    var rows = mapper.firstQueuedAt(conversation);
    return rows.get(0).get("first_at") instanceof Number n ? n.longValue() : fallback;
  }

  public void supersedeQueued(Object conversation) {
    // Running/previously attempted runs keep their immutable context and lease.
    mapper.supersedeQueuedUpdateJobs(conversation);
    mapper.supersedeQueuedUpdateAgentRuns(conversation);
  }

  public void defer(Object run, long due, long first) {
    mapper.deferUpdateAgentRuns(first, run);
    mapper.deferUpdateJobs(due, run);
  }

  public void bindDraft(Object command, Object run) {
    for (var question : draftCoverage(run)) mapper.bindDraft(command, question.get("message_id"));
  }

  public Set<String> commandCoverage(Object command) {
    return new HashSet<>(mapper.commandCoverage(command));
  }

  public void bindManual(Object command, Object conversation, Set<String> ids) {
    for (String id : ids) {
      if (mapper.bindManualSelectReplyQuestions(id, conversation) != 1)
        throw com.pddcopilot.core.support.WorkflowValues.conflict("QUESTION_NOT_PENDING");
      mapper.bindManualInsertReplyCommandMessages(command, id);
    }
  }

  public void delivered(Object command, long timestamp) {
    mapper.delivered(command, timestamp);
  }
}
