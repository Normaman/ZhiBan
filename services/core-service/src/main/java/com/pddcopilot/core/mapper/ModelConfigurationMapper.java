package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL mappings for ModelConfigurationRepository; transaction boundaries remain in the calling
 * service.
 */
@Mapper
public interface ModelConfigurationMapper {
  List<Map<String, Object>> settings(@Param("shopId") String shopId);

  Map<String, Object> lockShop(@Param("shopId") String shopId);

  Map<String, Object> version(@Param("id") String id, @Param("shopId") String shopId);

  int insert(
      @Param("id") String id,
      @Param("shopId") String shopId,
      @Param("version") long version,
      @Param("url") String url,
      @Param("model") String model,
      @Param("encryptedKey") String encryptedKey,
      @Param("hint") String hint,
      @Param("rounds") int rounds,
      @Param("timeout") int timeout,
      @Param("now") long now);

  int saveSettingsInsertModelSettings(
      @Param("shopId") String shopId,
      @Param("revision") long revision,
      @Param("latest") Object latest,
      @Param("active") Object active,
      @Param("environmentFallback") boolean environmentFallback);

  int saveSettingsUpdateModelSettings(
      @Param("revision") long revision,
      @Param("latest") Object latest,
      @Param("active") Object active,
      @Param("environmentFallback") boolean environmentFallback,
      @Param("shopId") String shopId);

  int tested(
      @Param("value") Object value,
      @Param("errorCode") Object errorCode,
      @Param("now") long now,
      @Param("id") String id,
      @Param("shopId") String shopId);
}
