package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for PddEventSpool; transaction boundaries remain in the calling service. */
@Mapper
public interface PddEventSpoolMapper {
  int insert(
      @Param("value") String value,
      @Param("shopId") String shopId,
      @Param("eventKey") String eventKey,
      @Param("type") String type,
      @Param("writeValueAsString") Object writeValueAsString,
      @Param("now") long now,
      @Param("now2") long now2);

  List<Map<String, Object>> drain(@Param("now") long now);

  int deliverUpdatePddEventSpool(@Param("now") long now, @Param("id") Object id);

  int deliverUpdatePddEventSpool2(
      @Param("now") Object now, @Param("code") String code, @Param("id") Object id);
}
