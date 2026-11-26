package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for WorkspaceRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface WorkspaceMapper {
  List<Map<String, Object>> accessibleShops(@Param("username") String username);

  List<Map<String, Object>> members(@Param("workspace") String workspace);

  List<Map<String, Object>> workspaceExists(@Param("id") String id);

  int insertWorkspace(
      @Param("id") String id,
      @Param("name") String name,
      @Param("owner") String owner,
      @Param("created") long created);

  List<Map<String, Object>> isMember(
      @Param("user") String user, @Param("workspace") String workspace);

  int insertMembership(
      @Param("workspace") String workspace,
      @Param("user") String user,
      @Param("role") String role,
      @Param("created") long created);

  int setInitialOwner(@Param("user") String user, @Param("workspace") String workspace);

  int insertShop(
      @Param("id") String id,
      @Param("name") String name,
      @Param("value") String value,
      @Param("value2") String value2,
      @Param("value3") String value3,
      @Param("workspace") String workspace,
      @Param("value4") String value4);

  Map<String, Object> shop(@Param("id") String id, @Param("lock") boolean lock);

  Integer shopCount(@Param("workspace") String workspace);

  Map<String, Object> workspace(@Param("id") String id);

  List<Map<String, Object>> invitations(@Param("workspace") String workspace);

  int renameShop(@Param("name") String name, @Param("id") String id);

  int insertInvitation(
      @Param("id") String id,
      @Param("workspace") String workspace,
      @Param("hash") String hash,
      @Param("actor") String actor,
      @Param("created") long created,
      @Param("expires") long expires);

  int revokeInvitation(@Param("id") String id, @Param("workspace") String workspace);

  List<Map<String, Object>> lockInvitation(@Param("hash") String hash);

  int consumeInvitation(@Param("timestamp") long timestamp, @Param("id") String id);

  Map<String, Object> firstShop(@Param("workspace") String workspace);

  Map<String, Object> binding(@Param("shop") String shop);

  int claimPlatform(@Param("platform") String platform, @Param("shop") String shop);

  List<Map<String, Object>> platformClaimedByAnotherShop(
      @Param("platform") String platform, @Param("shop") String shop);

  int bind(
      @Param("platform") String platform,
      @Param("account") String account,
      @Param("shop") String shop);
}
