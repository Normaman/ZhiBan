package com.pddcopilot.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.platform.pdd.PddCredentialVerifier;
import com.pddcopilot.core.platform.pdd.PddLoginBrowserService;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Orchestrates an administrator-approved login in the Spring-owned Playwright browser. */
@Service
public class PlatformLoginService {
  private final Path projectRoot;
  private final Path dataDirectory;
  private final ObjectMapper json;
  private final WorkspaceService workspaces;
  private final ConnectorRegistryService connectors;
  private final PddLoginBrowserService browser;
  private final PddCredentialVerifier verifier;
  private volatile State state = State.idle();
  private String expectedShop;
  private String expectedAccount;
  private String targetShop;
  private String actor;
  private Path activeDataDirectory;
  private boolean registerAfterLogin;
  private String discoveredShop;
  private String discoveredAccount;

  public PlatformLoginService(
      @Value("${user.dir}") String root,
      @Value("${PDD_DATA_DIR:/data}") String dataDirectory,
      ObjectMapper json,
      WorkspaceService workspaces,
      ConnectorRegistryService connectors,
      PddLoginBrowserService browser,
      PddCredentialVerifier verifier) {
    this.projectRoot = Path.of(root).toAbsolutePath().normalize();
    Path configured = Path.of(dataDirectory);
    this.dataDirectory = (configured.isAbsolute() ? configured : projectRoot.resolve(configured)).normalize();
    this.json = json;
    this.workspaces = workspaces;
    this.connectors = connectors;
    this.browser = browser;
    this.verifier = verifier;
  }

  public synchronized Map<String, Object> start(
      String shopId, String mode, String bindingState, String platformShopId,
      String accountId, String actor) {
    if (browser.isRunning() || Set.of("starting", "waiting_login", "checking_identity",
        "confirm_identity", "saving", "verifying_connection").contains(state.name()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_ALREADY_RUNNING");
    if (!"live".equals(mode))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_SHOP_NOT_BOUND");
    if (shopId == null || !shopId.matches("[A-Za-z0-9_-]{1,100}"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_SHOP_NOT_BOUND");
    boolean unbound = "unbound".equals(bindingState);
    expectedShop = unbound ? null : platformShopId;
    expectedAccount = unbound ? null : accountId;
    Path existing = dataDirectory.resolve("shops").resolve(shopId).normalize();
    if (unbound && (Files.exists(existing.resolve("connection.json"))
        || Files.exists(existing.resolve("credentials.enc")))) {
      try {
        Map<String, Object> identity = json.readValue(Files.readString(existing.resolve("connection.json")),
            new TypeReference<>() {});
        expectedShop = String.valueOf(identity.get("platform_shop_id"));
        expectedAccount = String.valueOf(identity.get("account_id"));
        if (expectedShop.isBlank() || expectedAccount.isBlank()
            || "null".equals(expectedShop) || "null".equals(expectedAccount))
          throw new IllegalStateException();
      } catch (Exception error) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_DIRECTORY_MISMATCH");
      }
    }
    if (!unbound && (expectedShop == null || expectedShop.isBlank()
        || expectedAccount == null || expectedAccount.isBlank()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_SHOP_NOT_BOUND");
    targetShop = shopId;
    this.actor = actor;
    activeDataDirectory = selectDataDirectory(shopId, unbound, expectedShop, expectedAccount);
    registerAfterLogin = unbound || !activeDataDirectory.equals(dataDirectory);
    if (expectedShop != null) verifyDirectoryBinding(activeDataDirectory, expectedShop, expectedAccount);
    discoveredShop = null;
    discoveredAccount = null;
    state = new State("starting", null, null, null, null);
    try {
      browser.start(activeDataDirectory, expectedShop, expectedAccount,
          this::browserState, this::browserCompleted, this::browserFailed);
      return view();
    } catch (Exception error) {
      state = new State("failed", null, null, null, "LOGIN_HELPER_START_FAILED");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LOGIN_HELPER_START_FAILED");
    }
  }

  public synchronized Map<String, Object> continueLogin(String shopId) {
    requireScope(shopId);
    requireStage("waiting_login");
    state = new State("checking_identity", null, null, null, null);
    browser.continueLogin();
    return view();
  }

  public synchronized Map<String, Object> decide(boolean accepted, String shopId, String actor) {
    requireScope(shopId);
    requireStage("confirm_identity");
    if (accepted && registerAfterLogin)
      workspaces.validateConnectorBinding(targetShop, discoveredShop, discoveredAccount);
    this.actor = actor;
    state = new State(accepted ? "saving" : "cancelled", null, null, null, null);
    browser.decide(accepted);
    return view();
  }

  public synchronized Map<String, Object> cancel(String shopId) {
    requireScope(shopId);
    browser.cancel();
    state = new State("cancelled", null, null, null, null);
    return view();
  }

  public synchronized void stopForShopRemoval(String shopId) {
    if (!shopId.equals(targetShop)) return;
    browser.cancel();
    state = new State("cancelled", null, null, null, null);
  }

  @PreDestroy
  public synchronized void close() {
    browser.close();
  }

  public synchronized Map<String, Object> view() {
    State current = state;
    return Map.of(
        "state", current.name(),
        "viewer_url", current.name().equals("waiting_login") ? current.viewerUrl() : "",
        "platform_shop_id", current.name().equals("confirm_identity") ? value(discoveredShop) : "",
        "connector_account_id", current.name().equals("confirm_identity") ? value(discoveredAccount) : "",
        "error_code", value(current.errorCode()));
  }

  public synchronized Map<String, Object> view(String shopId) {
    if (targetShop != null && !targetShop.equals(shopId))
      return Map.of("state", "idle", "viewer_url", "", "platform_shop_id", "",
          "connector_account_id", "", "error_code", "");
    return view();
  }

  private synchronized void browserState(String name, String value) {
    if (name.equals("confirm_identity")) {
      String[] identity = value.split("\\n", -1);
      if (identity.length != 2 || identity[0].isBlank() || identity[1].isBlank()) {
        browserFailed("PDD_LOGIN_FAILED");
        return;
      }
      discoveredShop = identity[0];
      discoveredAccount = identity[1];
      state = new State(name, null, discoveredShop, discoveredAccount, null);
    } else {
      state = new State(name, name.equals("waiting_login") ? value : null, null, null, null);
    }
  }

  private void browserCompleted(PddLoginBrowserService.Identity identity) {
    synchronized (this) { state = new State("verifying_connection", null, null, null, null); }
    String verificationError = verifyPlatformSession(identity);
    synchronized (this) {
      if (verificationError != null) {
        state = new State("failed", null, null, null, verificationError);
        return;
      }
      try {
        if (registerAfterLogin) {
          connectors.register(targetShop, activeDataDirectory, discoveredShop, discoveredAccount);
          try {
            workspaces.bindConnectorForShop(targetShop, discoveredShop, discoveredAccount, actor);
          } catch (RuntimeException error) {
            connectors.remove(targetShop);
            throw error;
          }
        }
        state = new State("completed", null, null, null, null);
      } catch (RuntimeException error) {
        String code = error instanceof ResponseStatusException response
            ? response.getReason() : error.getMessage();
        state = new State("failed", null, null, null,
            code == null ? "CONNECTOR_ENROLLMENT_FAILED" : code);
      }
    }
  }

  private String verifyPlatformSession(PddLoginBrowserService.Identity identity) {
    try {
      verifier.verify(activeDataDirectory, identity.shopId(), identity.accountId());
      return null;
    } catch (RuntimeException error) {
      return error instanceof com.pddcopilot.core.platform.pdd.PddPlatformException platform
          ? platform.code() : "PDD_VERIFY_FAILED";
    }
  }

  private void browserFailed(String code) {
    synchronized (this) { state = new State("failed", null, null, null, code); }
  }

  private Path selectDataDirectory(
      String shopId, boolean onboarding, String platformShopId, String accountId) {
    Path child = dataDirectory.resolve("shops").resolve(shopId).normalize();
    if (!child.startsWith(dataDirectory))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_DIRECTORY_MISMATCH");
    if (onboarding) return child;
    for (Path candidate : java.util.List.of(child, dataDirectory)) {
      Path connection = candidate.resolve("connection.json");
      if (!Files.isRegularFile(connection)) continue;
      try {
        Map<String, Object> binding = json.readValue(Files.readString(connection), new TypeReference<>() {});
        if (platformShopId.equals(binding.get("platform_shop_id"))
            && accountId.equals(binding.get("account_id"))) return candidate;
      } catch (Exception ignored) { }
    }
    return child;
  }

  private void verifyDirectoryBinding(Path directory, String shopId, String accountId) {
    try {
      Map<String, Object> binding = json.readValue(
          Files.readString(directory.resolve("connection.json")), new TypeReference<>() {});
      if (!shopId.equals(binding.get("platform_shop_id"))
          || !accountId.equals(binding.get("account_id")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_DIRECTORY_MISMATCH");
    } catch (ResponseStatusException error) {
      throw error;
    } catch (Exception error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_DIRECTORY_MISMATCH");
    }
  }

  private void requireStage(String expected) {
    if (!state.name().equals(expected))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_LOGIN_STATE_CONFLICT");
  }

  private void requireScope(String shopId) {
    if (targetShop == null || !targetShop.equals(shopId))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PDD_LOGIN_SCOPE_MISMATCH");
  }

  private static String value(String value) { return value == null ? "" : value; }

  private record State(String name, String viewerUrl, String shop, String account, String errorCode) {
    static State idle() { return new State("idle", null, null, null, null); }
  }
}
