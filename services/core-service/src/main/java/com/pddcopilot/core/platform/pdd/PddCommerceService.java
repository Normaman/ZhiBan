package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.service.ConnectorRegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only product and order adapter for the existing merchant-page protocol. */
@Service
public class PddCommerceService {
  private static final Logger log = LoggerFactory.getLogger(PddCommerceService.class);
  private static final int PAGE_SIZE = 50;
  private static final int MAX_PAGES = 100;
  private final BusinessStore store;
  private final ConnectionRepository shops;
  private final ConnectorRegistryService registry;
  private final PddCredentialStore credentials;
  private final PddProductParser parser;
  private final PddOrderParser orderParser;
  private final ObjectMapper json;
  private final PddBrowserSessionService browserSessions;
  private final PddOrderQueryThrottle orderThrottle;
  private final Map<String, Semaphore> detailPermits = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> nextDetailAt = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> orderLocks = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> overviewLocks = new ConcurrentHashMap<>();
  private final Map<String, CachedOverview> overviewCache = new ConcurrentHashMap<>();
  private final Set<String> productDiagnostics = ConcurrentHashMap.newKeySet();
  private record CachedOverview(Map<String, Object> value, long expiresAt) {}

  public PddCommerceService(
      BusinessStore store,
      ConnectionRepository shops,
      ConnectorRegistryService registry,
      PddCredentialStore credentials,
      PddProductParser parser,
      PddOrderParser orderParser,
      ObjectMapper json,
      PddBrowserSessionService browserSessions,
      PddOrderQueryThrottle orderThrottle) {
    this.store = store;
    this.shops = shops;
    this.registry = registry;
    this.credentials = credentials;
    this.parser = parser;
    this.orderParser = orderParser;
    this.json = json;
    this.browserSessions = browserSessions;
    this.orderThrottle = orderThrottle;
  }

  public Map<String, Object> products() {
    String shopId = store.shopId();
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(shopId);
    if (directory == null)
      throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    var client = new PddWebClient(
        json, credentials.load(directory, platformShopId, accountId));
    PddWebClient.Identity identity = client.identity();
    if (!platformShopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");

    Map<String, String> headers = credentials.loadProductHeaders(directory, platformShopId, accountId);
    try {
      return fetchProducts(client, headers, shopId);
    } catch (PddPlatformException error) {
      if (!"PRODUCT_QUERY_REJECTED_3000000".equals(error.code())) throw error;
      try {
        browserSessions.captureProduct(directory, platformShopId, accountId);
        headers = credentials.loadProductHeaders(directory, platformShopId, accountId);
        return fetchProducts(client, headers, shopId);
      } catch (Exception refreshError) {
        throw new PddPlatformException("PRODUCT_SESSION_REFRESH_FAILED");
      }
    }
  }

  private Map<String, Object> fetchProducts(
      PddWebClient client, Map<String, String> headers, String shopId) {
    List<Map<String, Object>> products = new ArrayList<>();
    Integer expectedTotal = null;
    for (int page = 1; page <= MAX_PAGES; page++) {
      Map<String, Object> response = client.productPage(page, PAGE_SIZE, headers);
      Object rawRows = response.get("products");
      if (!(rawRows instanceof List<?> rows))
        throw new PddPlatformException("UNRECOGNIZED_PRODUCT_LIST_SCHEMA");
      String fetchedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
      for (Object raw : rows) {
        if (raw instanceof Map<?, ?> map) {
          Map<String, Object> product = new LinkedHashMap<>();
          map.forEach((key, value) -> product.put(key.toString(), value));
          product.put("fetched_at", fetchedAt);
          products.add(product);
        }
      }
      Object total = response.get("total");
      if (total instanceof Number number && number.intValue() >= 0) expectedTotal = number.intValue();
      if (rows.isEmpty() || (expectedTotal != null && products.size() >= expectedTotal))
        return Map.of("shop_id", shopId, "products", products);
      if (page < MAX_PAGES) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new PddPlatformException("PRODUCT_QUERY_INTERRUPTED");
        }
      }
    }
    throw new PddPlatformException("PRODUCT_QUERY_LIMIT_EXCEEDED");
  }

  public Map<String, Object> productDetail(String goodsId) {
    String shopId = store.shopId();
    Semaphore permits = detailPermits.computeIfAbsent(shopId, ignored -> new Semaphore(2));
    boolean acquired = false;
    try {
      permits.acquire();
      acquired = true;
      AtomicLong next = nextDetailAt.computeIfAbsent(shopId, ignored -> new AtomicLong());
      long startAt;
      while (true) {
        long now = System.currentTimeMillis();
        long previous = next.get();
        startAt = Math.max(now, previous);
        if (next.compareAndSet(previous, startAt + 1000)) break;
      }
      long delay = startAt - System.currentTimeMillis();
      if (delay > 0) Thread.sleep(delay);
      return queryProductDetail(shopId, goodsId);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PddPlatformException("PRODUCT_QUERY_INTERRUPTED");
    } finally {
      if (acquired) permits.release();
    }
  }

  private Map<String, Object> queryProductDetail(String shopId, String goodsId) {
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    if (!"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    PddWebClient client = new PddWebClient(
        json, credentials.load(directory, platformShopId, accountId));
    PddWebClient.Identity identity = client.identity();
    if (!platformShopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
    boolean refreshed = false;
    Map<String, Object> detail;
    try {
      detail = fetchDetail(client, directory, platformShopId, accountId, goodsId);
    } catch (PddPlatformException error) {
      if (!Set.of("PRODUCT_SESSION_INVALID", "PLATFORM_SESSION_EXPIRED", "PRODUCT_DETAIL_UNAVAILABLE")
          .contains(error.code())) throw error;
      try {
        browserSessions.captureProduct(directory, platformShopId, accountId);
        detail = fetchDetail(client, directory, platformShopId, accountId, goodsId);
        refreshed = true;
      } catch (Exception refreshError) {
        throw new PddPlatformException("PRODUCT_SESSION_REFRESH_FAILED");
      }
    }
    detail.put("_query_session_refreshed", refreshed);
    detail.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return Map.of("shop_id", shopId, "detail", detail);
  }

  private Map<String, Object> fetchDetail(
      PddWebClient client, Path directory, String platformShopId, String accountId, String goodsId) {
    Map<String, String> captured = credentials.loadProductHeaders(directory, platformShopId, accountId);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("anti-content", captured.getOrDefault("anti-content", ""));
    headers.put("origin", PddWebClient.BASE_URL);
    headers.put("referer", PddWebClient.BASE_URL + "/goods/goods_detail");
    var raw = client.postJson(
        "/glide/v2/mms/query/commit/on_shop/detail", Map.of("goods_id", goodsId), headers);
    Map<String, Object> detail = parser.detail(raw, goodsId, platformShopId);
    String templateId = (String) detail.get("freight_template_id");
    if (templateId == null && productDiagnostics.add("freight-template-value-state")) {
      JsonNode nativeDetail = raw.path("result");
      log.info(
          "pdd_product_schema section=goods_detail result_keys={} freight_field_paths={} cost_template_id_type={} cost_template_id_positive_integer={} sku_count={}",
          fieldNames(nativeDetail, 80),
          matchingFieldPaths(nativeDetail, Set.of("cost", "freight", "template", "express")),
          nodeType(nativeDetail.get("cost_template_id")),
          isPositiveInteger(nativeDetail.get("cost_template_id")),
          nativeDetail.path("skus").isArray() ? nativeDetail.path("skus").size() : -1);
    }
    if (templateId != null) {
      try {
        var freight = client.postJson(
            "/express_inf/cost_template/get_one",
            Map.of("costTemplateId", new BigInteger(templateId)), headers);
        detail.put("freight", parser.freight(freight, templateId));
      } catch (Exception error) {
        if (productDiagnostics.add("freight-query-failed")) {
          log.info("pdd_product_schema section=freight error_code={}", safeErrorCode(error));
        }
        // Keep the explicit unavailable freight state from the detail payload.
      }
    }
    try {
      var properties = client.postJson(
          "/latitude/goods/goodsProperties", Map.of("goodsId", goodsId), headers);
      List<Map<String, Object>> parsedAttributes = parser.attributes(properties, goodsId);
      detail.put("attributes", parsedAttributes);
      detail.put("attributes_status", "synced");
      if (parsedAttributes.isEmpty() && productDiagnostics.add("attributes-empty")) {
        JsonNode result = properties.path("result");
        log.info(
            "pdd_product_schema section=attributes success={} result_keys={} goods_id_match={} properties_type={} properties_count=0",
            properties.path("success").asBoolean(false),
            fieldNames(result, 80),
            goodsId.equals(result.path("goodsId").asText()),
            nodeType(result.get("properties")));
      }
    } catch (Exception error) {
      detail.put("attributes", List.of());
      detail.put("attributes_status", "unavailable");
      if (productDiagnostics.add("attributes-unavailable")) {
        log.info("pdd_product_schema section=attributes error_code={}", safeErrorCode(error));
      }
    }
    return detail;
  }

  private List<String> fieldNames(JsonNode node, int limit) {
    List<String> names = new ArrayList<>();
    if (node != null && node.isObject()) {
      node.fieldNames().forEachRemaining(name -> {
        if (names.size() < limit) names.add(name);
      });
    }
    return names;
  }

  private List<String> matchingFieldPaths(JsonNode node, Set<String> fragments) {
    List<String> paths = new ArrayList<>();
    collectMatchingFieldPaths(node, "result", fragments, paths, 0, new int[] {0});
    return paths;
  }

  private void collectMatchingFieldPaths(
      JsonNode node, String path, Set<String> fragments, List<String> paths, int depth, int[] visited) {
    if (node == null || depth > 8 || paths.size() >= 30 || visited[0]++ >= 3000) return;
    if (node.isObject()) {
      node.fields().forEachRemaining(entry -> {
        if (paths.size() >= 30 || visited[0] >= 3000) return;
        String childPath = path + "." + entry.getKey();
        String normalized = entry.getKey().toLowerCase(java.util.Locale.ROOT);
        if (fragments.stream().anyMatch(normalized::contains)) paths.add(childPath);
        collectMatchingFieldPaths(entry.getValue(), childPath, fragments, paths, depth + 1, visited);
      });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size() && i < 20; i++) {
        collectMatchingFieldPaths(node.get(i), path + "[]", fragments, paths, depth + 1, visited);
        if (paths.size() >= 30 || visited[0] >= 3000) break;
      }
    }
  }

  private String nodeType(JsonNode node) {
    if (node == null || node.isMissingNode()) return "missing";
    if (node.isNull()) return "null";
    if (node.isArray()) return "array";
    if (node.isObject()) return "object";
    if (node.isTextual()) return "text";
    if (node.isNumber()) return "number";
    return "other";
  }

  private boolean isPositiveInteger(JsonNode node) {
    return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0;
  }

  private String safeErrorCode(Exception error) {
    return error instanceof PddPlatformException platform
        ? platform.code()
        : error.getClass().getSimpleName();
  }

  public Map<String, Object> orderDetail(String orderId, String buyerRef) {
    if (orderId == null || orderId.isBlank() || orderId.length() > 200
        || buyerRef == null || buyerRef.isBlank() || buyerRef.length() > 200)
      throw new PddPlatformException("ORDER_REFERENCE_REQUIRED");
    String shopId = store.shopId();
    ReentrantLock lock = orderLocks.computeIfAbsent(shopId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      return queryOrder(shopId, orderId, buyerRef);
    } finally {
      lock.unlock();
    }
  }

  public Map<String, Object> orderVerification(String orderId, String buyerRef) {
    // Validate the order and buyer before exposing the authenticated merchant browser.
    Map<String, Object> current = orderDetail(orderId, buyerRef);
    @SuppressWarnings("unchecked")
    var order = (Map<String, Object>) current.get("order");
    if (!"unknown".equals(order.get("order_status"))) return current;
    if (!"ORDER_QUERY_VERIFICATION_REQUIRED".equals(order.get("status_error_code"))) return current;
    String shopId = store.shopId();
    var shop = shops.findShop(shopId).get(0);
    return browserSessions.orderVerification(registry.directory(shopId),
        String.valueOf(shop.get("platform_shop_id")), String.valueOf(shop.get("connector_account_id")), orderId);
  }

  public Map<String, Object> retryVerifiedOrder(String orderId, String buyerRef) {
    Path directory = registry.directory(store.shopId());
    if (directory != null && browserSessions.consumeOrderVerification(directory, orderId))
      orderThrottle.clear(store.shopId());
    var result = orderDetail(orderId, buyerRef);
    @SuppressWarnings("unchecked")
    var order = (Map<String, Object>) result.get("order");
    if (!"unknown".equals(order.get("order_status"))) closeOrderVerification();
    return result;
  }

  public void closeOrderVerification() {
    Path directory = registry.directory(store.shopId());
    if (directory != null) browserSessions.hideOrderVerification(directory);
  }

  /** Reads one page of merchant orders using the observed read-only list request. */
  public Map<String, Object> orderList(int orderType, int pageNumber) {
    if (!Set.of(1, 2, 7).contains(orderType) || pageNumber < 1 || pageNumber > 100)
      throw new PddPlatformException("ORDER_LIST_FILTER_INVALID");
    String shopId = store.shopId();
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    return browserSessions.captureOrderList(
        directory, platformShopId, accountId, orderType, pageNumber);
  }

  /** Opens the same read-only Linux browser used to capture the merchant order list. */
  public Map<String, Object> openOrderListBrowser() {
    String shopId = store.shopId();
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    return browserSessions.openOrderListBrowser(directory, platformShopId, accountId);
  }

  /** Returns read-only order counts observed from the merchant order page. */
  public Map<String, Object> orderOverview() {
    return orderOverview(false);
  }

  public Map<String, Object> orderOverview(boolean refresh) {
    String shopId = store.shopId();
    CachedOverview cached = overviewCache.get(shopId);
    if (!refresh && cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.value();
    ReentrantLock lock = overviewLocks.computeIfAbsent(shopId, ignored -> new ReentrantLock());
    lock.lock();
    try {
      cached = overviewCache.get(shopId);
      if (!refresh && cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.value();
      try {
        Map<String, Object> result = queryOrderOverview(shopId);
        overviewCache.put(shopId, new CachedOverview(result, System.currentTimeMillis() + 60_000));
        return result;
      } catch (PddPlatformException error) {
        org.slf4j.LoggerFactory.getLogger(PddCommerceService.class)
            .warn("pdd_order_overview_failed code={}", error.code());
        throw error;
      }
    } finally {
      lock.unlock();
    }
  }

  private Map<String, Object> queryOrderOverview(String shopId) {
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    PddWebClient client = new PddWebClient(
        json, credentials.load(directory, platformShopId, accountId));
    PddWebClient.Identity identity = client.identity();
    if (!platformShopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");

    Map<String, String> captured;
    try {
      captured = credentials.loadProductHeaders(directory, platformShopId, accountId);
      if (captured.getOrDefault("anti-content", "").isBlank()) {
        browserSessions.captureProduct(directory, platformShopId, accountId);
        captured = credentials.loadProductHeaders(directory, platformShopId, accountId);
      }
    } catch (IllegalStateException invalidSession) {
      try {
        browserSessions.captureProduct(directory, platformShopId, accountId);
        captured = credentials.loadProductHeaders(directory, platformShopId, accountId);
      } catch (Exception refreshError) {
        throw new PddPlatformException("ORDER_STATS_SESSION_REFRESH_FAILED");
      }
    }
    String antiContent = captured.get("anti-content");
    if (antiContent == null || antiContent.isBlank())
      throw new PddPlatformException("ORDER_STATS_SESSION_REFRESH_FAILED");
    Map<String, String> headers = Map.of(
        "anti-content", antiContent,
        "origin", PddWebClient.BASE_URL,
        "referer", PddWebClient.BASE_URL + "/orders/list");

    Map<String, Integer> counts = new LinkedHashMap<>();
    for (int subType : List.of(15, 30, 29)) {
      var response = client.postJson(
          "/mars/app/order/statisticWithType",
          Map.of("source", "MMS", "subType", subType),
          headers);
      if (!response.path("success").asBoolean(false) || !response.path("result").isObject())
        throw new PddPlatformException("ORDER_STATS_UNAVAILABLE");
      var fields = response.path("result").fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (!Set.of("unshipped", "shipped", "waitPay", "refunding",
            "waitShippingDelay", "waitShippingUnShip12h", "waitConfirmUnShip12h",
            "waitConfirmDelay").contains(field.getKey())) continue;
        if (!field.getValue().canConvertToInt() || field.getValue().asInt() < 0)
          throw new PddPlatformException("ORDER_STATS_SCHEMA_UNSUPPORTED");
        int value = field.getValue().asInt();
        Integer prior = counts.putIfAbsent(field.getKey(), value);
        if (prior != null && prior != value)
          throw new PddPlatformException("ORDER_STATS_INCONSISTENT");
      }
    }
    if (!counts.keySet().containsAll(Set.of("unshipped", "shipped", "waitPay", "refunding")))
      throw new PddPlatformException("ORDER_STATS_SCHEMA_UNSUPPORTED");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("shop_id", shopId);
    result.put("counts", counts);
    result.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    result.put("details_available", false);
    return Map.copyOf(result);
  }

  private Map<String, Object> queryOrder(
      String shopId, String orderId, String buyerRef) {
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    if (!"live".equals(shop.get("mode")) || !"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    String platformShopId = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    PddWebClient client = new PddWebClient(
        json, credentials.load(directory, platformShopId, accountId));
    PddWebClient.Identity identity = client.identity();
    if (!platformShopId.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");
    Map<String, Object> order = orderParser.basic(
        client.postJson("/latitude/order/orderSimpleDetail", Map.of("orderSn", orderId), Map.of()),
        orderId,
        buyerRef);

    if (orderThrottle.retryAfterSeconds(shopId) > 0)
      return unavailableOrder(shopId, order, orderThrottle.code(shopId));

    String sessionError = null;
    try {
      Path sessionFile = directory.resolve("order-session.enc");
      if (!Files.exists(sessionFile)) throw new PddPlatformException("ORDER_SESSION_REQUIRED");
      Map<String, Object> session = credentials.loadOrderSession(directory, platformShopId, accountId);
      @SuppressWarnings("unchecked")
      Map<String, String> cookies = (Map<String, String>) session.get("cookies");
      @SuppressWarnings("unchecked")
      Map<String, String> headers = (Map<String, String>) session.get("headers");
      PddWebClient orderClient = new PddWebClient(json, cookies);
      PddWebClient.Identity orderIdentity = orderClient.identity();
      if (!platformShopId.equals(orderIdentity.platformShopId()) || !accountId.equals(orderIdentity.accountId()))
        throw new PddPlatformException("ORDER_SESSION_INVALID");
      var detail = orderClient.postJson(
          (String) session.getOrDefault("query_path", "/mangkhut/mms/orderDetail"),
          Map.of("orderSn", orderId, "source", "MMS"),
          headers);
      Map<String, Object> verified = orderParser.merge(order, detail);
      verified.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
      return Map.of("shop_id", shopId, "order", verified);
    } catch (PddPlatformException error) {
      sessionError = switch (error.code()) {
        case "ORDER_SESSION_REQUIRED", "ORDER_SESSION_INVALID", "ORDER_SESSION_EXPIRED" -> error.code();
        case "PLATFORM_SESSION_EXPIRED" -> "ORDER_SESSION_EXPIRED";
        case "ORDER_QUERY_RATE_LIMITED", "PLATFORM_HTTP_429" -> "ORDER_QUERY_RATE_LIMITED";
        case "ORDER_QUERY_VERIFICATION_REQUIRED" -> error.code();
        case "ORDER_STATUS_UNAVAILABLE", "ORDER_DETAIL_SCOPE_MISMATCH", "ORDER_DETAIL_NOT_FOUND" -> error.code();
        default -> "ORDER_STATUS_UNAVAILABLE";
      };
    } catch (IllegalStateException error) {
      sessionError = "ORDER_SESSION_INVALID";
    }

    if (Set.of("ORDER_SESSION_REQUIRED", "ORDER_SESSION_INVALID", "ORDER_SESSION_EXPIRED",
        "ORDER_QUERY_VERIFICATION_REQUIRED")
        .contains(sessionError)) {
      try {
        Map<String, Object> fresh = browserSessions.captureOrder(directory, platformShopId, accountId, orderId);
        return Map.of("shop_id", shopId, "order", fresh);
      } catch (Exception refreshError) {
        sessionError = refreshError instanceof PddPlatformException platformError
            ? platformError.code() : "ORDER_SESSION_CAPTURE_FAILED";
        log.warn("pdd_order_status_refresh_failed code={}", sessionError);
      }
    }
    if (Set.of("ORDER_QUERY_RATE_LIMITED", "ORDER_QUERY_VERIFICATION_REQUIRED").contains(sessionError))
      orderThrottle.pause(shopId, sessionError);
    return unavailableOrder(shopId, order, sessionError);
  }

  private Map<String, Object> unavailableOrder(
      String shopId, Map<String, Object> order, String code) {
    order.put("status_error_code", code);
    if (Set.of("ORDER_QUERY_RATE_LIMITED", "ORDER_QUERY_VERIFICATION_REQUIRED").contains(code))
      order.put("retry_after_seconds", orderThrottle.retryAfterSeconds(shopId));
    order.put("fetched_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return Map.of("shop_id", shopId, "order", order);
  }
}
