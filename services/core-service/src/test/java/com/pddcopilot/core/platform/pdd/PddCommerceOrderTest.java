package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.service.ConnectorRegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PddCommerceOrderTest {
  @TempDir Path directory;
  final ObjectMapper json = new ObjectMapper();
  final BusinessStore store = mock(BusinessStore.class);
  final ConnectionRepository shops = mock(ConnectionRepository.class);
  final ConnectorRegistryService registry = mock(ConnectorRegistryService.class);
  final PddCredentialStore credentials = mock(PddCredentialStore.class);
  final PddBrowserSessionService browser = mock(PddBrowserSessionService.class);
  final Clock clock = mock(Clock.class);
  final AtomicInteger detailRequests = new AtomicInteger();
  final AtomicReference<String> detailResponse = new AtomicReference<>();

  PddCommerceService service(boolean sessionExists) throws Exception {
    when(store.shopId()).thenReturn("shop-a");
    when(shops.findShop("shop-a")).thenReturn(List.of(Map.of(
        "mode", "live", "binding_state", "bound", "platform_shop_id", "mall-a",
        "connector_account_id", "account-a")));
    when(registry.directory("shop-a")).thenReturn(directory);
    when(credentials.load(directory, "mall-a", "account-a")).thenReturn(Map.of("cookie", "fixture"));
    when(credentials.loadOrderSession(directory, "mall-a", "account-a"))
        .thenReturn(Map.of("cookies", Map.of("cookie", "fixture"), "headers", Map.of("anti-content", "fixture")));
    when(clock.millis()).thenReturn(0L);
    if (sessionExists) Files.createFile(directory.resolve("order-session.enc"));
    return new PddCommerceService(store, shops, registry, credentials, new PddProductParser(),
        new PddOrderParser(), json, browser, new PddOrderQueryThrottle(60, clock));
  }

  org.mockito.MockedConstruction<PddWebClient> transport(String detail) {
    detailResponse.set(detail);
    return mockConstruction(PddWebClient.class, (client, construction) -> {
      when(client.identity()).thenReturn(new PddWebClient.Identity("mall-a", "account-a"));
      when(client.postJson(eq("/latitude/order/orderSimpleDetail"), any(), any()))
          .thenReturn(json.readTree("""
              {"success":true,"result":{"orderSn":"fixture-order","uid":"fixture-buyer",
              "goodsName":"测试商品","goodsNumber":1}}
              """));
      when(client.postJson(eq("/mangkhut/mms/orderDetail"), any(), any()))
          .thenAnswer(invocation -> { detailRequests.incrementAndGet(); return json.readTree(detailResponse.get()); });
    });
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> order(PddCommerceService service) {
    return (Map<String, Object>) service.orderDetail("fixture-order", "fixture-buyer").get("order");
  }

  @Test
  void frequentOperationDoesNotRefreshCredentialsAndSuppressesFurtherDetailQueries() throws Exception {
    var service = service(true);
    try (var clients = transport("{\"error_code\":54001,\"error_msg\":\"操作太过频繁，请稍后再试！\"}")) {
      var first = order(service);
      assertEquals("unknown", first.get("order_status"));
      assertEquals("ORDER_QUERY_RATE_LIMITED", first.get("status_error_code"));
      assertEquals(60L, first.get("retry_after_seconds"));
      assertEquals(1, first.get("purchased_quantity"));
      assertEquals("ORDER_QUERY_RATE_LIMITED", order(service).get("status_error_code"));
      assertEquals(1, detailRequests.get());
      verifyNoInteractions(browser);
      when(clock.millis()).thenReturn(60_000L);
      order(service);
      assertEquals(2, detailRequests.get());
    }
  }

  @Test
  void browserRateLimitStopsRefreshRecursionAndStartsCooldown() throws Exception {
    var service = service(false);
    doThrow(new PddPlatformException("ORDER_QUERY_RATE_LIMITED"))
        .when(browser).captureOrder(directory, "mall-a", "account-a", "fixture-order");
    try (var clients = transport("{}")) {
      assertEquals("ORDER_QUERY_RATE_LIMITED", order(service).get("status_error_code"));
      assertEquals("ORDER_QUERY_RATE_LIMITED", order(service).get("status_error_code"));
      verify(browser, times(1)).captureOrder(directory, "mall-a", "account-a", "fixture-order");
      assertEquals(0, detailRequests.get());
    }
  }

  @Test
  void trueExpiredSessionRefreshesOnceAndReturnsTheNewVerifiedStatus() throws Exception {
    var service = service(true);
    try (var clients = transport("{\"error_code\":43001}")) {
      doAnswer(invocation -> {
        return Map.of("order_id", "fixture-order", "order_status", "cancelled",
            "status_text", "已取消", "fetched_at", "2026-01-01T00:00:00Z");
      }).when(browser).captureOrder(any(), anyString(), anyString(), anyString());
      var result = order(service);
      assertEquals("cancelled", result.get("order_status"));
      assertNull(result.get("status_error_code"));
      assertNotNull(result.get("fetched_at"));
      assertDoesNotThrow(() -> java.time.OffsetDateTime.parse((String) result.get("fetched_at")));
      verify(browser, times(1)).captureOrder(directory, "mall-a", "account-a", "fixture-order");
      assertEquals(1, detailRequests.get());
    }
  }

  @Test
  void blockedHeaderReplayUsesOneFreshBrowserResponseWithoutReplayingItAgain() throws Exception {
    var service = service(true);
    when(browser.captureOrder(directory, "mall-a", "account-a", "fixture-order"))
        .thenReturn(Map.of("order_id", "fixture-order", "order_status", "cancelled",
            "status_text", "已取消", "fetched_at", "2026-01-01T00:00:00Z"));
    try (var clients = transport("""
        {"error_code":54001,"result":{"verifyAuthToken":"synthetic-challenge"}}
        """)) {
      assertEquals("cancelled", order(service).get("order_status"));
      verify(browser, times(1)).captureOrder(directory, "mall-a", "account-a", "fixture-order");
      assertEquals(1, detailRequests.get());
    }
  }

  @Test
  void wrongBuyerNeverQueriesStatusOrRefreshesTheSessionEvenDuringCooldown() throws Exception {
    var service = service(true);
    try (var clients = transport("{\"error_code\":54001}")) {
      order(service);
      assertEquals("ORDER_BUYER_MISMATCH", assertThrows(PddPlatformException.class,
          () -> service.orderDetail("fixture-order", "another-buyer")).code());
      assertEquals(1, detailRequests.get());
      verifyNoInteractions(browser);
    }
  }
}
