package com.pddcopilot.core.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for BusinessStore; transaction boundaries remain in the calling service. */
@Mapper
public interface BusinessMapper {
  Map<String, Object> scopedConversation(
      @Param("conversationId") String conversationId, @Param("lock") boolean lock);

  Map<String, Object> findJob(@Param("kind") String kind, @Param("ref") String ref);

  int recordAudit(
      @Param("id") String id,
      @Param("shopId") Object shopId,
      @Param("type") String type,
      @Param("subject") String subject,
      @Param("actor") String actor,
      @Param("trace") String trace,
      @Param("now") long now);

  int enqueueJob(
      @Param("id") String id,
      @Param("kind") String kind,
      @Param("ref") String ref,
      @Param("now") long now,
      @Param("shopId") Object shopId);

  int enqueueJobEvent(
      @Param("id") String id,
      @Param("jobId") String jobId,
      @Param("availableAt") long availableAt,
      @Param("now") long now);
}
