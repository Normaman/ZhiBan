package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for AuthenticationAudit; transaction boundaries remain in the calling service. */
@Mapper
public interface AuthenticationEventMapper {
  int record(
      @Param("id") Object id,
      @Param("type") String type,
      @Param("value") Object value,
      @Param("hash") Object hash,
      @Param("hash2") Object hash2,
      @Param("now") long now);

  List<Map<String, Object>> recent(@Param("username") String username);
}
