package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Owns order browser contexts on one Playwright thread, including human verification. */
@Service
public class PddOrderBrowserService {
  private static final Logger log = LoggerFactory.getLogger(PddOrderBrowserService.class);
  private final ObjectMapper json;
  private final PddCredentialStore credentials;
  private final PddOrderParser parser;
  private final PddLoginDesktopService desktops;
  private final java.util.function.Supplier<Playwright> browserFactory;
  private final java.util.function.Function<Map<String, String>, PddWebClient> clientFactory;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "pdd-order-browser");
    thread.setDaemon(true);
    return thread;
  });
  private final LinkedHashMap<Path, Session> sessions = new LinkedHashMap<>(4, 0.75f, true);

  @org.springframework.beans.factory.annotation.Autowired
  public PddOrderBrowserService(ObjectMapper json, PddCredentialStore credentials,
      PddOrderParser parser, PddLoginDesktopService desktops) {
    this(json, credentials, parser, desktops, Playwright::create, cookies -> new PddWebClient(json, cookies));
  }

  PddOrderBrowserService(ObjectMapper json, PddCredentialStore credentials,
      PddOrderParser parser, PddLoginDesktopService desktops,
      java.util.function.Supplier<Playwright> browserFactory,
      java.util.function.Function<Map<String, String>, PddWebClient> clientFactory) {
    this.json = json;
    this.credentials = credentials;
    this.parser = parser;
    this.desktops = desktops;
    this.browserFactory = browserFactory;
    this.clientFactory = clientFactory;
    worker.scheduleWithFixedDelay(this::pump, 500, 500, TimeUnit.MILLISECONDS);
  }

  private final class Session implements AutoCloseable {
    Path directory;
    String shop, account, digest, orderId;
    Playwright playwright;
    BrowserContext context;
    Page page;
    PddLoginDesktopService.Desktop display;
    Map<String, Object> basic, latest;
    Response latestListResponse;
    int latestListOrderType = -1;
    int latestListPage = -1;
    long lastUsed = System.currentTimeMillis();
    long latestAt;
    boolean querying;
    boolean verificationCompleted;
    boolean rejected;
    boolean closed;

    public void close() {
      if (closed) return;
      closed = true;
      try { if (context != null) context.close(); } catch (Exception ignored) { }
      try { if (playwright != null) playwright.close(); } catch (Exception ignored) { }
      desktops.close(display);
      log.info("pdd_order_browser_closed channel=original");
    }
  }

  public Map<String, Object> read(Path directory, String shop, String account, String orderId) {
    return submit(() -> readOnWorker(directory, shop, account, orderId));
  }

  public Map<String, Object> list(Path directory, String shop, String account,
      int orderType, int pageNumber) {
    return submit(() -> listOnWorker(directory, shop, account, orderType, pageNumber));
  }

  public Map<String, Object> openListBrowser(Path directory, String shop, String account) {
    return submit(() -> openListBrowserOnWorker(directory, shop, account));
  }

  public Map<String, Object> verification(Path directory, String shop, String account, String orderId) {
    return submit(() -> {
      Session session = sessions.get(directory.toAbsolutePath().normalize());
      if (session == null || !orderId.equals(session.orderId) || session.page.isClosed()
          || !session.digest.equals(PddBrowserSessionService.credentialDigest(directory))) {
        try { readOnWorker(directory, shop, account, orderId); }
        catch (PddPlatformException error) {
          if (!"ORDER_QUERY_VERIFICATION_REQUIRED".equals(error.code())) throw error;
        }
        session = sessions.get(directory.toAbsolutePath().normalize());
      }
      if (session == null || !session.shop.equals(shop) || !session.account.equals(account))
        throw new PddPlatformException("ORDER_SESSION_INVALID");
      session.display = desktops.exposeViewer(session.display, 6080);
      session.lastUsed = System.currentTimeMillis();
      return Map.of("viewer_url", "/login-desktop/vnc.html#password=" + session.display.password()
          + "&autoconnect=true&resize=scale");
    });
  }

  public void hideVerification(Path directory) {
    submit(() -> {
      Session session = sessions.get(directory.toAbsolutePath().normalize());
      if (session != null) session.display = desktops.hideViewer(session.display);
      return null;
    });
  }

  public boolean consumeVerification(Path directory, String orderId) {
    return submit(() -> {
      Session session = sessions.get(directory.toAbsolutePath().normalize());
      if (session == null || !orderId.equals(session.orderId) || !session.verificationCompleted) return false;
      session.verificationCompleted = false;
      return true;
    });
  }

  private Map<String, Object> readOnWorker(Path directory, String shop, String account, String orderId) {
    Path key = directory.toAbsolutePath().normalize();
    Map<String, String> loginCookies;
    try { loginCookies = credentials.load(directory, shop, account); }
    catch (IllegalStateException unavailable) {
      throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    }
    var client = clientFactory.apply(loginCookies);
    PddBrowserSessionService.requireIdentity(client, shop, account);
    var basicResponse = client.postJson("/latitude/order/orderSimpleDetail", Map.of("orderSn", orderId), Map.of());
    String buyer = basicResponse.path("result").path("uid").asText("");
    if (buyer.isBlank()) throw new PddPlatformException("ORDER_QUERY_UNAVAILABLE");
    Map<String, Object> basic = parser.basic(basicResponse, orderId, buyer);
    String digest = PddBrowserSessionService.credentialDigest(directory);
    Session session = sessions.get(key);
    if (session != null && (!digest.equals(session.digest) || session.page.isClosed())) {
      sessions.remove(key);
      session.close();
      session = null;
    }
    if (session == null) {
      if (sessions.size() >= 2) {
        Path oldest = sessions.keySet().iterator().next();
        sessions.remove(oldest).close();
      }
      session = create(directory, shop, account, digest, loginCookies);
      sessions.put(key, session);
    }
    session.lastUsed = System.currentTimeMillis();
    if (orderId.equals(session.orderId) && session.latest != null
        && session.latestAt > session.lastUsed - 30_000) {
      var fresh = session.latest;
      session.latest = null;
      return fresh;
    }
    session.orderId = orderId;
    session.basic = basic;
    Session active = session;
    active.querying = true;
    try {
      String target = PddWebClient.BASE_URL + "/orders/detail?sn="
          + java.net.URLEncoder.encode(orderId, java.nio.charset.StandardCharsets.UTF_8);
      Response response = active.page.waitForResponse(
          r -> PddBrowserSessionService.ORDER_PATHS.stream().anyMatch(
              path -> PddBrowserSessionService.isEndpoint(r.url(), "mms.pinduoduo.com", path)),
          () -> active.page.navigate(target, new Page.NavigateOptions()
              .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(35_000)));
      return capture(active, response);
    } catch (PddPlatformException error) {
      if (active.rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      throw error;
    } catch (Exception error) {
      if (active.rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      if (URI.create(active.page.url()).getPath().contains("login"))
        throw new PddPlatformException("ORDER_SESSION_EXPIRED");
      log.warn("pdd_order_browser_failed error_type={}", error.getClass().getSimpleName());
      throw new PddPlatformException("ORDER_SESSION_CAPTURE_FAILED");
    } finally {
      active.querying = false;
    }
  }

  private Map<String, Object> listOnWorker(Path directory, String shop, String account,
      int orderType, int pageNumber) {
    Map<String, String> cookies;
    try { cookies = credentials.load(directory, shop, account); }
    catch (IllegalStateException unavailable) {
      throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    }
    PddBrowserSessionService.requireIdentity(clientFactory.apply(cookies), shop, account);
    Path key = directory.toAbsolutePath().normalize();
    String digest = PddBrowserSessionService.credentialDigest(directory);
    Session session = sessions.get(key);
    if (session != null && (!digest.equals(session.digest) || session.page.isClosed())) {
      sessions.remove(key);
      session.close();
      session = null;
    }
    if (session == null) {
      if (sessions.size() >= 2) {
        Path oldest = sessions.keySet().iterator().next();
        sessions.remove(oldest).close();
      }
      session = create(directory, shop, account, digest, cookies);
      sessions.put(key, session);
    }

    String label = switch (orderType) {
      case 0 -> null;
      case 1 -> "待发货";
      // Captured merchant-page route type=1 emits API orderType=2.
      case 2 -> null;
      case 7 -> "待付款";
      default -> throw new PddPlatformException("ORDER_LIST_FILTER_INVALID");
    };
    Session active = session;
    active.lastUsed = System.currentTimeMillis();
    active.querying = true;
    active.latestListResponse = null;
    active.latestListOrderType = -1;
    active.latestListPage = -1;
    String[] stage = {"navigate_order_list"};
    try {
      String filterLabel = label;
      String pageRoute = orderType == 2
          ? "/orders/list?msfrom=mms_sidenav&type=1"
          : "/orders/list?msfrom=mms_sidenav";
      active.page.navigate(PddWebClient.BASE_URL + pageRoute,
          new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(35_000));
      if (filterLabel != null) {
        stage[0] = "find_status_filter";
        var filter = active.page.getByText(filterLabel, new Page.GetByTextOptions().setExact(true));
        int matches = filter.count();
        log.info("pdd_order_list_filter_lookup order_type={} matches={}", orderType, matches);
        stage[0] = "click_status_filter";
        filter.last().click(new Locator.ClickOptions().setTimeout(12_000));
      }
      for (int current = 1; current < pageNumber; current++) {
        stage[0] = "click_next_page";
        active.page.getByText("下一页", new Page.GetByTextOptions().setExact(true)).last()
            .click(new Locator.ClickOptions().setTimeout(12_000));
      }
      stage[0] = "wait_for_list_response";
      long deadline = System.currentTimeMillis() + 30_000;
      while ((active.latestListResponse == null || active.latestListOrderType != orderType
          || active.latestListPage != pageNumber) && !active.rejected && System.currentTimeMillis() < deadline)
        active.page.waitForTimeout(200);
      if (active.rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      if (active.latestListResponse == null || active.latestListOrderType != orderType
          || active.latestListPage != pageNumber)
        throw new PddPlatformException("ORDER_LIST_RESPONSE_TIMEOUT");
      Response response = active.latestListResponse;
      Map<String, Object> result = new LinkedHashMap<>(parser.list(json.readTree(response.body())));
      result.put("order_type", orderType);
      result.put("page", pageNumber);
      result.put("page_size", 20);
      result.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
      active.lastUsed = System.currentTimeMillis();
      return result;
    } catch (PddPlatformException error) {
      if (active.rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      throw error;
    } catch (Exception error) {
      if (active.rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      if (URI.create(active.page.url()).getPath().contains("login"))
        throw new PddPlatformException("ORDER_SESSION_EXPIRED");
      String pagePath;
      try { pagePath = URI.create(active.page.url()).getPath(); }
      catch (Exception ignored) { pagePath = "unknown"; }
      log.warn("pdd_order_list_capture_failed stage={} page_path={} error_type={}",
          stage[0], pagePath, error.getClass().getSimpleName());
      throw new PddPlatformException("ORDER_LIST_CAPTURE_FAILED");
    } finally {
      active.querying = false;
    }
  }

  private Map<String, Object> openListBrowserOnWorker(Path directory, String shop, String account) {
    Map<String, String> cookies;
    try { cookies = credentials.load(directory, shop, account); }
    catch (IllegalStateException unavailable) {
      throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    }
    PddBrowserSessionService.requireIdentity(clientFactory.apply(cookies), shop, account);
    Path key = directory.toAbsolutePath().normalize();
    String digest = PddBrowserSessionService.credentialDigest(directory);
    Session session = sessions.get(key);
    if (session != null && (!digest.equals(session.digest) || session.page.isClosed())) {
      sessions.remove(key);
      session.close();
      session = null;
    }
    if (session == null) {
      if (sessions.size() >= 2) {
        Path oldest = sessions.keySet().iterator().next();
        sessions.remove(oldest).close();
      }
      session = create(directory, shop, account, digest, cookies);
      sessions.put(key, session);
    }
    session.lastUsed = System.currentTimeMillis();
    String stage = "navigate_order_list";
    try {
      session.page.navigate(PddWebClient.BASE_URL + "/orders/list?msfrom=mms_sidenav&type=1",
          new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
              .setTimeout(35_000));
      stage = "wait_for_order_page";
      session.page.waitForTimeout(1_500);
      stage = "expose_viewer";
      session.display = desktops.exposeViewer(session.display, 6080);
      return Map.of("viewer_url", "/login-desktop/vnc.html#password="
          + session.display.password() + "&autoconnect=true&resize=scale");
    } catch (PddPlatformException error) {
      throw error;
    } catch (Exception error) {
      String pagePath;
      try { pagePath = URI.create(session.page.url()).getPath(); }
      catch (Exception ignored) { pagePath = "unknown"; }
      log.warn("pdd_order_list_browser_open_failed stage={} page_path={} error_type={}",
          stage, pagePath, error.getClass().getSimpleName());
      throw new PddPlatformException("ORDER_LIST_BROWSER_UNAVAILABLE");
    }
  }

  private Session create(Path directory, String shop, String account, String digest,
      Map<String, String> loginCookies) {
    Session session = new Session();
    session.directory = directory;
    session.shop = shop;
    session.account = account;
    session.digest = digest;
    String stage = "resolve_profile";
    try {
      Path profile = PddBrowserSessionService.loginProfile(directory, json);
      stage = "start_display";
      session.display = desktops.startDisplay(profile);
      stage = "start_playwright";
      session.playwright = browserFactory.get();
      stage = "launch_chromium";
      session.context = session.playwright.chromium().launchPersistentContext(profile,
          new BrowserType.LaunchPersistentContextOptions().setHeadless(false)
              .setEnv(session.display.environment()).setArgs(List.of("--disable-notifications", "--disable-dev-shm-usage")));
      Map<String, String> restored = new LinkedHashMap<>(loginCookies);
      try {
        @SuppressWarnings("unchecked")
        var saved = (Map<String, String>) credentials.loadOrderSession(directory, shop, account).get("cookies");
        restored.putAll(saved);
      } catch (IllegalStateException unavailable) { }
      session.context.addCookies(PddBrowserSessionService.cookiesToRestore(
          restored, session.context.cookies(PddWebClient.BASE_URL), true));
      session.context.route("**/*", route -> {
        if (PddBrowserSessionService.allowOrderRequest(route.request().method(), route.request().url()))
          route.resume();
        else route.abort();
      });
      session.context.routeWebSocket("**/*", WebSocketRoute::close);
      session.page = session.context.newPage();
      session.page.onResponse(response -> {
        if (PddOrderResponseCodes.orderEndpoint(response.url())) {
          try {
            var body = json.readTree(response.body());
            log.info("pdd_order_response channel=original http={} codes={}",
                response.status(), PddOrderResponseCodes.summary(body));
            if (PddOrderResponseCodes.rejected(body)) {
              reject(session);
              return;
            }
          } catch (Exception unavailable) {
            log.debug("pdd_order_response_unavailable channel=original http={}", response.status());
          }
        }
        if (session.rejected) return;
        if (PddBrowserSessionService.isEndpoint(response.url(), "mms.pinduoduo.com",
            "/mangkhut/mms/recentOrderList")) {
          try {
            var request = json.readTree(response.request().postData());
            var body = json.readTree(response.body());
            int responseOrderType = request.path("orderType").asInt(-1);
            int responsePage = request.path("pageNumber").asInt(-1);
            int responsePageSize = request.path("pageSize").asInt(-1);
            var headers = response.request().allHeaders();
            String userAgent = headers.getOrDefault("user-agent", "");
            String browserOs = userAgent.contains("Windows") ? "windows"
                : userAgent.contains("Linux") ? "linux" : "other";
            String refererPath;
            String refererQuery;
            try {
              URI referer = URI.create(headers.getOrDefault("referer", ""));
              refererPath = referer.getPath();
              refererQuery = referer.getQuery() != null
                  && referer.getQuery().contains("msfrom=mms_sidenav") ? "sidebar" : "other_or_missing";
            } catch (Exception ignored) {
              refererPath = "missing";
              refererQuery = "other_or_missing";
            }
            String errorMessage = body.path("errorMsg").asText(body.path("error_msg").asText(""));
            String errorCategory = classifyOrderListError(errorMessage);
            String clientPlatform = headers.getOrDefault("sec-ch-ua-platform", "unknown");
            List<String> requestFields = new ArrayList<>();
            request.fieldNames().forEachRemaining(requestFields::add);
            if (responsePageSize >= 20) {
              session.latestListResponse = response;
              session.latestListOrderType = responseOrderType;
              session.latestListPage = responsePage;
            }
            log.info("pdd_order_list_browser_response order_type={} after_sale_type={} page={} page_size={} "
                    + "sort_type={} remark_status={} urge_status={} time_range_seconds={} "
                    + "has_consolidate_types={} hide_region_black_delay_shipping={} anti_content_present={} "
                    + "browser_os={} client_platform={} referer_path={} referer_source={} "
                    + "request_fields={} http={} success={} error_code={} error_category={} total={}",
                responseOrderType, request.path("afterSaleType").asInt(-1),
                responsePage, responsePageSize,
                request.path("sortType").asInt(-1), request.path("remarkStatus").asInt(-1),
                request.path("urgeShippingStatus").asInt(-1),
                request.path("groupEndTime").asLong(0) - request.path("groupStartTime").asLong(0),
                request.path("consolidateTypeList").isArray(),
                request.has("hideRegionBlackDelayShipping")
                    ? request.path("hideRegionBlackDelayShipping").asText() : "missing",
                !headers.getOrDefault("anti-content", "").isBlank(), browserOs, clientPlatform,
                refererPath, refererQuery, requestFields,
                response.status(), body.path("success").asBoolean(false),
                body.path("errorCode").asInt(body.path("error_code").asInt(-1)),
                errorCategory,
                body.path("result").path("totalItemNum").asInt(-1));
          } catch (Exception error) {
            log.debug("pdd_order_list_browser_response_unavailable error_type={}",
                error.getClass().getSimpleName());
          }
          return;
        }
        if (session.querying || session.basic == null || session.orderId == null) return;
        if (PddBrowserSessionService.ORDER_PATHS.stream().noneMatch(
            path -> PddBrowserSessionService.isEndpoint(response.url(), "mms.pinduoduo.com", path))) return;
        try {
          var request = json.readTree(response.request().postData());
          if (!session.orderId.equals(request.path("orderSn").asText())) return;
          session.latest = capture(session, response);
          session.latestAt = System.currentTimeMillis();
          session.lastUsed = session.latestAt;
          session.verificationCompleted = true;
        } catch (Exception error) {
          log.debug("pdd_order_verification_response_unavailable error_type={}", error.getClass().getSimpleName());
        }
      });
      return session;
    } catch (Exception error) {
      log.warn("pdd_order_browser_create_failed stage={} error_type={}",
          stage, error.getClass().getSimpleName());
      session.close();
      if (error instanceof PddPlatformException platform) throw platform;
      throw new PddPlatformException("PDD_BROWSER_UNAVAILABLE");
    }
  }

  private static String classifyOrderListError(String message) {
    if (message == null || message.isBlank()) return "empty";
    String normalized = message.toLowerCase(Locale.ROOT);
    if (normalized.contains("验证码") || normalized.contains("安全验证")
        || normalized.contains("风控") || normalized.contains("captcha")
        || normalized.contains("verify")) return "verification";
    if (normalized.contains("频繁") || normalized.contains("rate")
        || normalized.contains("too many")) return "rate_limited";
    if (normalized.contains("登录") || normalized.contains("会话")
        || normalized.contains("login") || normalized.contains("session")) return "session";
    if (normalized.contains("权限") || normalized.contains("forbidden")
        || normalized.contains("unauthorized")) return "permission";
    if (normalized.contains("参数") || normalized.contains("invalid")
        || normalized.contains("parameter")) return "parameter";
    return "other";
  }

  private Map<String, Object> capture(Session session, Response response) throws Exception {
    var body = json.readTree(response.body());
    if (PddOrderResponseCodes.rejected(body)) {
      reject(session);
      throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
    }
    Map<String, Object> normalized = parser.merge(session.basic, body);
    Map<String, String> headers = new LinkedHashMap<>();
    response.request().allHeaders().forEach((name, value) -> {
      if (PddBrowserSessionService.ORDER_HEADERS.contains(name.toLowerCase())
          && value.length() <= 32_000 && !value.contains("\r") && !value.contains("\n"))
        headers.put(name.toLowerCase(), value);
    });
    Map<String, String> cookies = new LinkedHashMap<>();
    session.context.cookies(PddWebClient.BASE_URL).forEach(cookie -> cookies.put(cookie.name, cookie.value));
    if (headers.getOrDefault("anti-content", "").isBlank() || cookies.isEmpty())
      throw new PddPlatformException("ORDER_SESSION_INVALID");
    PddBrowserSessionService.requireIdentity(clientFactory.apply(cookies), session.shop, session.account);
    if (!session.digest.equals(PddBrowserSessionService.credentialDigest(session.directory)))
      throw new PddPlatformException("ORDER_SESSION_INVALID");
    credentials.saveEncryptedRecord(session.directory, "order-session.enc", Map.of(
        "purpose", "order-query", "shop_id", session.shop, "account_id", session.account,
        "credential_digest", session.digest, "captured_at", OffsetDateTime.now(ZoneOffset.UTC).toString(),
        "headers", headers, "cookies", cookies, "query_path", URI.create(response.url()).getPath()));
    normalized.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return normalized;
  }

  private void reject(Session session) {
    // 40002 invalidates this browser, rather than starting a cooldown or reusing it.
    // Closing a locally launched persistent context also terminates its browser.
    session.rejected = true;
    session.latest = null;
    sessions.remove(session.directory.toAbsolutePath().normalize(), session);
    log.warn("pdd_order_rejected channel=original business_code=40002");
    session.close();
  }

  private void pump() {
    for (var entry : new ArrayList<>(sessions.entrySet())) {
      Session session = entry.getValue();
      try {
        if (session.page.isClosed() || session.lastUsed < System.currentTimeMillis() - 30 * 60_000) {
          sessions.remove(entry.getKey());
          session.close();
        } else session.page.waitForTimeout(25);
      } catch (Exception error) {
        sessions.remove(entry.getKey());
        session.close();
      }
    }
  }

  private <T> T submit(Callable<T> task) {
    Future<T> future = worker.submit(task);
    try { return future.get(65, TimeUnit.SECONDS); }
    catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PddPlatformException("ORDER_QUERY_INTERRUPTED");
    } catch (ExecutionException error) {
      if (error.getCause() instanceof PddPlatformException platform) throw platform;
      throw new PddPlatformException("ORDER_SESSION_CAPTURE_FAILED");
    } catch (TimeoutException error) {
      future.cancel(false);
      throw new PddPlatformException("ORDER_QUERY_BUSY");
    }
  }

  @PreDestroy
  public void close() {
    try { worker.submit(() -> { sessions.values().forEach(Session::close); sessions.clear(); }).get(5, TimeUnit.SECONDS); }
    catch (Exception ignored) { }
    finally { worker.shutdownNow(); }
  }
}
