package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL mappings for AgentExecutionRepository; transaction boundaries remain in the calling service.
 */
@Mapper
public interface AgentExecutionMapper {
  Map<String, Object> lockRun(@Param("id") String id);

  List<Map<String, Object>> find(@Param("id") String id);

  int insert(
      @Param("id") String id,
      @Param("request") String request,
      @Param("lease") String lease,
      @Param("until") long until);

  int lease(@Param("lease") String lease, @Param("until") long until, @Param("id") String id);

  int complete(@Param("result") String result, @Param("now") long now, @Param("id") String id);
}
