package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.mapper.WorkspaceAuditMapper;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.WorkspaceRepository;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {
  private final WorkspaceAuditMapper mapper;
  private final BusinessStore store;
  private final WorkspaceRepository repository;

  public WorkspaceService(
      WorkspaceAuditMapper mapper, BusinessStore store, WorkspaceRepository repository) {
    this.mapper = mapper;
    this.store = store;
    this.repository = repository;
  }

  public record RegistrationScope(String workspaceId, String shopId) {}

  public void ensureLegacyWorkspace(String shop, String name) {
    if (!repository.workspaceExists(shop))
      repository.insertWorkspace(shop, name, null, nowMillis());
  }

  public void ensureMembership(String user, String workspace, String role) {
    if (!repository.isMember(user, workspace))
      repository.insertMembership(user, workspace, role, nowMillis());
    if ("ADMIN".equals(role)) repository.setInitialOwner(user, workspace);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public RegistrationScope createWorkspace(String owner, String name) {
    String workspace = newId();
    repository.insertWorkspace(workspace, name, owner, nowMillis());
    return new RegistrationScope(workspace, insertShop(workspace, "我的店铺"));
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public RegistrationScope consumeInvitation(String token) {
    var invitations = repository.lockInvitation(sha256(token));
    if (invitations.isEmpty()
        || Boolean.TRUE.equals(invitations.get(0).get("revoked"))
        || invitations.get(0).get("consumed_at") != null
        || asLong(invitations.get(0).get("expires_at")) <= nowMillis())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "INVITE_INVALID");
    var invitation = invitations.get(0);
    String workspace = invitation.get("workspace_id").toString();
    repository.consumeInvitation(invitation.get("id").toString(), nowMillis());
    return new RegistrationScope(workspace, repository.firstShop(workspace));
  }

  private String insertShop(String workspace, String name) {
    String id = newId();
    repository.insertShop(id, name, workspace, "live");
    return id;
  }

  public String workspaceId() {
    return repository.shop(store.shopId(), false).get("workspace_id").toString();
  }

  public Map<String, Object> overview(String user) {
    String workspace = workspaceId();
    return Map.of(
        "workspace",
        repository.workspace(workspace),
        "shops",
        repository.accessibleShops(user).stream()
            .filter(s -> workspace.equals(s.get("workspace_id")))
            .toList(),
        "members",
        repository.members(workspace),
        "invitations",
        repository.invitations(workspace));
  }

  @Transactional
  public Map<String, Object> addShop(String name, String actor) {
    String shop = insertShop(workspaceId(), name.trim());
    store.recordAudit("shop.created", shop, actor, shop);
    return Map.of("shop_id", shop);
  }

  @Transactional
  public Map<String, Object> addLiveShop(String name, String actor) {
    return addShop(name, actor);
  }

  @Transactional
  public Map<String, Object> renameShop(String name, String actor) {
    repository.renameShop(store.shopId(), name.trim());
    store.recordAudit("shop.renamed", store.shopId(), actor, newId());
    return Map.of("name", name.trim());
  }

  @Transactional
  public Map<String, Object> invite(String actor) {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    String token = "team_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random),
        id = newId();
    long expires = nowMillis() + java.time.Duration.ofHours(48).toMillis();
    repository.insertInvitation(id, workspaceId(), sha256(token), actor, nowMillis(), expires);
    store.recordAudit("invitation.created", id, actor, id);
    return Map.of("id", id, "invite_code", token, "expires_at", expires);
  }

  @Transactional
  public void revokeInvite(String id, String actor) {
    if (repository.revokeInvitation(id, workspaceId()) == 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
    store.recordAudit("invitation.revoked", id, actor, id);
  }

  public Map<String, Object> connectorBinding() {
    return repository.binding(store.shopId());
  }

  @Transactional(readOnly = true)
  public void validateConnectorBinding(String shopId, String platform, String account) {
    if (platform == null || platform.isBlank() || account == null || account.isBlank())
      throw conflict("PDD_IDENTITY_INVALID");
    var shop = repository.shop(shopId, false);
    if (!"live".equals(shop.get("mode"))) throw conflict("LIVE_SHOP_REQUIRED");
    if ("bound".equals(shop.get("binding_state"))
        && (!platform.equals(shop.get("platform_shop_id"))
            || !account.equals(shop.get("connector_account_id"))))
      throw conflict("SHOP_ALREADY_BOUND");
    if (repository.platformClaimedByAnotherShop(platform, shopId))
      throw conflict("PLATFORM_SHOP_ALREADY_BOUND");
  }

  @Transactional
  public Map<String, Object> bindConnector(String platform, String account, String actor) {
    return bindConnectorForShop(store.shopId(), platform, account, actor);
  }

  @Transactional
  public Map<String, Object> bindConnectorForShop(
      String shopId, String platform, String account, String actor) {
    if (platform == null || platform.isBlank() || account == null || account.isBlank())
      throw conflict("PDD_IDENTITY_INVALID");
    var shop = repository.shop(shopId, true);
    if (!"live".equals(shop.get("mode"))) throw conflict("LIVE_SHOP_REQUIRED");
    if ("bound".equals(shop.get("binding_state"))) {
      if (!platform.equals(shop.get("platform_shop_id"))
          || !account.equals(shop.get("connector_account_id")))
        throw conflict("SHOP_ALREADY_BOUND");
      return repository.binding(shopId);
    }
    try {
      repository.claimPlatform(platform, shopId);
    } catch (DuplicateKeyException error) {
      throw conflict("PLATFORM_SHOP_ALREADY_BOUND");
    }
    repository.bind(shopId, platform, account);
    mapper.bindConnectorForShop(
        newId(), shopId, "shop.connector_bound", newId(), actor, newId(), nowMillis());
    return repository.binding(shopId);
  }
}
