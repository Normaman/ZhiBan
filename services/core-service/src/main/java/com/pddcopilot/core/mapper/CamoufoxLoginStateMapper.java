package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL mappings for CamoufoxLoginStateStore; transaction boundaries remain in the calling service.
 */
@Mapper
public interface CamoufoxLoginStateMapper {
  Boolean exists(@Param("shop") String shop);

  List<Map<String, Object>> load(@Param("shop") String shop);

  int save(
      @Param("shop") String shop,
      @Param("platformShop") String platformShop,
      @Param("account") String account,
      @Param("scope") Object scope,
      @Param("value") String value,
      @Param("now") long now);

  int refresh(
      @Param("scope") Object scope,
      @Param("value") String value,
      @Param("now") long now,
      @Param("shop") String shop,
      @Param("revision") Object revision);

  int invalidate(
      @Param("now") long now, @Param("shop") String shop, @Param("revision") Object revision);
}
