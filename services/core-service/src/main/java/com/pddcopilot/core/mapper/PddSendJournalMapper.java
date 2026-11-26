package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for PddSendJournal; transaction boundaries remain in the calling service. */
@Mapper
public interface PddSendJournalMapper {
  int beginInsertPddSendParts(
      @Param("shopId") String shopId,
      @Param("commandId") String commandId,
      @Param("attempt") int attempt,
      @Param("key") String key,
      @Param("payloadHash") String payloadHash,
      @Param("now") long now);

  Map<String, Object> beginSelectPddSendParts(
      @Param("commandId") String commandId,
      @Param("attempt") int attempt,
      @Param("key") String key);

  List<Map<String, Object>> find(
      @Param("shopId") String shopId,
      @Param("commandId") String commandId,
      @Param("attempt") int attempt,
      @Param("key") String key);

  int finish(
      @Param("state") String state,
      @Param("toJson") Object toJson,
      @Param("now") long now,
      @Param("shopId") String shopId,
      @Param("commandId") String commandId,
      @Param("attempt") int attempt,
      @Param("key") String key);
}
