package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PddOrderBrowserLifecycleTest {
  @TempDir Path directory;
  final ObjectMapper json = new ObjectMapper();

  record Browser(Playwright playwright, BrowserContext context, Page page, Runnable completeVerification) {}

  Browser browser(Set<String> threads) throws Exception {
    Playwright playwright = mock(Playwright.class);
    BrowserType chromium = mock(BrowserType.class);
    BrowserContext context = mock(BrowserContext.class);
    Page page = mock(Page.class);
    Response response = mock(Response.class);
    Request request = mock(Request.class);
    AtomicReference<java.util.function.Consumer<Response>> listener = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean completeVerification = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(invocation -> { listener.set(invocation.getArgument(0)); return null; }).when(page).onResponse(any());
    when(page.isClosed()).thenAnswer(invocation -> {
      if (completeVerification.getAndSet(false)) listener.get().accept(response);
      return false;
    });
    when(playwright.chromium()).thenAnswer(invocation -> { threads.add(Thread.currentThread().getName()); return chromium; });
    when(chromium.launchPersistentContext(any(Path.class), any())).thenReturn(context);
    when(context.newPage()).thenReturn(page);
    when(context.cookies(anyString())).thenReturn(List.of(new Cookie("session", "synthetic").setUrl(PddWebClient.BASE_URL)));
    when(page.url()).thenReturn(PddWebClient.BASE_URL + "/orders/detail");
    when(response.url()).thenReturn(PddWebClient.BASE_URL + "/mangkhut/mms/orderDetail");
    when(response.request()).thenReturn(request);
    when(request.allHeaders()).thenReturn(Map.of("anti-content", "synthetic"));
    when(request.postData()).thenReturn("{\"orderSn\":\"fixture-order\"}");
    when(response.body()).thenReturn("""
        {"success":true,"result":{"order_sn":"fixture-order","order_status_str":"已取消"}}
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    when(page.waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(), any(Runnable.class))).thenAnswer(invocation -> {
      threads.add(Thread.currentThread().getName());
      @SuppressWarnings("unchecked") var predicate = (Predicate<Response>) invocation.getArgument(0);
      assertTrue(predicate.test(response));
      ((Runnable) invocation.getArgument(1)).run();
      return response;
    });
    return new Browser(playwright, context, page, () -> completeVerification.set(true));
  }

  @Test
  void readsReuseTheirBrowserOnOneWorkerAndRecreateItOnlyWhenCredentialsChange() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String value = switch (exchange.getRequestURI().getPath()) {
        case "/janus/api/new/userinfo" -> "{\"id\":\"fixture-account\"}";
        case "/earth/api/merchant/queryMerchantInfoByMallId" -> "{\"mallId\":\"fixture-shop\"}";
        default -> "{\"orderSn\":\"fixture-order\",\"uid\":\"fixture-buyer\"}";
      };
      byte[] data = ("{\"success\":true,\"result\":" + value + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, data.length);
      exchange.getResponseBody().write(data);
      exchange.close();
    });
    server.start();
    var credentials = new PddCredentialStore(json);
    credentials.saveLogin(directory, "fixture-shop", "fixture-account", "login-" + "a".repeat(32), Map.of("session", "fixture"));
    var desktops = mock(PddLoginDesktopService.class);
    when(desktops.startDisplay(any())).thenReturn(new PddLoginDesktopService.Desktop(
        Map.of(), "", List.of(), directory, directory.resolve("password"), directory.resolve("auth"), directory.resolve("xauth"), 12345));
    Set<String> threads = Collections.synchronizedSet(new HashSet<>());
    List<Browser> browsers = new java.util.concurrent.CopyOnWriteArrayList<>();
    var service = new PddOrderBrowserService(json, credentials, new PddOrderParser(), desktops,
        () -> {
          try { var browser = browser(threads); browsers.add(browser); return browser.playwright(); }
          catch (Exception error) { throw new RuntimeException(error); }
        }, cookies -> new PddWebClient(json, cookies, "http://127.0.0.1:" + server.getAddress().getPort()));
    try {
      assertEquals("cancelled", service.read(directory, "fixture-shop", "fixture-account", "fixture-order").get("order_status"));
      assertEquals("cancelled", service.read(directory, "fixture-shop", "fixture-account", "fixture-order").get("order_status"));
      assertEquals(1, browsers.size());
      verify(browsers.get(0).context(), never()).close();
      assertEquals(Set.of("pdd-order-browser"), threads);
      browsers.get(0).completeVerification().run();
      assertEquals("cancelled", service.read(directory, "fixture-shop", "fixture-account", "fixture-order").get("order_status"));
      assertTrue(service.consumeVerification(directory, "fixture-order"));
      assertFalse(service.consumeVerification(directory, "fixture-order"));
      service.hideVerification(directory);
      verify(browsers.get(0).context(), never()).close();
      assertEquals("PDD_CREDENTIALS_UNAVAILABLE", assertThrows(PddPlatformException.class,
          () -> service.read(directory, "another-shop", "fixture-account", "fixture-order")).code());
      credentials.saveLogin(directory, "fixture-shop", "fixture-account", "login-" + "b".repeat(32), Map.of("session", "new-fixture"));
      service.read(directory, "fixture-shop", "fixture-account", "fixture-order");
      assertEquals(2, browsers.size());
      verify(browsers.get(0).context(), times(1)).close();
    } finally {
      service.close();
      server.stop(0);
    }
    verify(browsers.get(1).context(), times(1)).close();
  }
}
