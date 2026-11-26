package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ShopRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ShopMapper {
  List<Map<String, Object>> findShopIds(@Param("shopId") Object shopId);

  int insertShop(
      @Param("shopId") Object shopId,
      @Param("shopName") Object shopName,
      @Param("account") Object account,
      @Param("platform") Object platform,
      @Param("mode") Object mode,
      @Param("value") String value);

  Map<String, Object> findShop(@Param("shopId") Object shopId);

  int resetConnection(@Param("shopId") Object shopId);
}
