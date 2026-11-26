package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for KnowledgeRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface KnowledgeMapper {
  Map<String, Object> lockShop(@Param("shop") Object shop);

  List<Map<String, Object>> findVersion(
      @Param("shopId") String shopId, @Param("version") Object version);

  int insertVersion(
      @Param("version") Object version,
      @Param("shop") Object shop,
      @Param("snapshot") Object snapshot,
      @Param("timestamp") Object timestamp,
      @Param("label") Object label);

  int activateVersion(@Param("version") Object version, @Param("shop") Object shop);

  int insertProductSnapshot(
      @Param("snapshot") Object snapshot,
      @Param("shop") Object shop,
      @Param("payloadJson") Object payloadJson,
      @Param("timestamp") Object timestamp);

  int checkedProducts(@Param("now") long now, @Param("id") Object id);

  List<Map<String, Object>> listVersions(@Param("shop") Object shop);

  List<Map<String, Object>> activeVersion(@Param("shop") String shop);

  List<Map<String, Object>> findLatestProducts(@Param("shop") Object shop);
}
