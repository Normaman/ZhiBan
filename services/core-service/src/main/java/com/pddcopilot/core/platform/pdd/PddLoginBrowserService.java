package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Browser-based manual login, fully owned by the Spring service. */
@Service
public class PddLoginBrowserService {
  public record Identity(String shopId, String accountId) {}
  private final ObjectMapper json;
  private final PddCredentialStore credentials;
  private final PddLoginDesktopService desktops;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "pdd-java-login");
    thread.setDaemon(true);
    return thread;
  });
  private volatile PddLoginDesktopService.Desktop desktop;
  private volatile BrowserContext context;
  private volatile Playwright playwright;
  private volatile boolean continueRequested;
  private volatile Boolean accepted;
  private volatile boolean cancelled;
  private volatile String expectedShop;
  private volatile String expectedAccount;
  private volatile Path dataDirectory;
  private volatile String profileName;
  private volatile BiConsumer<String, String> state;
  private volatile Consumer<Identity> completed;
  private volatile Consumer<String> failed;

  public PddLoginBrowserService(
      ObjectMapper json,
      PddCredentialStore credentials,
      PddLoginDesktopService desktops) {
    this.json = json;
    this.credentials = credentials;
    this.desktops = desktops;
  }

  public synchronized void start(
      Path dataDirectory, String expectedShop, String expectedAccount,
      BiConsumer<String, String> state, Consumer<Identity> completed, Consumer<String> failed) {
    if (desktop != null || context != null) throw new IllegalStateException("PDD_LOGIN_ALREADY_RUNNING");
    this.dataDirectory = dataDirectory;
    this.expectedShop = expectedShop;
    this.expectedAccount = expectedAccount;
    this.state = state;
    this.completed = completed;
    this.failed = failed;
    this.continueRequested = false;
    this.accepted = null;
    this.cancelled = false;
    this.profileName = "login-" + UUID.randomUUID().toString().replace("-", "");
    worker.submit(this::run);
  }

  public synchronized void continueLogin() {
    continueRequested = true;
    notifyAll();
  }

  public synchronized void decide(boolean accept) {
    accepted = accept;
    notifyAll();
  }

  public synchronized void cancel() {
    cancelled = true;
    notifyAll();
    stopDesktop();
  }

  public synchronized boolean isRunning() {
    return desktop != null || context != null;
  }

  public synchronized void close() {
    cancelled = true;
    notifyAll();
    closeBrowser();
    stopDesktop();
    worker.shutdownNow();
  }

  private void run() {
    String errorCode = "PDD_LOGIN_FAILED";
    Path profile = null;
    boolean keepProfile = false;
    try {
      profile = dataDirectory.resolve("browser_profiles").resolve(profileName);
      Files.createDirectories(profile.getParent());
      setPrivate(profile.getParent());
      Files.createDirectories(profile);
      setPrivate(profile);
      desktop = desktops.start(profile, 6080);
      if (cancelled) return;
      String password = desktop.password();

      playwright = Playwright.create();
      context = playwright.chromium().launchPersistentContext(profile,
          new com.microsoft.playwright.BrowserType.LaunchPersistentContextOptions()
              .setHeadless(false)
              .setEnv(desktop.environment())
              .setArgs(java.util.List.of("--disable-notifications", "--disable-dev-shm-usage")));
      var page = context.newPage();
      page.navigate(PddWebClient.BASE_URL + "/login",
          new com.microsoft.playwright.Page.NavigateOptions().setTimeout(60_000));
      state.accept("waiting_login", "/login-desktop/vnc.html#password=" + password
          + "&autoconnect=true&resize=scale");
      synchronized (this) {
        while (!continueRequested && !cancelled) wait();
      }
      if (cancelled) return;
      state.accept("checking_identity", "");
      Map<String, String> cookies = new LinkedHashMap<>();
      for (var cookie : context.cookies())
        if (cookie.name != null && cookie.value != null) cookies.put(cookie.name, cookie.value);
      if (cookies.isEmpty()) throw new IllegalStateException("PDD_LOGIN_FAILED");
      PddWebClient.Identity identity = new PddWebClient(json, cookies).identity();
      if ((expectedShop != null && !expectedShop.equals(identity.platformShopId()))
          || (expectedAccount != null && !expectedAccount.equals(identity.accountId())))
        throw new IllegalStateException("LOGIN_ACCOUNT_MISMATCH");
      state.accept("confirm_identity", identity.platformShopId() + "\n" + identity.accountId());
      synchronized (this) {
        while (accepted == null && !cancelled) wait();
      }
      if (cancelled || !Boolean.TRUE.equals(accepted)) return;
      credentials.saveLogin(dataDirectory, identity.platformShopId(), identity.accountId(),
          profileName, cookies);
      keepProfile = true;
      completed.accept(new Identity(identity.platformShopId(), identity.accountId()));
      return;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return;
    } catch (Exception error) {
      if (error instanceof IllegalStateException stateError
          && stateError.getMessage() != null
          && stateError.getMessage().matches("[A-Z0-9_]+")) errorCode = stateError.getMessage();
      failed.accept(errorCode);
    } finally {
      closeBrowser();
      stopDesktop();
      if (!keepProfile && profile != null) {
        try (var paths = Files.walk(profile)) {
          paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
          });
        } catch (Exception ignored) { }
      }
    }
  }

  private synchronized void closeBrowser() {
    try { if (context != null) context.close(); } catch (Exception ignored) { }
    context = null;
    try { if (playwright != null) playwright.close(); } catch (Exception ignored) { }
    playwright = null;
  }

  private synchronized void stopDesktop() {
    PddLoginDesktopService.Desktop current = desktop;
    if (current == null) return;
    desktops.close(current);
    desktop = null;
  }

  private static void setPrivate(Path path) throws Exception {
    try {
      Files.setPosixFilePermissions(path,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
    }
  }
}
