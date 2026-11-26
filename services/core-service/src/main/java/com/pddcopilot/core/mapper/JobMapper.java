package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for JobRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface JobMapper {
  List<Map<String, Object>> claimableJobs(
      @Param("timestamp") Object timestamp, @Param("leaseExpiredBefore") Object leaseExpiredBefore);

  List<Map<String, Object>> findRunConversation(@Param("run") Object run);

  List<Map<String, Object>> tryLockConversation(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> tryLockJob(@Param("id") Object id);

  List<Map<String, Object>> findJob(@Param("id") Object id);

  Long countState(@Param("state") String state);

  List<Map<String, Object>> findUndelivered(
      @Param("now") long now,
      @Param("publicationCooldown") long publicationCooldown,
      @Param("limit") int limit);

  Integer countUnresolvedSends(@Param("conversation") Object conversation);

  Integer countOtherActiveGenerations(
      @Param("conversation") Object conversation,
      @Param("except") Object except,
      @Param("now") long now);

  Map<String, Object> generationCurrent(@Param("run") Object run);

  int discardJob(@Param("id") Object id);

  int discardRun(@Param("run") Object run);

  int claimJob(
      @Param("attempts") Object attempts,
      @Param("timestamp") Object timestamp,
      @Param("claimId") Object claimId,
      @Param("jobId") Object jobId);

  int markCommandSending(
      @Param("timestamp") Object timestamp, @Param("referenceId") Object referenceId);

  int markDraftSending(@Param("referenceId") Object referenceId);

  int markRunRunning(@Param("referenceId") Object referenceId);

  Map<String, Object> lockJob(@Param("jobId") Object jobId);

  int completeJob(@Param("jobId") Object jobId, @Param("claimId") Object claimId);

  int scheduleRetry(
      @Param("availableAt") Object availableAt,
      @Param("timestamp") Object timestamp,
      @Param("jobId") Object jobId,
      @Param("claimId") Object claimId);

  int failRun(@Param("reference") Object reference);

  int failMessage(@Param("reference") Object reference);
}
