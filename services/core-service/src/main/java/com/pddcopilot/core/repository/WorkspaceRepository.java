package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.WorkspaceMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Membership is queried on every business request, including requests with existing sessions. */
@Repository
public class WorkspaceRepository {
  private final WorkspaceMapper mapper;

  public WorkspaceRepository(WorkspaceMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> accessibleShops(String username) {
    return mapper.accessibleShops(username);
  }

  public List<Map<String, Object>> members(String workspace) {
    return mapper.members(workspace);
  }

  public boolean workspaceExists(String id) {
    return !mapper.workspaceExists(id).isEmpty();
  }

  public void insertWorkspace(String id, String name, String owner, long created) {
    mapper.insertWorkspace(id, name, owner, created);
  }

  public boolean isMember(String user, String workspace) {
    return !mapper.isMember(user, workspace).isEmpty();
  }

  public void insertMembership(String user, String workspace, String role, long created) {
    mapper.insertMembership(workspace, user, role, created);
  }

  public void setInitialOwner(String user, String workspace) {
    mapper.setInitialOwner(user, workspace);
  }

  public void insertShop(String id, String name, String workspace, String mode) {
    mapper.insertShop(id, name, "unbound", "unbound", "live", workspace, "unbound");
  }

  public Map<String, Object> shop(String id, boolean lock) {
    return DatabaseRows.single(mapper.shop(id, lock));
  }

  public int shopCount(String workspace) {
    return mapper.shopCount(workspace);
  }

  public Map<String, Object> workspace(String id) {
    return DatabaseRows.single(mapper.workspace(id));
  }

  public List<Map<String, Object>> invitations(String workspace) {
    return mapper.invitations(workspace);
  }

  public void renameShop(String id, String name) {
    mapper.renameShop(name, id);
  }

  public void insertInvitation(
      String id, String workspace, String hash, String actor, long created, long expires) {
    mapper.insertInvitation(id, workspace, hash, actor, created, expires);
  }

  public int revokeInvitation(String id, String workspace) {
    return mapper.revokeInvitation(id, workspace);
  }

  public List<Map<String, Object>> lockInvitation(String hash) {
    return mapper.lockInvitation(hash);
  }

  public void consumeInvitation(String id, long timestamp) {
    mapper.consumeInvitation(timestamp, id);
  }

  public String firstShop(String workspace) {
    return DatabaseRows.single(mapper.firstShop(workspace)).get("id").toString();
  }

  public Map<String, Object> binding(String shop) {
    return DatabaseRows.single(mapper.binding(shop));
  }

  public void claimPlatform(String platform, String shop) {
    mapper.claimPlatform(platform, shop);
  }

  public boolean platformClaimedByAnotherShop(String platform, String shop) {
    return !mapper.platformClaimedByAnotherShop(platform, shop).isEmpty();
  }

  public void bind(String shop, String platform, String account) {
    mapper.bind(platform, account, shop);
  }
}
