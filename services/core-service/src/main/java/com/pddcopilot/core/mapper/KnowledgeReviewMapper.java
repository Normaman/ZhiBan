package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL mappings for KnowledgeReviewRepository; transaction boundaries remain in the calling service.
 */
@Mapper
public interface KnowledgeReviewMapper {
  Map<String, Object> baseVersion(@Param("shop") String shop, @Param("version") String version);

  int updateDraft(
      @Param("shop") String shop,
      @Param("id") String id,
      @Param("base") String base,
      @Param("snapshot") String snapshot);

  int recordRevision(
      @Param("shop") String shop,
      @Param("id") String id,
      @Param("actor") String actor,
      @Param("at") long at);

  List<Map<String, Object>> history(@Param("shop") String shop, @Param("id") String id);

  List<Map<String, Object>> list(@Param("shop") String shop);

  Map<String, Object> find(
      @Param("shop") String shop, @Param("id") String id, @Param("lock") boolean lock);

  int insert(
      @Param("id") String id,
      @Param("shop") String shop,
      @Param("base") String base,
      @Param("snapshot") String snapshot,
      @Param("actor") String actor,
      @Param("at") long at);

  int transition(
      @Param("state") String state,
      @Param("actor") String actor,
      @Param("at") long at,
      @Param("note") String note,
      @Param("version") Object version,
      @Param("shop") String shop,
      @Param("id") String id);
}
