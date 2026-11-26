package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures only read-only browser transport material; it never sends a buyer message. */
@Service
public class PddBrowserSessionService {
  private static final Logger log = LoggerFactory.getLogger(PddBrowserSessionService.class);
  private static final String PRODUCT_PATH = "/latitude/goods/recommendGoods";
  private static final String ORDER_PATH = "/mangkhut/mms/orderDetail";
  static final Set<String> ORDER_PATHS = Set.of(ORDER_PATH, "/mangkhut/mms/routeOrderDetail");
  private static final Set<String> ORDER_READ_POSTS = Set.of(
      ORDER_PATH, "/mangkhut/mms/routeOrderDetail", "/mangkhut/mms/recentOrderList",
      "/mars/app/order/statisticWithType", "/mars/shop/orderList/hasBanner",
      "/mangkhut/tools/showSameCityDeliveryTab", "/mangkhut/tools/showDeliveryScheduleTab",
      "/mangkhut/tools/notice",
      "/janus/api/new/userinfo",
      "/janus/api/checkLogin", "/earth/api/merchant/queryMerchantInfoByMallId",
      "/janus/api/pageResources/sidebar/navigation/query", "/janus/api/subSystem/getAuthToken",
      "/space/user/login", "/mspace/user/login", "/api/phantom/vc_pre_ck_b");
  static final Set<String> ORDER_HEADERS = Set.of(
      "accept", "anti-content", "origin", "referer", "user-agent", "content-type",
      "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform");
  private final ObjectMapper json;
  private final PddCredentialStore credentials;
  private final PddOrderBrowserService orderBrowsers;
  private final PddOrderQueryService orderQueries;
  private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

  public PddBrowserSessionService(
      ObjectMapper json, PddCredentialStore credentials, PddOrderBrowserService orderBrowsers,
      PddOrderQueryService orderQueries) {
    this.json = json;
    this.credentials = credentials;
    this.orderBrowsers = orderBrowsers;
    this.orderQueries = orderQueries;
  }

  public void captureProduct(Path directory, String shopId, String accountId) {
    withLock(directory, () -> { captureProductLocked(directory, shopId, accountId); return null; });
  }

  public Map<String, Object> captureOrder(Path directory, String shopId, String accountId, String orderId) {
    if (orderId == null || orderId.isBlank() || orderId.length() > 200)
      throw new PddPlatformException("ORDER_REFERENCE_REQUIRED");
    return orderQueries.detail(directory, shopId, accountId, orderId).data();
  }

  public Map<String, Object> captureOrderList(Path directory, String shopId, String accountId,
      int orderType, int pageNumber) {
    return orderQueries.list(directory, shopId, accountId, orderType, pageNumber).data();
  }

  public Map<String, Object> openOrderListBrowser(Path directory, String shopId, String accountId) {
    return orderBrowsers.openListBrowser(directory, shopId, accountId);
  }

  public Map<String, Object> orderVerification(Path directory, String shop, String account, String orderId) {
    return orderBrowsers.verification(directory, shop, account, orderId);
  }

  public void hideOrderVerification(Path directory) {
    orderBrowsers.hideVerification(directory);
  }

  public boolean consumeOrderVerification(Path directory, String orderId) {
    return orderBrowsers.consumeVerification(directory, orderId);
  }

  private void captureProductLocked(Path directory, String shopId, String accountId) {
    Map<String, String> cookies = credentials.load(directory, shopId, accountId);
    PddWebClient http = new PddWebClient(json, cookies);
    requireIdentity(http, shopId, accountId);
    String digest = credentialDigest(directory);
    String step = "browser_start";
    try (Playwright playwright = Playwright.create()) {
      BrowserContext context = context(playwright,
          directory.resolve("browser_profiles").resolve("java-product-query"), cookies, null);
      try {
        context.route("**/*", route -> {
          URI uri = URI.create(route.request().url());
          if (Set.of("/plateau/chat/send_message", "/plateau/chat/set_csstatus")
              .contains(uri.getPath())) route.abort();
          else route.resume();
        });
        var page = context.newPage();
        step = "merchant_page_navigation";
        page.navigate(PddWebClient.CHAT_URL,
            new com.microsoft.playwright.Page.NavigateOptions().setWaitUntil(
                com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(35_000));
        var tab = page.getByText("商品推荐", new com.microsoft.playwright.Page.GetByTextOptions()
            .setExact(true)).first();
        step = "product_tab_wait";
        tab.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(45_000));
        step = "product_request_capture";
        Response response = page.waitForResponse(
            r -> isEndpoint(r.url(), "mms.pinduoduo.com", PRODUCT_PATH),
            () -> tab.click());
        step = "product_response_parse";
        JsonNode payload = json.readTree(response.body());
        if (!payload.path("success").asBoolean(false))
          throw new PddPlatformException("PRODUCT_SESSION_QUERY_REJECTED");
        String antiContent = response.request().allHeaders().getOrDefault("anti-content", "");
        if (antiContent.isBlank()) throw new PddPlatformException("PRODUCT_SESSION_HEADER_MISSING");
        step = "product_query_verify";
        http.productPage(1, 10, Map.of("anti-content", antiContent));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("purpose", "product-query");
        record.put("shop_id", shopId);
        record.put("account_id", accountId);
        record.put("credential_digest", digest);
        record.put("captured_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        record.put("headers", Map.of("anti-content", antiContent));
        step = "session_persist";
        credentials.saveEncryptedRecord(directory, "product-session.enc", record);
      } finally {
        try {
          context.close();
        } catch (Exception closeError) {
          // The captured session is already persisted; a browser shutdown error must not
          // turn a successful read-only capture into a failed operation.
          log.warn("pdd_product_query_context_close_failed error_type={}",
              closeError.getClass().getSimpleName());
        }
      }
    } catch (PddPlatformException error) {
      throw error;
    } catch (Exception error) {
      String stack = java.util.Arrays.stream(error.getStackTrace()).limit(6)
          .map(frame -> frame.getClassName() + "." + frame.getMethodName()
              + ":" + frame.getLineNumber())
          .collect(java.util.stream.Collectors.joining(" | "));
      log.warn("pdd_product_session_capture_failed step={} error_type={} stack={}",
          step, error.getClass().getSimpleName(), stack);
      throw new PddPlatformException("PRODUCT_SESSION_CAPTURE_FAILED");
    }
  }

  private BrowserContext context(Playwright playwright, Path profile, Map<String, String> cookies,
      PddLoginDesktopService.Desktop display) {
    try {
      Files.createDirectories(profile);
      try { Files.setPosixFilePermissions(profile.getParent(),
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
      catch (UnsupportedOperationException ignored) { }
      try { Files.setPosixFilePermissions(profile,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
      catch (UnsupportedOperationException ignored) { }
      BrowserType.LaunchPersistentContextOptions options =
          new BrowserType.LaunchPersistentContextOptions().setHeadless(display == null)
              .setArgs(List.of("--disable-notifications", "--disable-dev-shm-usage"));
      if (display != null) options.setEnv(display.environment());
      BrowserContext context = playwright.chromium().launchPersistentContext(profile, options);
      context.addCookies(cookiesToRestore(cookies, context.cookies(PddWebClient.BASE_URL), display != null));
      return context;
    } catch (Exception error) {
      log.warn("pdd_browser_launch_failed error_type={}", error.getClass().getSimpleName());
      throw new PddPlatformException("PDD_BROWSER_UNAVAILABLE");
    }
  }

  static List<Cookie> cookiesToRestore(
      Map<String, String> saved, List<Cookie> existing, boolean preserveExisting) {
    Set<String> names = existing.stream().map(cookie -> cookie.name)
        .collect(java.util.stream.Collectors.toSet());
    List<Cookie> result = new ArrayList<>();
    saved.forEach((name, value) -> {
      if (!preserveExisting || !names.contains(name))
        result.add(new Cookie(name, value).setUrl(PddWebClient.BASE_URL));
    });
    return result;
  }

  static Path loginProfile(Path directory, ObjectMapper json) throws Exception {
    Map<String, Object> binding = json.readValue(
        Files.readString(directory.resolve("connection.json")),
        new TypeReference<Map<String, Object>>() {});
    Object rawName = binding.get("profile_name");
    if (!(rawName instanceof String name) || !name.matches("login-[a-f0-9]{32}"))
      throw new PddPlatformException("PDD_BROWSER_PROFILE_UNAVAILABLE");
    Path profile = directory.resolve("browser_profiles").resolve(name).normalize();
    if (!profile.startsWith(directory.resolve("browser_profiles").normalize()))
      throw new PddPlatformException("PDD_BROWSER_PROFILE_UNAVAILABLE");
    removeStaleChromiumSingleton(profile);
    return profile;
  }

  private static void removeStaleChromiumSingleton(Path profile) {
    Path lock = profile.resolve("SingletonLock");
    Path socket = profile.resolve("SingletonSocket");
    try {
      if (!Files.isSymbolicLink(lock)) return;
      String owner = Files.readSymbolicLink(lock).toString();
      int split = owner.lastIndexOf('-');
      if (split <= 0) return;
      String ownerHost = owner.substring(0, split);
      long ownerPid = Long.parseLong(owner.substring(split + 1));
      String currentHost = java.net.InetAddress.getLocalHost().getHostName();
      boolean processAlive = ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive).orElse(false);
      boolean socketAlive = Files.isSymbolicLink(socket)
          && Files.exists(Path.of(Files.readSymbolicLink(socket).toString()));
      if ((!ownerHost.equals(currentHost) || !processAlive) && !socketAlive) {
        Files.deleteIfExists(profile.resolve("SingletonCookie"));
        Files.deleteIfExists(lock);
        Files.deleteIfExists(socket);
        log.info("pdd_browser_stale_profile_lock_removed");
      }
    } catch (Exception error) {
      log.debug("pdd_browser_profile_lock_check_failed error_type={}",
          error.getClass().getSimpleName());
    }
  }

  static boolean isEndpoint(String url, String host, String path) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) return false;
      return host.equalsIgnoreCase(uri.getHost()) && path.equals(uri.getPath());
    } catch (Exception ignored) {
      return false;
    }
  }

  static boolean allowOrderRequest(String method, String url) {
    if (Set.of("GET", "HEAD", "OPTIONS").contains(method)) return true;
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) return false;
      return "POST".equals(method) && "mms.pinduoduo.com".equalsIgnoreCase(uri.getHost())
          && (ORDER_READ_POSTS.contains(uri.getPath()) || uri.getPath().startsWith("/api/phantom/"))
          || "POST".equals(method) && Set.of("captcha.pinduoduo.com", "captcha.yangkeduo.com").contains(uri.getHost());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  static void requireIdentity(PddWebClient client, String shopId, String accountId) {
    PddWebClient.Identity identity = client.identity();
    if (!shopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("LOGIN_ACCOUNT_MISMATCH");
  }

  static String credentialDigest(Path directory) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(directory.resolve("credentials.enc"))));
    } catch (Exception error) {
      throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    }
  }

  private <T> T withLock(Path directory, java.util.function.Supplier<T> action) {
    Path key = directory.toAbsolutePath().normalize();
    ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
    lock.lock();
    try { return action.get(); }
    finally { lock.unlock(); }
  }
}
