package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.api.dto.Contracts;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Converts the currently observed WebSocket envelopes into the existing Core contracts. */
@Component
public class PddMessageNormalizer {
  public record Event(Contracts.Inbound message, Contracts.ReadReceipt receipt, boolean sessionChanged) {}

  private final ObjectMapper json;

  public PddMessageNormalizer(ObjectMapper json) {
    this.json = json;
  }

  public Event normalize(
      JsonNode raw, String shopId, String platformShopId, String accountId) {
    String response = text(raw, "response");
    JsonNode message = raw.path("message");
    if ("system_push".equals(response) || number(message.path("type"), -1) == 30)
      return new Event(null, null, true);
    if ("mall_system_msg".equals(response) && number(message.path("type"), -1) == 20)
      return new Event(null, readReceipt(message, shopId, platformShopId, accountId), false);
    if (!"push".equals(response)) return new Event(null, null, false);

    String role = text(message.path("from"), "role");
    String origin = origin(role);
    int type = number(message.path("type"), -1);
    int subtype = number(message.path("sub_type"), -1);
    String kind = kind(type, subtype);
    String action = action(origin, type, subtype);
    if (Set.of("ignore", "observe").contains(action)) return new Event(null, null, false);

    String buyer = "merchant".equals(origin)
        ? text(message.path("to"), "uid") : text(message.path("from"), "uid");
    if (buyer == null || buyer.isBlank()) return new Event(null, null, false);
    JsonNode contentNode = content(message, type, subtype);
    String content = contentNode == null || contentNode.isNull()
        ? "" : contentNode.isContainerNode() ? contentNode.toString() : contentNode.asText();
    String direction = "merchant".equals(origin) ? "outbound"
        : "customer".equals(origin) ? "inbound" : "system";
    if ("merchant".equals(origin) && Set.of(1, 5, 14).contains(type)
        || "merchant".equals(origin) && type == 0 && subtype != 2)
      kind = "mall_cs";

    String platformMessageId = scalar(message.get("msg_id"));
    OffsetDateTime occurredAt = timestamp(message);
    Contracts.Media media = media(message, type, kind, contentNode);
    return new Event(
        new Contracts.Inbound(
            UUID.randomUUID().toString(), shopId, platformShopId, accountId,
            platformMessageId, buyer, direction, kind, content, occurredAt,
            OffsetDateTime.now(ZoneOffset.UTC), action, media),
        null,
        false);
  }

  private Contracts.ReadReceipt readReceipt(
      JsonNode message, String shop, String platformShop, String account) {
    JsonNode data = message.path("data");
    String buyer = scalar(data.get("user_id"));
    String minimum = scalar(data.get("min_supported_msg_id"));
    String last = scalar(data.get("user_last_read"));
    if ("".equals(last)) last = null;
    if (buyer == null || buyer.isBlank() || buyer.length() > 200
        || minimum == null || !minimum.matches("[0-9]{1,200}")
        || (last != null && !last.matches("[0-9]{1,200}"))) return null;
    return new Contracts.ReadReceipt(
        shop, platformShop, account, buyer, last, minimum, OffsetDateTime.now(ZoneOffset.UTC));
  }

  private JsonNode content(JsonNode message, int type, int subtype) {
    if (type == 0 && subtype == 1) {
      JsonNode info = message.path("info");
      Map<String, Object> result = new LinkedHashMap<>();
      putScalar(result, "order_id", info.get("orderSequenceNo"));
      put(result, "goods_id", info.get("goodsID"));
      put(result, "goods_name", info.get("goodsName"));
      put(result, "goods_thumb_url", first(info.get("goodsThumbUrl"), info.get("goods_thumb_url")));
      put(result, "afterSalesStatus", info.get("afterSalesStatus"));
      put(result, "afterSalesType", info.get("afterSalesType"));
      put(result, "spec", info.get("spec"));
      return json.valueToTree(result);
    }
    if (type == 0 && subtype == 0) return object(message.path("info"),
        "goods_id", "goodsID", "goods_name", "goodsName", "goods_price", "goodsPrice",
        "goods_thumb_url", "goodsThumbUrl", "link_url", "linkUrl");
    if (type == 0 && subtype == 2) return content(message, 0, 0);
    if (type == 8) {
      JsonNode goods = message.path("info").path("goods_info");
      Map<String, Object> result = new LinkedHashMap<>();
      putScalar(result, "order_id", goods.get("order_sequence_no"));
      put(result, "goods_id", goods.get("goods_id"));
      put(result, "goods_name", goods.get("goods_name"));
      put(result, "goods_thumb_url", first(goods.get("goods_thumb_url"), goods.get("goodsThumbUrl")));
      put(result, "spec", first(goods.get("spec"), goods.get("goods_spec")));
      put(result, "title", message.path("info").get("title"));
      put(result, "text", message.path("info").get("text"));
      return json.valueToTree(result);
    }
    if (type == 5) return message.path("info").get("description");
    if (type == 1002) return message.path("info").get("withdraw_hint");
    if (type == 64) {
      JsonNode data = message.path("info").path("data");
      JsonNode nested = data.path("goods_info");
      Map<String, Object> result = new LinkedHashMap<>();
      put(result, "goods_id", first(data.get("goodsID"), nested.get("goods_id")));
      put(result, "goods_name", first(data.get("goodsName"), nested.get("goods_name")));
      put(result, "goods_price", first(data.get("goodsPrice"), nested.get("goods_price")));
      put(result, "goods_spec", first(data.get("spec"), data.get("specStr"), nested.get("spec")));
      put(result, "title", first(data.get("title"), message.get("content")));
      put(result, "sub_title", data.get("sub_title"));
      put(result, "template_name", message.get("template_name"));
      if (result.keySet().stream().noneMatch(Set.of("goods_id", "goods_name", "goods_price", "goods_spec")::contains))
        put(result, "raw_info", message.get("info"));
      return json.valueToTree(result);
    }
    if (type == 24) {
      JsonNode info = message.path("info");
      Map<String, Object> result = new LinkedHashMap<>();
      put(result, "from_uid", message.path("from").get("uid"));
      put(result, "to_uid", message.path("to").get("uid"));
      put(result, "description", message.get("content"));
      put(result, "origin_cs_id", info.get("origin_id"));
      put(result, "target_cs_id", info.get("target_id"));
      put(result, "new_csid", info.get("new_csid"));
      return json.valueToTree(result);
    }
    if (type == 41) return message.path("content");
    return message.get("content");
  }

  private Contracts.Media media(JsonNode message, int type, String kind, JsonNode content) {
    String url = null;
    String mediaKind = null;
    String goodsId = null;
    String title = null;
    String relatedId = null;
    if (type == 1) {
      url = imageUrl(content == null ? null : content.asText(null));
      mediaKind = "image";
    } else {
      JsonNode info = message.path("info");
      JsonNode goods = type == 8 ? info.path("goods_info") : type == 41 ? info.path("goods_info") : content;
      if (type == 8 && !goods.isObject()) goods = info;
      if (goods != null && goods.isObject()
          && Set.of("goods_card", "goods_inquiry", "goods_spec", "order_info", "system_hint").contains(kind)) {
        goodsId = scalar(first(goods.get("goods_id"), goods.get("goodsID")));
        title = scalar(first(goods.get("goods_name"), goods.get("goodsName")));
        url = imageUrl(scalar(first(goods.get("goods_thumb_url"), goods.get("goodsThumbUrl"))));
        mediaKind = "product";
        if (type == 41 || ("goods_inquiry".equals(kind) && message.get("pre_msg_id") != null)) {
          String candidate = scalar(message.get("pre_msg_id"));
          if (candidate != null && candidate.matches("[0-9]{1,200}")) relatedId = candidate;
        }
      }
    }
    if (mediaKind == null || (url == null && goodsId == null && title == null)) return null;
    return new Contracts.Media(mediaKind, url, goodsId,
        title != null && title.length() > 1000 ? title.substring(0, 1000) : title, relatedId);
  }

  private String action(String origin, int type, int subtype) {
    if ("customer".equals(origin)) {
      if (type == 0) return subtype == 2 ? "context_only" : "reply";
      if (Set.of(1, 5, 14, 64).contains(type)) return "reply";
      if (type == 8 || type == 41) return "context_only";
      if (type == 24) return "handoff";
      if (Set.of(20, 30, 31, 97).contains(type)) return "observe";
      if (Set.of(56, 1002).contains(type)) return "ignore";
      return "unknown";
    }
    if ("merchant".equals(origin)) {
      if (type == 0 && subtype == 2) return "context_only";
      if (Set.of(0, 8, 64).contains(type)) return "context_only";
      if (Set.of(56, 1002).contains(type)) return "ignore";
      return "observe";
    }
    if ("system".equals(origin)) return Set.of(56, 1002).contains(type) ? "ignore" : "observe";
    return "unknown";
  }

  private String kind(int type, int subtype) {
    if (type == 0 && subtype == 1) return "order_info";
    if (type == 0 && subtype == 0) return "goods_inquiry";
    if (type == 0 && subtype == 2) return "goods_card";
    return switch (type) {
      case 0 -> "text";
      case 1 -> "image";
      case 5 -> "emotion";
      case 8 -> "order_info";
      case 14 -> "video";
      case 20, 30, 97 -> "system_status";
      case 24 -> "transfer";
      case 31, 41, 56 -> "system_hint";
      case 64 -> "goods_spec";
      case 1002 -> "withdraw";
      default -> "system_status";
    };
  }

  private String origin(String role) {
    if ("user".equalsIgnoreCase(role)) return "customer";
    if ("mall_cs".equalsIgnoreCase(role)) return "merchant";
    if (Set.of("system", "mall").contains(role == null ? "" : role.toLowerCase())) return "system";
    return "unknown";
  }

  private OffsetDateTime timestamp(JsonNode message) {
    String raw = scalar(first(message.get("ts"), message.get("time")));
    if (raw == null) return null;
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0)
        value = value.movePointLeft(3);
      return OffsetDateTime.ofInstant(Instant.ofEpochMilli(value.multiply(BigDecimal.valueOf(1000)).longValue()), ZoneOffset.UTC);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String imageUrl(String value) {
    if (value == null || value.length() > 4000) return null;
    try {
      java.net.URI uri = java.net.URI.create(value);
      String host = uri.getHost();
      boolean allowed = host != null && Set.of("pddpic.com", "pinduoduo.com", "yangkeduo.com")
          .stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
      return "https".equals(uri.getScheme()) && allowed && uri.getUserInfo() == null && uri.getPort() == -1
          ? value : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  private JsonNode object(JsonNode source, String... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) put(result, pairs[i], source.get(pairs[i + 1]));
    return json.valueToTree(result);
  }

  private void put(Map<String, Object> target, String key, JsonNode value) {
    if (value != null && !value.isNull()) target.put(key, json.convertValue(value, Object.class));
  }

  private void putScalar(Map<String, Object> target, String key, JsonNode value) {
    String scalar = scalar(value);
    if (scalar != null && !scalar.isBlank()) target.put(key, scalar);
  }

  private JsonNode first(JsonNode... values) {
    for (JsonNode value : values) if (value != null && !value.isNull() && !value.asText("").isBlank()) return value;
    return null;
  }

  private String scalar(JsonNode value) {
    if (value == null || value.isNull() || value.isContainerNode()) return null;
    return value.asText();
  }

  private String text(JsonNode node, String field) {
    return scalar(node == null ? null : node.get(field));
  }

  private int number(JsonNode node, int fallback) {
    return node != null && node.canConvertToInt() ? node.asInt() : fallback;
  }
}
