package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** HTTP transport for the currently observed merchant-page endpoints. */
public final class PddWebClient {
  public static final String BASE_URL = "https://mms.pinduoduo.com";
  public static final String CHAT_URL = BASE_URL + "/chat-merchant/index.html";
  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

  private final HttpClient http;
  private final ObjectMapper json;
  private final Map<String, String> cookies;
  private final String baseUrl;

  public PddWebClient(ObjectMapper json, Map<String, String> cookies) {
    this(json, cookies, BASE_URL);
  }

  PddWebClient(ObjectMapper json, Map<String, String> cookies, String baseUrl) {
    this.json = json;
    this.cookies = Map.copyOf(cookies);
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public JsonNode postJson(String path, Object payload, Map<String, String> headers) {
    try {
      return post(path, json.writeValueAsString(payload), headers);
    } catch (Exception error) {
      if (error instanceof PddPlatformException platform) throw platform;
      throw new PddPlatformException("PLATFORM_REQUEST_FAILED");
    }
  }

  public JsonNode postForm(String path, Map<String, String> fields) {
    StringJoiner body = new StringJoiner("&");
    fields.forEach((name, value) -> body.add(encode(name) + "=" + encode(value)));
    return post(path, body.toString(), Map.of());
  }

  public JsonNode postEmpty(String path) {
    return post(path, "", Map.of());
  }

  public JsonNode getToken() {
    JsonNode response = postForm("/chats/getToken", Map.of("version", "3"));
    JsonNode token = response.get("token");
    if (token == null && response.path("result").isObject())
      token = response.path("result").get("token");
    if (token == null || !token.isTextual() || token.asText().isBlank())
      throw new PddPlatformException("TOKEN_UNAVAILABLE");
    return token;
  }

  public record Identity(String platformShopId, String accountId) {}

  public Identity identity() {
    JsonNode user = postEmpty("/janus/api/new/userinfo");
    JsonNode shop = postJson("/earth/api/merchant/queryMerchantInfoByMallId", Map.of(), Map.of());
    if (!user.path("success").asBoolean(false) || !shop.path("success").asBoolean(false))
      throw new PddPlatformException("ACCOUNT_LOOKUP_FAILED");
    JsonNode userId = user.path("result").get("id");
    JsonNode shopId = shop.path("result").get("mallId");
    if (userId == null || userId.isNull() || shopId == null || shopId.isNull())
      throw new PddPlatformException("ACCOUNT_IDENTITY_MISSING");
    return new Identity(shopId.asText(), userId.asText());
  }

  public com.pddcopilot.core.api.dto.Contracts.ShopProfile shopProfile() {
    JsonNode user = postEmpty("/janus/api/new/userinfo");
    JsonNode shop = postJson("/earth/api/merchant/queryMerchantInfoByMallId", Map.of(), Map.of());
    JsonNode detail = shop.path("result");
    String userId = text(user.path("result").get("id"));
    String mallId = text(detail.get("mallId"));
    String name = text(detail.get("mallName")).trim();
    if (!user.path("success").asBoolean(false) || !shop.path("success").asBoolean(false)
        || userId.isBlank() || mallId.isBlank() || name.isBlank())
      throw new PddPlatformException("SHOP_PROFILE_UNAVAILABLE");
    String avatar = imageUrl(text(detail.get("mallLogo")));
    if (avatar != null && avatar.isBlank()) avatar = null;
    String category = text(first(detail.get("mallCategoryStr"), detail.get("categoryStr")));
    return new com.pddcopilot.core.api.dto.Contracts.ShopProfile(
        mallId,
        userId,
        name.substring(0, Math.min(200, name.length())),
        avatar,
        category.isBlank() ? null : category.substring(0, Math.min(500, category.length())));
  }

  public Map<String, Object> productPage(int page, int size, Map<String, String> productHeaders) {
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    String antiContent = productHeaders.get("anti-content");
    if (antiContent == null || antiContent.isBlank())
      antiContent = cookies.getOrDefault("anti_content", cookies.getOrDefault("anti-content", ""));
    headers.put("anti-content", antiContent);
    headers.put("origin", BASE_URL);
    headers.put("referer", CHAT_URL);
    JsonNode response = postJson(
        "/latitude/goods/recommendGoods",
        Map.of("uid", "", "pageNum", page, "pageSize", size),
        headers);
    if (!response.path("success").asBoolean(false)) {
      JsonNode code = first(response.get("errorCode"), response.get("error_code"));
      if (code != null && "3000000".equals(code.asText()))
        throw new PddPlatformException("PRODUCT_QUERY_REJECTED_3000000");
      throw new PddPlatformException("PRODUCT_QUERY_FAILED");
    }
    JsonNode result = response.path("result");
    JsonNode rows = result.get("onSaleGoods");
    if (rows == null || !rows.isArray())
      throw new PddPlatformException("UNRECOGNIZED_PRODUCT_LIST_SCHEMA");
    List<Map<String, Object>> products = new ArrayList<>();
    for (JsonNode goods : rows) {
      if (!goods.isObject()) continue;
      JsonNode minimum = goods.get("minOnSaleGroupPrice");
      JsonNode maximum = goods.get("maxOnSaleGroupPrice");
      String price = price(minimum);
      if (price != null && maximum != null && !maximum.isNull() && !maximum.equals(minimum)) {
        String max = price(maximum);
        if (max != null) price += "-" + max;
      }
      Map<String, Object> product = new LinkedHashMap<>();
      put(product, "goods_id", goods.get("goodsId"));
      product.put("goods_name", text(goods.get("goodsName")));
      String thumb = text(goods.get("thumbUrl"));
      product.put("thumb_url", thumb.isEmpty() ? thumb : imageUrl(thumb));
      product.put("price", price);
      put(product, "price_min", minimum);
      put(product, "price_max", maximum);
      put(product, "quantity", goods.get("quantity"));
      put(product, "sold_quantity", goods.get("soldQuantity"));
      put(product, "sold_quantity_30d", goods.get("soldQuantity30d"));
      product.put("goods_type", text(goods.get("goodsType")));
      product.put("is_spike", truthy(goods.get("isSpike")));
      product.put("support_customize", truthy(goods.get("supportCustomize")));
      product.put("goods_url", text(goods.get("goodsUrl")));
      product.put("tag", marketingTags(goods.path("goodsTag").path("marketingTags")));
      products.add(product);
    }
    JsonNode totalNode = result.get("total");
    Integer total = null;
    if (totalNode != null && !totalNode.isNull()) {
      if (!totalNode.canConvertToInt() || totalNode.asInt() < 0)
        throw new PddPlatformException("UNRECOGNIZED_PRODUCT_LIST_SCHEMA");
      total = totalNode.asInt();
    }
    return Map.of("products", products, "total", total == null ? -1 : total);
  }

  public Map<String, Object> sendText(String buyerId, String content) {
    long requestId = System.currentTimeMillis();
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("to", Map.of("role", "user", "uid", buyerId));
    message.put("from", Map.of("role", "mall_cs"));
    message.put("content", content);
    message.put("msg_id", null);
    message.put("type", 0);
    message.put("is_aut", 0);
    message.put("manual_reply", 1);
    JsonNode response = postJson(
        "/plateau/chat/send_message",
        Map.of("data", Map.of(
            "cmd", "send_message", "request_id", requestId, "message", message),
            "client", "WEB"),
        Map.of());
    JsonNode result = response.path("result");
    if (result.isObject() && result.path("error_code").asInt(-1) == 10002)
      return Map.of("state", "send_failed", "error_code", "PLATFORM_10002", "mode", "live");
    if (!response.path("success").asBoolean(false) || !result.isObject()
        || !(result.get("error_code") == null || result.get("error_code").isNull()
            || result.get("error_code").asInt(-1) == 0))
      return Map.of("state", "send_unknown", "error_code", "UNRECOGNIZED_PLATFORM_RECEIPT", "mode", "live");
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("state", "sent");
    receipt.put("mode", "live");
    receipt.put("receipt_basis", "success_flag_and_result_without_error");
    String id = messageId(result.get("msg_id"));
    if (id != null) receipt.put("platform_message_id", id);
    return receipt;
  }

  public Map<String, Object> sendProductCard(String buyerId, String goodsId) {
    if (goodsId == null || !goodsId.matches("[0-9]{1,20}"))
      return Map.of("state", "send_failed", "error_code", "CARD_PRODUCT_UNAVAILABLE", "mode", "live");
    JsonNode response = postJson(
        "/plateau/message/send/mallGoodsCard",
        Map.of("uid", buyerId, "goods_id", new BigInteger(goodsId), "biz_type", 3),
        Map.of());
    if (response.path("success").isBoolean() && !response.path("success").asBoolean())
      return Map.of("state", "send_failed", "error_code", "CARD_PLATFORM_REJECTED", "mode", "live");
    if (!response.path("success").asBoolean(false))
      return Map.of("state", "send_unknown", "error_code", "UNRECOGNIZED_CARD_RECEIPT", "mode", "live");
    JsonNode result = response.get("result");
    if (result != null && ((result.isBoolean() && !result.asBoolean())
        || (result.isObject() && result.has("error_code") && !result.get("error_code").isNull()
            && result.path("error_code").asInt(-1) != 0)))
      return Map.of("state", "send_failed", "error_code", "CARD_PLATFORM_REJECTED", "mode", "live");
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("state", "sent");
    receipt.put("mode", "live");
    receipt.put("receipt_basis", "card_endpoint_success");
    if (result != null && result.isObject()) {
      String id = messageId(result.get("msg_id"));
      if (id != null) receipt.put("platform_message_id", id);
    }
    return receipt;
  }

  private String messageId(JsonNode value) {
    if (value == null || value.isNull()) return null;
    String id = value.asText();
    return id.matches("[0-9]{1,200}") ? id : null;
  }

  private String marketingTags(JsonNode tags) {
    if (!tags.isArray()) return "";
    List<String> values = new ArrayList<>();
    tags.forEach(tag -> {
      if (tag.isTextual()) values.add(tag.asText());
    });
    return String.join(", ", values);
  }

  private String price(JsonNode cents) {
    if (cents == null || cents.isNull()) return null;
    try {
      return new BigDecimal(cents.asText()).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY)
          .toPlainString();
    } catch (Exception ignored) {
      throw new PddPlatformException("UNRECOGNIZED_PRODUCT_LIST_SCHEMA");
    }
  }

  private String imageUrl(String value) {
    if (value == null || value.length() > 4000) return "";
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      boolean allowed = host != null && java.util.Set.of("pddpic.com", "pinduoduo.com", "yangkeduo.com")
          .stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
      return "https".equals(uri.getScheme()) && allowed && uri.getUserInfo() == null && uri.getPort() == -1
          ? value : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private JsonNode first(JsonNode... values) {
    for (JsonNode value : values) if (value != null && !value.isNull()) return value;
    return null;
  }

  private String text(JsonNode value) {
    return value == null || value.isNull() ? "" : value.asText();
  }

  private void put(Map<String, Object> target, String key, JsonNode value) {
    target.put(key, value == null || value.isNull() ? null : json.convertValue(value, Object.class));
  }

  private boolean truthy(JsonNode value) {
    if (value == null || value.isNull()) return false;
    if (value.isBoolean()) return value.asBoolean();
    if (value.isNumber()) return value.asDouble() != 0;
    if (value.isTextual()) return !value.asText().isEmpty();
    return !value.isEmpty();
  }

  private JsonNode post(String path, String body, Map<String, String> extraHeaders) {
    if (!path.startsWith("/") || path.startsWith("//") || path.contains(".."))
      throw new IllegalArgumentException("INVALID_PLATFORM_PATH");
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(Duration.ofSeconds(15))
          .header("User-Agent", USER_AGENT)
          .header("Content-Type", "application/json")
          .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
          .header("Cookie", cookieHeader())
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      extraHeaders.forEach((name, value) -> {
        if (!name.matches("[A-Za-z0-9-]{1,80}") || value.contains("\r") || value.contains("\n"))
          throw new IllegalArgumentException("INVALID_PLATFORM_HEADER");
        request.setHeader(name, value);
      });
      HttpResponse<String> response = http.send(
          request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new PddPlatformException("PLATFORM_HTTP_" + response.statusCode());
      JsonNode result = json.readTree(response.body());
      if (result == null || !result.isObject())
        throw new PddPlatformException("INVALID_PLATFORM_RESPONSE");
      if (result.path("success").isBoolean() && !result.path("success").asBoolean()
          && result.path("error_code").asInt(-1) == 43001)
        throw new PddPlatformException("PLATFORM_SESSION_EXPIRED");
      return result;
    } catch (PddPlatformException error) {
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PddPlatformException("PLATFORM_REQUEST_INTERRUPTED");
    } catch (Exception error) {
      throw new PddPlatformException("PLATFORM_REQUEST_FAILED");
    }
  }

  private String cookieHeader() {
    StringJoiner header = new StringJoiner("; ");
    cookies.forEach((name, value) -> {
      if (!name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,128}")
          || value.contains("\r") || value.contains("\n") || value.contains(";"))
        throw new PddPlatformException("PDD_CREDENTIALS_INVALID");
      header.add(name + "=" + value);
    });
    return header.toString();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
