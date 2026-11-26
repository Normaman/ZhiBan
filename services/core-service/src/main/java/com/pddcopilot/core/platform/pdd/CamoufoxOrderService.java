package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.AriaRole;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One remote instance per attempt; never shares the original channel's credentials. */
@Service
public class CamoufoxOrderService {
  private static final Logger log = LoggerFactory.getLogger(CamoufoxOrderService.class);
  private final CamoufoxGatewayClient gateway;
  private final ObjectMapper json;
  private final PddOrderParser parser;
  private final CamoufoxLoginStateStore loginStates;
  private final CamoufoxAuthentication authentication;

  public CamoufoxOrderService(CamoufoxGatewayClient gateway, ObjectMapper json, PddOrderParser parser,
      CamoufoxLoginStateStore loginStates, CamoufoxAuthentication authentication) {
    this.gateway = gateway;
    this.json = json;
    this.parser = parser;
    this.loginStates = loginStates;
    this.authentication = authentication;
  }

  private static final class Attempt {
    Browser browser;
    boolean rejected;
    JsonNode result;
    String responseError;
    int latestListPage = -1;
    int latestListType = -1;
    int latestListPageSize = -1;
    String stage = "connect";
    long lastListRequest;
    final String instanceId;
    final long deadline;

    Attempt(String instanceId, int ttlSeconds) {
      this.instanceId = instanceId;
      // Leave time for release before the gateway's independent hard TTL expires.
      this.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(75, ttlSeconds - 10));
    }

    double remaining() {
      if (Thread.currentThread().isInterrupted()) throw new PddPlatformException("ORDER_QUERY_INTERRUPTED");
      long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (millis <= 0) throw new PddPlatformException("CAMOUFOX_ORDER_TIMEOUT");
      return millis;
    }

    void check() {
      if (rejected) throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      if (responseError != null) throw new PddPlatformException(responseError);
      remaining();
    }
  }

  OrderResult query(PddOrderTarget target, String localShop, String shop, String account) {
    CamoufoxGatewayClient.Instance instance = gateway.fetch();
    Playwright playwright = null;
    Browser browser = null;
    BrowserContext context = null;
    Page page = null;
    RuntimeException failure = null;
    Attempt state = new Attempt(instance.instanceId(), instance.ttlSeconds());
    CamoufoxLoginStateStore.Saved saved = null;
    try {
      playwright = Playwright.create();
      // Camoufox exposes the Playwright Firefox protocol, never Chromium CDP.
      browser = playwright.firefox().connect(instance.wsEndpoint(),
          new BrowserType.ConnectOptions().setTimeout(Math.min(15_000, state.remaining())));
      state.browser = browser;
      browser.onDisconnected(ignored -> log.info(
          "pdd_order_browser_disconnected channel=camoufox instanceId={}", instance.instanceId()));
      if (!browser.contexts().isEmpty()) throw new PddPlatformException("CAMOUFOX_LOGIN_CONTEXT_NOT_EMPTY");
      log.info("pdd_order_login channel=camoufox instanceId={} state=restoring", instance.instanceId());
      saved = loginStates.load(localShop, shop, account);
      // Restore only the independently scanned/approved Camoufox session.
      context = browser.newContext(new Browser.NewContextOptions()
          .setStorageState(saved.json()).setViewportSize(null)
          .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
      context.routeWebSocket("**/*", WebSocketRoute::close);
      page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
      // A fresh leased browser may include its initial page. Close other pages so
      // background tabs cannot generate a second order-query task in this instance.
      for (Page other : context.pages()) if (other != page) close(other, "page", instance.instanceId());
      page.setDefaultTimeout(15_000);
      page.route("**/*", route -> route(route, state));
      page.onResponse(response -> observe(response, state, target, instance.instanceId()));
      log.info("pdd_order_page channel=camoufox instanceId={} state=navigating", instance.instanceId());
      state.stage = "navigate";
      Response navigation = page.navigate(target.url(), new Page.NavigateOptions()
          .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(state.remaining()));
      if (navigation != null && navigation.status() >= 400)
        throw new PddPlatformException("CAMOUFOX_ORDER_PAGE_HTTP_" + navigation.status());
      state.check();
      checkPage(page);
      page.waitForLoadState(LoadState.NETWORKIDLE,
          new Page.WaitForLoadStateOptions().setTimeout(state.remaining()));
      state.check();
      log.info("pdd_order_page channel=camoufox instanceId={} state=networkidle", instance.instanceId());
      // A bounded pause precedes our explicit queries/UI actions. Initial page requests
      // can happen during navigation; they are paced by the route handler below.
      int pause = ThreadLocalRandom.current().nextInt(3000, 7001);
      if (state.remaining() < pause) throw new PddPlatformException("CAMOUFOX_ORDER_TIMEOUT");
      page.waitForTimeout(pause);
      state.check();
      authentication.verify(page, shop, account, instance.instanceId(), state.remaining());

      Map<String, Object> result;
      if (target.detail()) {
        JsonNode basic = browserPost(page, state, "/latitude/order/orderSimpleDetail",
            Map.of("orderSn", target.orderId()));
        String buyer = basic.path("result").path("uid").asText("");
        if (buyer.isBlank()) throw new PddPlatformException("ORDER_QUERY_UNAVAILABLE");
        Map<String, Object> base = parser.basic(basic, target.orderId(), buyer);
        waitForResult(page, state);
        result = parser.merge(base, state.result);
      } else {
        state.stage = "select_filter";
        selectListFilter(page, state, target.orderType());
        state.stage = "wait_first_page";
        waitForListPage(page, state, target.orderType(), 1);
        for (int current = 1; current < target.page(); current++) {
          page.getByText("下一页", new Page.GetByTextOptions().setExact(true)).last()
              .click(new Locator.ClickOptions().setTimeout(Math.min(12_000, state.remaining())));
          state.check();
          waitForListPage(page, state, target.orderType(), current + 1);
        }
        waitForResult(page, state);
        result = new LinkedHashMap<>(parser.list(state.result));
        result.put("order_type", target.orderType());
        result.put("page", target.page());
        result.put("page_size", state.latestListPageSize);
      }
      state.check();
      if (authentication.challenge(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
      if (authentication.loginPage(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_EXPIRED");
      loginStates.refresh(localShop, shop, account, saved, context.storageState());
      result.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
      return new OrderResult("camoufox", target.detail() ? "detail" : "list", result);
    } catch (RuntimeException error) {
      if (error instanceof PddPlatformException p && PddOrderResponseCodes.REJECTED.equals(p.code())) {
        state.rejected = true;
        close(browser, "browser", instance.instanceId());
      }
      failure = state.rejected ? new PddPlatformException(PddOrderResponseCodes.REJECTED)
          : error instanceof TimeoutError ? new PddPlatformException("CAMOUFOX_ORDER_TIMEOUT")
          : error instanceof PddPlatformException ? error : new PddPlatformException("CAMOUFOX_ORDER_FAILED");
      if (saved != null && failure instanceof PddPlatformException p
          && java.util.Set.of("CAMOUFOX_LOGIN_EXPIRED", "CAMOUFOX_LOGIN_CHALLENGE_REQUIRED").contains(p.code())) {
        try { loginStates.invalidate(localShop, saved); } catch (Exception ignored) { }
      }
      log.warn("pdd_order_failed channel=camoufox instanceId={} stage={} code={}",
          instance.instanceId(), state.stage, failure.getMessage());
      throw failure;
    } finally {
      // Release first: cleanup RPCs to an unresponsive browser must not postpone
      // the gateway's process termination. Browser.close() cannot replace release.
      try { gateway.release(instance.instanceId()); }
      catch (RuntimeException cleanup) {
        if (failure != null) failure.addSuppressed(cleanup);
        else throw cleanup;
      } finally {
        // Each close is independent, even if release itself failed.
        close(page, "page", instance.instanceId());
        close(context, "context", instance.instanceId());
        close(browser, "browser", instance.instanceId());
        close(playwright, "playwright", instance.instanceId());
      }
    }
  }

  private void route(Route route, Attempt state) {
    if (state.rejected) { route.abort(); return; }
    boolean basicDetail = "POST".equals(route.request().method())
        && PddBrowserSessionService.isEndpoint(route.request().url(), "mms.pinduoduo.com",
            "/latitude/order/orderSimpleDetail");
    if (!basicDetail
        && !PddBrowserSessionService.allowOrderRequest(route.request().method(), route.request().url())) {
      route.abort();
      return;
    }
    if (PddOrderResponseCodes.listEndpoint(route.request().url())) {
      JsonNode body = requestBody(route.request());
      int type = body.path("orderType").asInt(-1);
      if (type == 0 || type == 2) {
        // Use a monotonic clock. Do not pump Playwright events inside this handler:
        // nested route callbacks would let a later request overtake a paused one.
        long wait = TimeUnit.MILLISECONDS.toNanos(1500) - (System.nanoTime() - state.lastListRequest);
        if (wait > 0) {
          if (state.remaining() < TimeUnit.NANOSECONDS.toMillis(wait) + 1)
            throw new PddPlatformException("CAMOUFOX_ORDER_TIMEOUT");
          try { TimeUnit.NANOSECONDS.sleep(wait); }
          catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            route.abort();
            throw new PddPlatformException("ORDER_QUERY_INTERRUPTED");
          }
        }
        state.check();
        // Reserve before resume(): even this synchronous API can dispatch nested
        // Playwright callbacks while awaiting its protocol acknowledgement.
        state.lastListRequest = System.nanoTime();
        route.resume();
        return;
      }
    }
    route.resume();
  }


  private void observe(Response response, Attempt state, PddOrderTarget target, String instance) {
    if (!PddOrderResponseCodes.orderEndpoint(response.url()) || state.rejected) return;
    JsonNode request = requestBody(response.request());
    boolean relevant = target.detail()
        ? !PddOrderResponseCodes.listEndpoint(response.url())
            && target.orderId().equals(request.path("orderSn").asText())
        : PddOrderResponseCodes.listEndpoint(response.url())
            && request.path("orderType").asInt(-1) == target.orderType()
            && request.path("pageSize").asInt(-1) >= 20;
    try {
      JsonNode body = json.readTree(response.body());
      log.info("pdd_order_response channel=camoufox instanceId={} http={} codes={}",
          instance, response.status(), PddOrderResponseCodes.summary(body));
      if (PddOrderResponseCodes.listEndpoint(response.url())) {
        log.info("pdd_order_list_match channel=camoufox instanceId={} requested_type={} requested_page={}"
                + " response_type={} response_page={} response_page_size={} relevant={} rows={} total={}",
            instance, target.orderType(), target.page(), request.path("orderType").asInt(-1),
            request.path("pageNumber").asInt(-1), request.path("pageSize").asInt(-1), relevant,
            body.path("result").path("pageItems").isArray()
                ? body.path("result").path("pageItems").size() : -1,
            body.path("result").path("totalItemNum").asInt(-1));
      }
      if (PddOrderResponseCodes.rejected(body)) {
        state.rejected = true;
        log.warn("pdd_order_rejected channel=camoufox instanceId={} business_code=40002", instance);
        // No retry on this channel. Close now; finally still releases the process.
        close(state.browser, "browser", instance);
        return;
      }
      if (CamoufoxAuthentication.challengeResponse(body)) {
        state.responseError = "CAMOUFOX_LOGIN_CHALLENGE_REQUIRED";
        return;
      }
      if (!relevant) return;
      if (response.status() < 200 || response.status() >= 300) {
        state.responseError = "CAMOUFOX_ORDER_HTTP_" + response.status();
      } else {
        if (!target.detail()) {
          state.latestListPage = request.path("pageNumber").asInt(-1);
          state.latestListType = request.path("orderType").asInt(-1);
          state.latestListPageSize = request.path("pageSize").asInt(-1);
        }
        if (target.detail() || state.latestListPage == target.page()) state.result = body;
      }
    } catch (Exception error) {
      if (!state.rejected && relevant) {
        state.responseError = response.status() < 200 || response.status() >= 300
            ? "CAMOUFOX_ORDER_HTTP_" + response.status() : "CAMOUFOX_ORDER_RESPONSE_INVALID";
        log.warn("pdd_order_response_failed channel=camoufox instanceId={} http={} code={}",
            instance, response.status(), state.responseError);
      }
    }
  }

  private JsonNode requestBody(Request request) {
    try {
      return request.postData() == null ? json.createObjectNode() : json.readTree(request.postData());
    } catch (Exception invalid) { return json.createObjectNode(); }
  }

  private boolean hasListPage(Attempt state, int type, int number) {
    return state.latestListType == type && state.latestListPage == number
        && state.latestListPageSize >= 20;
  }

  private void selectListFilter(Page page, Attempt state, int type) {
    java.util.regex.Pattern label = switch (type) {
      case 1 -> java.util.regex.Pattern.compile("^待发货(?:\\s*[（(]\\d+[）)])?$");
      case 7 -> java.util.regex.Pattern.compile("^待付款(?:\\s*[（(]\\d+[）)])?$");
      default -> null;
    };
    if (label == null) return;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    int visibleMatches = 0;
    while (System.nanoTime() < deadline) {
      state.check();
      checkPage(page);
      if (hasListPage(state, type, 1)) {
        log.info("pdd_order_filter channel=camoufox order_type={} action=use_received_page", type);
        return;
      }
      Locator visible = page.locator(":visible");
      Locator candidate = null;
      // Prefer a unique interactive control; never choose an arbitrary text match.
      for (AriaRole role : new AriaRole[] {AriaRole.TAB, AriaRole.BUTTON, AriaRole.LINK}) {
        Locator controls = page.getByRole(role, new Page.GetByRoleOptions().setName(label)).and(visible);
        if (controls.count() == 1) {
          candidate = controls;
          break;
        }
      }
      Locator text = page.getByText(label).and(visible);
      visibleMatches = text.count();
      if (candidate == null && visibleMatches == 1) candidate = text;
      // Locator calls also dispatch response events, so check again before clicking.
      state.check();
      if (hasListPage(state, type, 1)) continue;
      if (candidate != null) {
        try {
          candidate.click(new Locator.ClickOptions().setTimeout(Math.min(2_000, state.remaining())));
          state.check();
          return;
        } catch (TimeoutError timeout) {
          state.check();
          if (hasListPage(state, type, 1)) return;
          log.warn("pdd_order_filter channel=camoufox order_type={} reason=click_failed", type);
          throw new PddPlatformException("ORDER_LIST_CAPTURE_FAILED");
        }
      }
      page.waitForTimeout(Math.min(100, state.remaining()));
    }
    state.check();
    if (hasListPage(state, type, 1)) return;
    log.warn("pdd_order_filter channel=camoufox order_type={} visible_matches={} reason={}",
        type, visibleMatches, visibleMatches > 1 ? "ambiguous" : "not_found");
    throw new PddPlatformException("ORDER_LIST_CAPTURE_FAILED");
  }

  private void waitForResult(Page page, Attempt state) {
    while (state.result == null && state.responseError == null) {
      state.check();
      checkPage(page);
      page.waitForTimeout(Math.min(100, state.remaining()));
    }
    state.check();
    if (state.responseError != null) throw new PddPlatformException(state.responseError);
  }

  private void waitForListPage(Page page, Attempt state, int type, int number) {
    while (!hasListPage(state, type, number) && state.responseError == null) {
      state.check();
      checkPage(page);
      page.waitForTimeout(Math.min(100, state.remaining()));
    }
    state.check();
    if (state.responseError != null) throw new PddPlatformException(state.responseError);
  }

  private void checkPage(Page page) {
    if (authentication.challenge(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
    if (authentication.loginPage(page)) throw new PddPlatformException("CAMOUFOX_LOGIN_EXPIRED");
  }

  private JsonNode browserPost(Page page, Attempt state, String path, Map<String, Object> data) {
    state.check();
    try {
      // Browser fetch keeps this request inside the gateway browser's network stack.
      // This is a normal same-origin API call, not fingerprint or webdriver spoofing.
      Map<String, Object> arguments = new LinkedHashMap<>();
      arguments.put("path", path);
      arguments.put("body", data == null ? null : json.writeValueAsString(data));
      arguments.put("timeout", Math.min(15_000, state.remaining()));
      String value = (String) page.evaluate("""
          async ({path, body, timeout}) => {
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), timeout);
            try {
              const response = await fetch(path, {method: 'POST', credentials: 'include',
                headers: body === null ? {} : {'Content-Type': 'application/json'},
                body, signal: controller.signal});
              return JSON.stringify({http: response.status, text: await response.text()});
            } catch (error) {
              return JSON.stringify({http: 0, timedOut: controller.signal.aborted});
            } finally { clearTimeout(timer); }
          }
          """, arguments);
      JsonNode result = json.readTree(value);
      if (result.path("timedOut").asBoolean()) throw new PddPlatformException("CAMOUFOX_ORDER_TIMEOUT");
      int status = result.path("http").asInt();
      if (status == 0) throw new PddPlatformException("CAMOUFOX_ORDER_BROWSER_REQUEST_FAILED");
      JsonNode body;
      try { body = json.readTree(result.path("text").asText()); }
      catch (Exception invalid) {
        throw new PddPlatformException(status < 200 || status >= 300
            ? "CAMOUFOX_ORDER_HTTP_" + status : "CAMOUFOX_ORDER_RESPONSE_INVALID");
      }
      if (body == null) throw new PddPlatformException(status < 200 || status >= 300
          ? "CAMOUFOX_ORDER_HTTP_" + status : "CAMOUFOX_ORDER_RESPONSE_INVALID");
      if (PddOrderResponseCodes.rejected(body)) {
        state.rejected = true;
        log.warn("pdd_order_rejected channel=camoufox instanceId={} business_code=40002", state.instanceId);
        close(state.browser, "browser", state.instanceId);
        throw new PddPlatformException(PddOrderResponseCodes.REJECTED);
      }
      if (CamoufoxAuthentication.challengeResponse(body))
        throw new PddPlatformException("CAMOUFOX_LOGIN_CHALLENGE_REQUIRED");
      if (status < 200 || status >= 300) throw new PddPlatformException("CAMOUFOX_ORDER_HTTP_" + status);
      return body;
    } catch (PddPlatformException error) { throw error; }
    catch (Exception error) { throw new PddPlatformException("CAMOUFOX_ORDER_BROWSER_REQUEST_FAILED"); }
  }

  private static void close(AutoCloseable resource, String name, String instance) {
    if (resource == null) return;
    try {
      resource.close();
      log.info("pdd_order_resource_closed channel=camoufox instanceId={} resource={}", instance, name);
    } catch (Exception error) {
      log.warn("pdd_order_close_failed channel=camoufox instanceId={} resource={}", instance, name);
    }
  }
}
