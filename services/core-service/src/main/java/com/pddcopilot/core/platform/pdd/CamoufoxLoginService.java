package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.support.ShopScope;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Human QR authorization owns a fresh gateway instance and a dedicated Playwright thread. */
@Service
public class CamoufoxLoginService {
  private static final Logger log = LoggerFactory.getLogger(CamoufoxLoginService.class);
  private static final int LOGIN_VIEWPORT_WIDTH = 1920;
  private static final int LOGIN_VIEWPORT_HEIGHT = 1080;
  private final Map<String, Login> logins = new ConcurrentHashMap<>();
  private final CamoufoxGatewayClient gateway;
  private final CamoufoxLoginStateStore states;
  private final CamoufoxAuthentication authentication;
  private final ConnectionRepository shops;
  private final BusinessStore store;
  private final ObjectMapper json;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  public CamoufoxLoginService(CamoufoxGatewayClient gateway, CamoufoxLoginStateStore states,
      CamoufoxAuthentication authentication, ConnectionRepository shops, BusinessStore store, ObjectMapper json,
      org.springframework.transaction.PlatformTransactionManager transactions) {
    this.gateway = gateway; this.states = states; this.authentication = authentication;
    this.shops = shops; this.store = store; this.json = json;
    this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
  }

  private final class Login {
    final String shop, platformShop, account, actor;
    final String sessionId = UUID.randomUUID().toString();
    final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "camoufox-authorization"); thread.setDaemon(true); return thread;
    });
    volatile String state = "starting", error = "";
    volatile long deadline;
    volatile boolean cancelled, closed;
    CamoufoxGatewayClient.Instance instance;
    Playwright playwright;
    Browser browser;
    BrowserContext context;
    Page page;
    Login(String shop, String platform, String account, String actor) {
      this.shop = shop; this.platformShop = platform; this.account = account; this.actor = actor;
    }
    String instanceId() { return instance == null ? "pending" : instance.instanceId(); }
  }

  public synchronized Map<String, Object> start(String shop, String actor) {
    if (!gateway.configured()) throw new PddPlatformException("CAMOUFOX_GATEWAY_NOT_CONFIGURED");
    var binding = boundShop(shop);
    Login previous = logins.get(shop);
    if (previous != null && !previous.closed)
      throw new PddPlatformException("CAMOUFOX_LOGIN_ALREADY_RUNNING");
    if (logins.values().stream().filter(login -> !login.closed).count() >= 2)
      throw new PddPlatformException("CAMOUFOX_LOGIN_BUSY");
    Login login = new Login(shop, String.valueOf(binding.get("platform_shop_id")),
        String.valueOf(binding.get("connector_account_id")), actor);
    logins.put(shop, login);
    login.worker.submit(() -> open(login));
    return view(shop);
  }

  public Map<String, Object> view(String shop) {
    Login login = logins.get(shop);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("state", login == null ? "idle" : login.state);
    result.put("configured", gateway.configured());
    result.put("error_code", login == null ? "" : login.error);
    result.put("session_id", login == null ? "" : login.sessionId);
    boolean verified = login != null && java.util.Set.of("confirm_identity", "completed").contains(login.state);
    result.put("platform_shop_id", verified ? login.platformShop : "");
    result.put("connector_account_id", verified ? login.account : "");
    result.put("saved", states.exists(shop));
    result.put("expires_in", login == null || login.deadline == 0 ? 0
        : Math.max(0, TimeUnit.NANOSECONDS.toSeconds(login.deadline - System.nanoTime())));
    return result;
  }

  public Map<String, Object> frame(String shop, String sessionId) {
    Login login = required(shop, sessionId);
    return call(login, () -> {
      check(login);
      if (!"waiting_login".equals(login.state) || !authentication.interactiveLoginPage(login.page))
        throw new PddPlatformException("CAMOUFOX_LOGIN_FRAME_UNAVAILABLE");
      byte[] image = login.page.screenshot(new Page.ScreenshotOptions()
          .setType(ScreenshotType.JPEG).setQuality(75).setTimeout(3000));
      return Map.of("image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image));
    });
  }

  public Map<String, Object> click(String shop, String sessionId, double x, double y) {
    Login login = required(shop, sessionId);
    call(login, () -> {
      check(login);
      // Only interact with the login page; never expose controls over a dashboard.
      if (!"waiting_login".equals(login.state) || !authentication.interactiveLoginPage(login.page))
        throw new PddPlatformException("CAMOUFOX_LOGIN_FRAME_UNAVAILABLE");
      var viewport = login.page.viewportSize();
      if (viewport == null) throw new PddPlatformException("CAMOUFOX_LOGIN_FRAME_UNAVAILABLE");
      login.page.mouse().click(x * viewport.width, y * viewport.height);
      return null;
    });
    return view(shop);
  }

  public Map<String, Object> verify(String shop, String sessionId) {
    Login login = required(shop, sessionId);
    call(login, () -> {
      if (!"waiting_login".equals(login.state)) throw new PddPlatformException("CAMOUFOX_LOGIN_STAGE_INVALID");
      identify(login);
      return null;
    });
    return view(shop);
  }

  public Map<String, Object> confirm(String shop, String sessionId, String confirmActor) {
    Login login = required(shop, sessionId);
    call(login, () -> {
      check(login);
      if (!"confirm_identity".equals(login.state)) throw new PddPlatformException("CAMOUFOX_LOGIN_STAGE_INVALID");
      try {
        requireSameBinding(login);
        authentication.verify(login.page, login.platformShop, login.account, login.instanceId(), remaining(login));
        check(login);
        String storageState = login.context.storageState();
        transaction.executeWithoutResult(status -> {
          states.save(shop, login.platformShop, login.account, storageState);
          try (var ignored = ShopScope.open(shop)) {
            store.recordAudit("camoufox_login_saved", shop, confirmActor, login.sessionId);
          }
        });
        login.state = "completed";
        event(login, "saved");
        dispose(login);
      } catch (RuntimeException error) { fail(login, error); }
      return null;
    });
    return view(shop);
  }

  public Map<String, Object> cancel(String shop, String sessionId) {
    Login login = required(shop, sessionId);
    login.cancelled = true;
    login.state = "cancelling";
    login.worker.submit(() -> { login.state = "cancelled"; dispose(login); });
    return view(shop);
  }

  public void stopForShopRemoval(String shop) {
    Login login = logins.get(shop);
    if (login == null || login.closed) return;
    login.cancelled = true;
    login.state = "cancelling";
    try { login.worker.submit(() -> { login.state = "cancelled"; dispose(login); }); }
    catch (RejectedExecutionException ignored) { }
  }

  private void open(Login login) {
    try {
      event(login, "starting");
      try (var ignored = ShopScope.open(login.shop)) {
        store.recordAudit("camoufox_login_started", login.shop, login.actor, login.sessionId);
      }
      login.instance = gateway.fetch("authorization-" + login.sessionId);
      login.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(login.instance.ttlSeconds() - 10);
      if (login.cancelled) { login.state = "cancelled"; dispose(login); return; }
      login.playwright = Playwright.create();
      login.browser = login.playwright.firefox().connect(login.instance.wsEndpoint(),
          new BrowserType.ConnectOptions().setTimeout(Math.min(15_000, remaining(login))));
      if (!login.browser.contexts().isEmpty()) throw new PddPlatformException("CAMOUFOX_LOGIN_CONTEXT_NOT_EMPTY");
      // Independent first authorization: absolutely no storage state or Cookies imported.
      login.context = login.browser.newContext(new Browser.NewContextOptions()
          .setViewportSize(LOGIN_VIEWPORT_WIDTH, LOGIN_VIEWPORT_HEIGHT)
          .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
      login.page = login.context.newPage();
      login.page.setDefaultTimeout(5000);
      login.page.onResponse(response -> {
        if (login.closed) return;
        try {
          if (!response.url().startsWith(PddWebClient.BASE_URL + "/")
              || !response.headers().getOrDefault("content-type", "").contains("json")) return;
          var body = json.readTree(response.body());
          if (PddOrderResponseCodes.rejected(body)) login.error = PddOrderResponseCodes.REJECTED;
          else if (CamoufoxAuthentication.challengeResponse(body)) login.error = "CAMOUFOX_LOGIN_CHALLENGE_REQUIRED";
        } catch (Exception ignored) { }
      });
      Response response = login.page.navigate(PddWebClient.BASE_URL + "/login",
          new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(Math.min(35_000, remaining(login))));
      if (response != null && response.status() >= 400)
        throw new PddPlatformException("CAMOUFOX_LOGIN_HTTP_" + response.status());
      check(login);
      login.state = "waiting_login";
      event(login, "waiting_qr_scan");
      login.worker.scheduleWithFixedDelay(() -> tick(login), 200, 500, TimeUnit.MILLISECONDS);
    } catch (RuntimeException error) { fail(login, error); }
  }

  private void tick(Login login) {
    if (login.closed) return;
    try {
      check(login);
      login.page.waitForTimeout(10);
      if (authentication.challenge(login.page)) throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
      if (authentication.loginPage(login.page) && !authentication.interactiveLoginPage(login.page))
        throw new PddPlatformException("CAMOUFOX_LOGIN_PAGE_UNSUPPORTED");
      if ("waiting_login".equals(login.state) && !authentication.loginPage(login.page)) identify(login);
    } catch (RuntimeException error) { fail(login, error); }
  }

  private void identify(Login login) {
    try {
      check(login);
      login.state = "checking_identity";
      authentication.verify(login.page, login.platformShop, login.account, login.instanceId(), remaining(login));
      check(login);
      login.state = "confirm_identity";
      event(login, "confirm_identity");
    } catch (RuntimeException error) { fail(login, error); }
  }

  private void check(Login login) {
    if (login.cancelled) throw new PddPlatformException("CAMOUFOX_LOGIN_CANCELLED");
    if (login.closed) throw new PddPlatformException("CAMOUFOX_LOGIN_CLOSED");
    if (!login.error.isBlank()) throw new PddPlatformException(login.error);
    remaining(login);
  }

  private double remaining(Login login) {
    long millis = TimeUnit.NANOSECONDS.toMillis(login.deadline - System.nanoTime());
    if (millis <= 0) throw new PddPlatformException("CAMOUFOX_LOGIN_TIMEOUT");
    return millis;
  }

  private void fail(Login login, RuntimeException error) {
    login.state = login.cancelled ? "cancelled" : "failed";
    login.error = error instanceof PddPlatformException p ? p.code()
        : error instanceof TimeoutError ? "CAMOUFOX_LOGIN_TIMEOUT" : "CAMOUFOX_LOGIN_FAILED";
    event(login, login.error);
    dispose(login);
  }

  private void dispose(Login login) {
    if (login.closed) return;
    login.closed = true;
    try {
      if (login.instance != null) gateway.release(login.instance.instanceId());
    } catch (RuntimeException error) {
      login.state = "failed";
      login.error = "CAMOUFOX_LOGIN_RELEASE_FAILED";
      event(login, login.error);
    } finally {
      close(login.page); close(login.context); close(login.browser); close(login.playwright);
      login.worker.shutdown();
    }
  }

  private void close(AutoCloseable resource) {
    try { if (resource != null) resource.close(); } catch (Exception ignored) { }
  }

  private Login required(String shop, String sessionId) {
    Login login = logins.get(shop);
    if (login == null || !login.sessionId.equals(sessionId) || login.closed)
      throw new PddPlatformException("CAMOUFOX_LOGIN_SESSION_INVALID");
    return login;
  }

  private Map<String, Object> boundShop(String shop) {
    var binding = shops.findShop(shop).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    if (!"bound".equals(binding.get("binding_state")) || !"live".equals(binding.get("mode")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    return binding;
  }

  private void requireSameBinding(Login login) {
    var binding = boundShop(login.shop);
    if (!login.platformShop.equals(binding.get("platform_shop_id"))
        || !login.account.equals(binding.get("connector_account_id")))
      throw new PddPlatformException("CAMOUFOX_SHOP_IDENTITY_MISMATCH");
  }

  private <T> T call(Login login, Callable<T> task) {
    Future<T> future;
    try {
      future = login.worker.submit(() -> {
        try { return task.call(); }
        catch (PlaywrightException error) {
          fail(login, error);
          throw new PddPlatformException("CAMOUFOX_LOGIN_PAGE_FAILED");
        } catch (PddPlatformException error) {
          if (java.util.Set.of("CAMOUFOX_LOGIN_TIMEOUT", "CAMOUFOX_LOGIN_CANCELLED",
              "CAMOUFOX_LOGIN_CHALLENGE_REQUIRED", "CAMOUFOX_LOGIN_EXPIRED").contains(error.code()))
            fail(login, error);
          throw error;
        }
      });
    } catch (RejectedExecutionException error) { throw new PddPlatformException("CAMOUFOX_LOGIN_BUSY"); }
    try { return future.get(30, TimeUnit.SECONDS); }
    catch (InterruptedException error) {
      Thread.currentThread().interrupt(); throw new PddPlatformException("CAMOUFOX_LOGIN_INTERRUPTED");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof PddPlatformException p) throw p;
      throw new PddPlatformException("CAMOUFOX_LOGIN_FAILED");
    } catch (TimeoutException error) {
      future.cancel(false);
      if (!login.closed) {
        login.cancelled = true;
        try { login.worker.submit(() -> fail(login, new PddPlatformException("CAMOUFOX_LOGIN_TIMEOUT"))); }
        catch (RejectedExecutionException ignored) { }
      }
      throw new PddPlatformException("CAMOUFOX_LOGIN_BUSY");
    }
  }

  private void event(Login login, String state) {
    log.info("pdd_order_login channel=camoufox instanceId={} state={}", login.instanceId(), state);
  }

  @PreDestroy public void close() {
    for (Login login : logins.values()) {
      if (login.closed) continue;
      login.cancelled = true;
      try { login.worker.submit(() -> dispose(login)).get(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS); }
      catch (Exception ignored) { login.worker.shutdown(); }
    }
  }
}
