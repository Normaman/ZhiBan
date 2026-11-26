package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ReplyBatchRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ReplyBatchMapper {
  int track(@Param("message") Object message, @Param("conversation") Object conversation);

  List<Map<String, Object>> pending(@Param("conversation") Object conversation);

  List<Map<String, Object>> coverage(@Param("run") Object run);

  int refreshSnapshotDeleteReplyRunMessages(@Param("run") Object run);

  int refreshSnapshotUpdateAgentRuns(
      @Param("now") long now, @Param("conversation") Object conversation, @Param("run") Object run);

  Map<String, Object> snapshotSelectAgentRuns(@Param("run") Object run);

  int snapshotInsertReplyRunMessages(
      @Param("run") Object run, @Param("messageId") Object messageId);

  List<Map<String, Object>> snapshotSelectReplyQuestions(
      @Param("conversation") Object conversation,
      @Param("sequence") Object sequence,
      @Param("start") long start,
      @Param("createdAt") Object createdAt);

  int snapshotInsertReplyRunMessages2(
      @Param("run") Object run, @Param("messageId") Object messageId);

  int single(@Param("run") Object run);

  Map<String, Object> result(@Param("run") Object run);

  List<Map<String, Object>> firstQueuedAt(@Param("conversation") Object conversation);

  int supersedeQueuedUpdateJobs(@Param("conversation") Object conversation);

  int supersedeQueuedUpdateAgentRuns(@Param("conversation") Object conversation);

  int deferUpdateAgentRuns(@Param("first") long first, @Param("run") Object run);

  int deferUpdateJobs(@Param("due") long due, @Param("run") Object run);

  int bindDraft(@Param("command") Object command, @Param("messageId") Object messageId);

  List<String> commandCoverage(@Param("command") Object command);

  Integer bindManualSelectReplyQuestions(
      @Param("id") String id, @Param("conversation") Object conversation);

  int bindManualInsertReplyCommandMessages(
      @Param("command") Object command, @Param("id") String id);

  int delivered(@Param("command") Object command, @Param("timestamp") long timestamp);
}
