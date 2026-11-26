package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ConnectionRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ConnectionMapper {
  List<Map<String, Object>> findShop(@Param("shopId") Object shopId);

  List<Map<String, Object>> findBoundLiveShops();

  Map<String, Object> lockShop(@Param("shop") Object shop);

  int updateConnection(
      @Param("state") Object state,
      @Param("timestamp") Object timestamp,
      @Param("epoch") Object epoch,
      @Param("sequence") Object sequence,
      @Param("errorCode") Object errorCode,
      @Param("shop") Object shop);

  int updateProfile(
      @Param("name") String name,
      @Param("avatar") String avatar,
      @Param("category") String category,
      @Param("observedAt") long observedAt,
      @Param("shop") String shop);
}
