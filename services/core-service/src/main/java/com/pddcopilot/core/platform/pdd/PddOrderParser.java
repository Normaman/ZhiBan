package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Whitelist parser for the observed order list and order detail responses. */
@Component
public class PddOrderParser {
  public Map<String, Object> list(JsonNode response) {
    JsonNode result = response.path("result");
    if (!response.path("success").asBoolean(false)
        || response.path("errorCode").asInt(-1) != 0 || !result.isObject()) {
      int code = response.path("errorCode").asInt(response.path("error_code").asInt(-1));
      throw new PddPlatformException(code == 40002
          ? "ORDER_LIST_QUERY_REJECTED" : "ORDER_LIST_QUERY_FAILED");
    }
    JsonNode totalNode = result.get("totalItemNum");
    JsonNode itemsNode = result.get("pageItems");
    if (totalNode == null || !totalNode.canConvertToInt() || totalNode.asInt() < 0
        || itemsNode == null || !itemsNode.isArray())
      throw new PddPlatformException("ORDER_LIST_SCHEMA_UNSUPPORTED");

    List<Map<String, Object>> orders = new ArrayList<>();
    for (JsonNode item : itemsNode) {
      if (!item.isObject()) throw new PddPlatformException("ORDER_LIST_SCHEMA_UNSUPPORTED");
      Map<String, Object> order = new LinkedHashMap<>();
      order.put("order_ref", maskedOrderRef(scalar(item.get("order_sn"))));
      order.put("goods_name", text(item.get("goods_name"), 300));
      order.put("spec", text(item.get("spec"), 200));
      order.put("quantity", positiveInt(item.get("goods_number")));
      order.put("status_text", text(item.get("order_status_str"), 100));
      order.put("order_status", classify(text(item.get("order_status_str"), 100)));
      order.put("order_time", timestamp(item.get("order_time")));
      order.put("amount", yuan(item.get("order_amount")));
      order.put("thumbnail", safeThumbnail(text(item.get("thumb_url"), 4000)));
      orders.add(order);
    }
    return Map.of("total", totalNode.asInt(), "orders", orders);
  }

  public Map<String, Object> basic(JsonNode response, String orderId, String buyerRef) {
    JsonNode value = response.path("result");
    if (!response.path("success").asBoolean(false) || !value.isObject())
      throw new PddPlatformException("ORDER_QUERY_UNAVAILABLE");
    if (!orderId.equals(scalar(value.get("orderSn")))
        || !buyerRef.equals(scalar(value.get("uid"))))
      throw new PddPlatformException("ORDER_BUYER_MISMATCH");
    Integer quantity = positiveInt(value.get("goodsNumber"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("order_id", orderId);
    result.put("goods_name", text(value.get("goodsName"), 1000));
    result.put("spec", text(value.get("spec"), 1000));
    result.put("purchased_quantity", quantity);
    result.put("order_status", "unknown");
    result.put("status_text", null);
    result.put("missing_fields", new ArrayList<>(List.of(
        "order_status", "created_at", "collected", "logistics_status", "signed",
        "freight_insurance", "goods_category")));
    return result;
  }

  public Map<String, Object> merge(Map<String, Object> order, JsonNode response) {
    JsonNode value = response.path("result");
    if (!response.path("success").asBoolean(false) || !value.isObject()) {
      if (response.path("error_code").asInt(-1) == 54001
          && value.path("verifyAuthToken").isTextual()
          && !value.path("verifyAuthToken").asText().isBlank())
        throw new PddPlatformException("ORDER_QUERY_VERIFICATION_REQUIRED");
      if (response.path("error_code").asInt(-1) == 54001)
        throw new PddPlatformException("ORDER_QUERY_RATE_LIMITED");
      if (response.path("error_code").asInt(-1) == 43001)
        throw new PddPlatformException("ORDER_SESSION_EXPIRED");
      if (response.path("errorCode").asInt(-1) == 4002)
        throw new PddPlatformException("ORDER_DETAIL_NOT_FOUND");
      throw new PddPlatformException("ORDER_STATUS_UNAVAILABLE");
    }
    if (!order.get("order_id").equals(scalar(value.get("order_sn"))))
      throw new PddPlatformException("ORDER_DETAIL_SCOPE_MISMATCH");
    Object rawLabel = value.get("order_status_str");
    String label = rawLabel instanceof JsonNode node && node.isTextual() ? node.asText() : null;
    String status = classify(label);
    Map<String, Object> result = new LinkedHashMap<>(order);
    result.put("order_status", status);
    result.put("status_text", label == null ? null : label.substring(0, Math.min(100, label.length())));
    @SuppressWarnings("unchecked")
    List<String> missing = new ArrayList<>((List<String>) order.get("missing_fields"));
    if (!"unknown".equals(status)) missing.remove("order_status");
    JsonNode created = value.get("order_time");
    if (created != null && created.canConvertToLong() && created.asLong() > 0) {
      try {
        result.put("created_at", Instant.ofEpochSecond(created.asLong()).atOffset(ZoneOffset.UTC).toString());
        missing.remove("created_at");
      } catch (Exception ignored) {
        // An unrecognized timestamp remains explicitly missing.
      }
    }
    result.put("missing_fields", missing);
    return result;
  }

  public String classify(String label) {
    if (label == null) return "unknown";
    String text = label.strip();
    if (text.startsWith("已取消")) return "cancelled";
    return switch (text) {
      case "已关闭", "交易关闭" -> "closed";
      case "已下单未付款", "已下单，未付款", "已下单,未付款", "待买家付款", "待支付", "待付款" -> "unpaid";
      case "已付款", "支付成功" -> "paid";
      case "已付款，待发货", "已付款,待发货", "已支付，待发货", "已支付,待发货", "待发货" -> "awaiting_shipment";
      case "待成团" -> "awaiting_group";
      case "已发货", "已发货，待签收", "已发货，待收货", "已发货,待收货" -> "shipped";
      case "已签收" -> "signed";
      case "交易成功", "已完成" -> "completed";
      default -> "unknown";
    };
  }

  private Integer positiveInt(JsonNode value) {
    return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
  }

  private String scalar(JsonNode value) {
    return value == null || value.isNull() ? "" : value.asText();
  }

  private String text(JsonNode value, int limit) {
    if (value == null || !value.isTextual()) return null;
    String result = value.asText();
    return result.substring(0, Math.min(result.length(), limit));
  }

  private String maskedOrderRef(String value) {
    if (value.isBlank()) return null;
    return "••••" + value.substring(Math.max(0, value.length() - 4));
  }

  private String timestamp(JsonNode value) {
    if (value == null || !value.canConvertToLong() || value.asLong() <= 0) return null;
    try { return Instant.ofEpochSecond(value.asLong()).atOffset(ZoneOffset.UTC).toString(); }
    catch (Exception ignored) { return null; }
  }

  private String yuan(JsonNode value) {
    if (value == null || !value.isNumber() || value.asDouble() < 0) return null;
    try {
      return new BigDecimal(value.asText()).divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY)
          .toPlainString();
    } catch (Exception ignored) { return null; }
  }

  private String safeThumbnail(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      var uri = java.net.URI.create(value);
      String host = uri.getHost();
      if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
          && (host.equals("pddpic.com") || host.endsWith(".pddpic.com"))) return value;
    } catch (Exception ignored) { }
    return null;
  }
}
