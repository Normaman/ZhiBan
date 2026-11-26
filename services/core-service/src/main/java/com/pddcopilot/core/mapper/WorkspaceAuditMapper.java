package com.pddcopilot.core.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for WorkspaceService; transaction boundaries remain in the calling service. */
@Mapper
public interface WorkspaceAuditMapper {
  int bindConnectorForShop(
      @Param("id") String id,
      @Param("shopId") String shopId,
      @Param("value") String value,
      @Param("id2") String id2,
      @Param("actor") String actor,
      @Param("id3") String id3,
      @Param("now") long now);
}
