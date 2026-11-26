package com.pddcopilot.core.service;

import com.pddcopilot.core.domain.UserAccount;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.UserRepository;
import com.pddcopilot.core.support.WorkflowValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
  private final UserRepository users;
  private final BusinessStore store;
  private final PasswordEncoder encoder;
  private final String invitation;
  private final WorkspaceService workspaces;

  public UserService(
      UserRepository users,
      BusinessStore store,
      PasswordEncoder encoder,
      WorkspaceService workspaces,
      @Value("${copilot.registration-invite}") String invitation) {
    this.users = users;
    this.store = store;
    this.encoder = encoder;
    this.invitation = invitation;
    this.workspaces = workspaces;
  }

  @Transactional
  public void ensureAdministrator(String username, String password) {
    username = username.toLowerCase(Locale.ROOT);
    if (users.findByUsername(username).isPresent()) return;
    String id = WorkflowValues.newId();
    users.insert(
        new UserAccount(id, username, encoder.encode(password), store.shopId(), "ADMIN", true),
        WorkflowValues.nowMillis());
    workspaces.ensureMembership(id, workspaces.workspaceId(), "ADMIN");
  }

  @Transactional
  public Map<String, Object> register(String username, String password, String invite) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG");
    username = username.toLowerCase(Locale.ROOT);
    if (users.findByUsername(username).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "USERNAME_EXISTS");
    boolean merchant =
        invitation.length() >= 24
            && MessageDigest.isEqual(
                invitation.getBytes(StandardCharsets.UTF_8),
                invite.getBytes(StandardCharsets.UTF_8));
    String id = WorkflowValues.newId();
    var registration =
        merchant
            ? workspaces.createWorkspace(id, username + " 的工作空间")
            : workspaces.consumeInvitation(invite);
    String shop = registration.shopId(), workspace = registration.workspaceId();
    var account =
        new UserAccount(
            id, username, encoder.encode(password), shop, merchant ? "ADMIN" : "REVIEWER", true);
    try {
      users.insert(account, WorkflowValues.nowMillis());
    } catch (DuplicateKeyException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "USERNAME_EXISTS");
    }
    workspaces.ensureMembership(id, workspace, merchant ? "OWNER" : "REVIEWER");
    try (var scope = com.pddcopilot.core.support.ShopScope.open(shop)) {
      store.recordAudit("user.registered", account.id(), username, account.id());
    }
    return Map.of("username", username, "shop_id", account.shopId());
  }
}
