package com.pddcopilot.core.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceMemberMapper {
  Map<String, Object> lockWorkspace(@Param("workspace") String workspace);

  Map<String, Object> member(@Param("workspace") String workspace, @Param("user") String user);

  Map<String, Object> actor(
      @Param("workspace") String workspace, @Param("username") String username);

  int setEnabled(
      @Param("workspace") String workspace,
      @Param("user") String user,
      @Param("enabled") boolean enabled,
      @Param("revision") long revision);

  int remove(
      @Param("workspace") String workspace,
      @Param("user") String user,
      @Param("revision") long revision);
}
