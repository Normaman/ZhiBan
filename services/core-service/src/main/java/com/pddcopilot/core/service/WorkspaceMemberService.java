package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.mapper.WorkspaceMemberMapper;
import com.pddcopilot.core.repository.BusinessStore;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Membership changes never delete the identity or historical approval records. */
@Service
public class WorkspaceMemberService {
  private final WorkspaceMemberMapper members;
  private final WorkspaceService workspaces;
  private final BusinessStore store;

  public WorkspaceMemberService(
      WorkspaceMemberMapper members, WorkspaceService workspaces, BusinessStore store) {
    this.members = members;
    this.workspaces = workspaces;
    this.store = store;
  }

  @Transactional
  public Map<String, Object> setEnabled(String id, long revision, boolean enabled, String actor) {
    String workspace = check(id, revision, actor);
    if (members.setEnabled(workspace, id, enabled, revision) != 1)
      throw conflict("MEMBER_REVISION_CONFLICT");
    store.recordAudit(enabled ? "member.enabled" : "member.disabled", id, actor, newId());
    return Map.of("enabled", enabled, "revision", revision + 1);
  }

  @Transactional
  public Map<String, Object> remove(String id, long revision, String actor) {
    String workspace = check(id, revision, actor);
    if (members.remove(workspace, id, revision) != 1) throw conflict("MEMBER_REVISION_CONFLICT");
    store.recordAudit("member.removed", id, actor, newId());
    return Map.of("removed", true);
  }

  private String check(String id, long revision, String actor) {
    String workspace = workspaces.workspaceId();
    var space = members.lockWorkspace(workspace);
    var manager = members.actor(workspace, actor);
    if (manager == null
        || !Boolean.TRUE.equals(manager.get("enabled"))
        || !Boolean.TRUE.equals(manager.get("account_enabled"))
        || !java.util.Set.of("OWNER", "ADMIN").contains(manager.get("role")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    var target = members.member(workspace, id);
    if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND");
    if (actor.equals(target.get("username"))
        || id.equals(space.get("owner_user_id"))
        || !"REVIEWER".equals(target.get("role"))) throw conflict("MEMBER_NOT_MANAGEABLE");
    if (((Number) target.get("revision")).longValue() != revision)
      throw conflict("MEMBER_REVISION_CONFLICT");
    return workspace;
  }
}
