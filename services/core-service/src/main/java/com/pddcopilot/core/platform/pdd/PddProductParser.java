package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Whitelist parser ported from the existing observed product/detail schemas. */
@Component
public class PddProductParser {
  private static final List<String> PROVINCES = List.of(
      "北京", "安徽", "福建", "甘肃", "广东", "广西", "贵州", "海南", "河北", "河南",
      "黑龙江", "湖北", "湖南", "吉林", "江苏", "江西", "辽宁", "内蒙古", "宁夏", "青海",
      "山东", "山西", "陕西", "上海", "四川", "天津", "西藏", "新疆", "云南", "浙江",
      "重庆", "香港", "澳门", "台湾");

  public Map<String, Object> detail(JsonNode payload, String goodsId, String shopId) {
    JsonNode detail = payload.path("result");
    if (!payload.path("success").asBoolean(false) || !detail.isObject())
      throw new PddPlatformException("PRODUCT_DETAIL_UNAVAILABLE");
    if (!goodsId.equals(scalar(detail.get("goods_id")))
        || !shopId.equals(scalar(detail.get("mall_id"))))
      throw new PddPlatformException("PRODUCT_DETAIL_SCOPE_MISMATCH");
    JsonNode nativeSkus = detail.get("skus");
    if (nativeSkus == null || nativeSkus.isNull())
      nativeSkus = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    if (!nativeSkus.isArray()) throw new PddPlatformException("PRODUCT_DETAIL_SCHEMA_UNSUPPORTED");
    List<Map<String, Object>> skus = new ArrayList<>();
    for (int i = 0; i < Math.min(500, nativeSkus.size()); i++) {
      JsonNode sku = nativeSkus.get(i);
      if (!sku.isObject()) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sku_id", scalar(sku.get("sku_id")));
      List<Map<String, String>> specs = new ArrayList<>();
      JsonNode nativeSpecs = sku.path("spec");
      for (int j = 0; nativeSpecs.isArray() && j < Math.min(20, nativeSpecs.size()); j++) {
        JsonNode spec = nativeSpecs.get(j);
        if (spec.isObject()) specs.add(Map.of(
            "name", text(spec.get("parent_name"), 100),
            "value", text(spec.get("spec_name"), 500)));
      }
      row.put("specs", specs);
      row.put("group_price_cents", positiveInt(sku.get("multi_price")));
      row.put("single_price_cents", positiveInt(sku.get("price")));
      row.put("on_sale", booleanFlag(sku.get("is_onsale")));
      row.put("quantity", nonnegativeInt(sku.get("quantity")));
      row.put("presale_deadline", presaleDeadline(sku.get("pre_sale_time")));
      List<Integer> shipmentLimits = new ArrayList<>();
      JsonNode limits = sku.path("shipment_limit_second_list");
      for (int j = 0; limits.isArray() && j < Math.min(20, limits.size()); j++) {
        Integer seconds = positiveInt(limits.get(j));
        if (seconds != null) shipmentLimits.add(seconds);
      }
      row.put("shipment_limit_seconds", shipmentLimits);
      row.put("thumb_url", imageUrl(scalar(sku.get("thumb_url"))));
      skus.add(row);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("detail_schema_version", 3);
    result.put("shipping", shipping(detail));
    String freightId = positiveIdentifier(detail.get("cost_template_id"));
    result.put("freight_template_id", freightId);
    result.put("goods_id", goodsId);
    result.put("goods_name", text(detail.get("goods_name"), 1000));
    result.put("category_id", scalar(detail.get("cat_id")));
    result.put("category_path", stringList(detail.path("cats"), 10, 200));
    result.put("description", text(detail.get("goods_desc"), 10000));
    result.put("goods_type", text(detail.get("goods_type_name"), 100));
    result.put("images", images(detail.path("carousel_gallery")));
    result.put("detail_images", images(detail.path("detail_gallery")));
    result.put("sku_count", nativeSkus.size());
    result.put("skus", skus);
    result.put("skus_truncated", nativeSkus.size() > 500);
    result.put("freight", unavailableFreight(freightId == null ? null : freightId.toString()));
    return result;
  }

  public List<Map<String, Object>> attributes(JsonNode payload, String goodsId) {
    JsonNode result = payload.path("result");
    if (!payload.path("success").asBoolean(false) || !result.isObject()
        || !goodsId.equals(scalar(result.get("goodsId"))) || !result.path("properties").isArray())
      throw new PddPlatformException("PRODUCT_ATTRIBUTES_UNAVAILABLE");
    List<Map<String, Object>> attributes = new ArrayList<>();
    JsonNode properties = result.path("properties");
    for (int i = 0; i < Math.min(100, properties.size()); i++) {
      JsonNode property = properties.get(i);
      if (!property.isObject() || !property.path("name").isTextual()
          || !property.path("values").isArray()) continue;
      List<String> values = new ArrayList<>();
      JsonNode nativeValues = property.path("values");
      for (int j = 0; j < Math.min(30, nativeValues.size()); j++)
        if (nativeValues.get(j).isTextual()) values.add(text(nativeValues.get(j), 1000));
      attributes.add(Map.of("name", text(property.get("name"), 200), "values", values));
    }
    return attributes;
  }

  public Map<String, Object> freight(JsonNode payload, String templateId) {
    JsonNode data = payload.path("result");
    if (!payload.path("success").asBoolean(false) || !data.isObject()
        || !templateId.equals(scalar(data.get("costTemplateId"))))
      throw new PddPlatformException("PRODUCT_FREIGHT_UNAVAILABLE");
    Map<String, Object> result = unavailableFreight(templateId);
    result.put("status", "unsupported");
    JsonNode config = data.get("regionCostTemplateConfig");
    Integer costType = integer(data.get("costType"));
    if ((config != null && !config.isNull()) || costType == null || !Set.of(0, 1).contains(costType))
      return result;
    JsonNode free = data.path("dispatchFree");
    if (!free.isObject()) return result;
    try {
      List<String> freeRegions = regions(free.path("areaList"), true);
      List<String> undeliverable = regions(data.path("nondeliveryAreas"), false);
      JsonNode nativeRules = data.path("dispatchCost");
      if (!nativeRules.isArray() || nativeRules.size() > 100) return result;
      List<Map<String, Object>> rules = new ArrayList<>();
      for (JsonNode row : nativeRules) {
        if (!row.isObject()) return result;
        Integer firstUnits = nonnegativeInt(row.get("firstStandard"));
        Integer firstFee = nonnegativeInt(row.get("firstCost"));
        Integer addUnits = nonnegativeInt(row.get("addStandard"));
        Integer addFee = nonnegativeInt(row.get("addCost"));
        if (firstUnits == null || firstUnits == 0 || firstFee == null || addUnits == null
            || addUnits == 0 || addFee == null || !row.path("hasMinCount").isBoolean()
            || !row.path("hasMinAmount").isBoolean()) return result;
        Integer minCount = row.path("hasMinCount").asBoolean()
            ? positiveInt(row.get("minCountForDeliveryFree")) : null;
        Integer minAmount = row.path("hasMinAmount").asBoolean()
            ? positiveInt(row.get("minAmountForDeliveryFree")) : null;
        if ((row.path("hasMinCount").asBoolean() && minCount == null)
            || (row.path("hasMinAmount").asBoolean() && minAmount == null)) return result;
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("regions", regions(row.path("areaList"), true));
        rule.put("unit", costType == 0 ? "piece" : "gram");
        rule.put("first_units", firstUnits);
        rule.put("first_fee_cents", firstFee);
        rule.put("additional_units", addUnits);
        rule.put("additional_fee_cents", addFee);
        rule.put("free_min_count", minCount);
        rule.put("free_min_amount_cents", minAmount);
        rules.add(rule);
      }
      result.put("status", "synced");
      result.put("free_regions", freeRegions);
      result.put("undeliverable_regions", undeliverable);
      result.put("paid_rules", rules);
      return result;
    } catch (PddPlatformException unsupported) {
      if ("FREIGHT_REGIONS_UNSUPPORTED".equals(unsupported.code())) return result;
      throw unsupported;
    }
  }

  private Map<String, Object> shipping(JsonNode detail) {
    Integer limit = nonnegativeInt(detail.get("shipment_limit_second"));
    String mode = "unknown";
    String summary = "发货设置未取得";
    String deadline = null;
    if (flag(detail.get("is_sku_pre_sale")) || flag(detail.get("is_sku_shipment"))
        || flag(detail.get("is_sku_group_pre_sale"))) {
      mode = "by_sku";
      summary = "按规格设置发货或预售时间，需核对具体规格";
    } else if (flag(detail.get("is_group_pre_sale")) && limit != null && limit > 0) {
      mode = "relative_presale";
      double days = limit / 86400.0;
      summary = "时段预售，支付成功后"
          + (days == Math.rint(days) ? Long.toString((long) days) : Double.toString(days))
          + "天内发货及揽收";
    } else if (flag(detail.get("is_pre_sale"))) {
      mode = "scheduled_presale";
      deadline = presaleDeadline(detail.get("pre_sale_time"));
      summary = deadline == null ? "定时预售，发货日期尚未取得" : "定时预售，" + deadline + "前发货及揽收";
    } else if (detail.has("is_pre_sale") && detail.get("is_pre_sale").asInt(-1) == 0) {
      mode = "regular";
      summary = "非预售；" + duration(limit, flag(detail.get("delivery_one_day")));
      if (summary.equals("非预售；")) summary = "非预售；承诺发货时间未取得";
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("mode", mode);
    result.put("summary", summary);
    result.put("shipment_limit_seconds", limit);
    result.put("presale_deadline", deadline);
    return result;
  }

  private String duration(Integer seconds, boolean sameDay) {
    if (seconds == null || seconds <= 0) return "";
    if (seconds == 86400 && sameDay) return "当日发货及揽收";
    double amount = seconds <= 172800 ? seconds / 3600.0 : seconds / 86400.0;
    String unit = seconds <= 172800 ? "小时" : "天";
    String number = amount == Math.rint(amount) ? Long.toString((long) amount) : Double.toString(amount);
    return number + unit + "内发货及揽收";
  }

  private String presaleDeadline(JsonNode value) {
    Long seconds = longInteger(value);
    if (seconds == null || seconds <= 0) return null;
    try {
      return Instant.ofEpochSecond(seconds).atZone(ZoneId.of("Asia/Shanghai"))
          .withHour(23).withMinute(59).withSecond(59).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (Exception ignored) {
      return null;
    }
  }

  private List<String> images(JsonNode entries) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (int i = 0; entries.isArray() && i < Math.min(40, entries.size()); i++) {
      String url = imageUrl(scalar(entries.get(i).get("url")));
      if (url != null) result.add(url);
    }
    return new ArrayList<>(result);
  }

  private List<String> stringList(JsonNode values, int limit, int maxLength) {
    List<String> result = new ArrayList<>();
    for (int i = 0; values.isArray() && i < Math.min(limit, values.size()); i++) {
      JsonNode value = values.get(i);
      if (value.isTextual() && !value.asText().isBlank()) result.add(text(value, maxLength));
    }
    return result;
  }

  private Map<String, Object> unavailableFreight(String templateId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "unavailable");
    result.put("template_id", templateId);
    result.put("free_regions", new ArrayList<>());
    result.put("paid_rules", new ArrayList<>());
    result.put("undeliverable_regions", new ArrayList<>());
    return result;
  }

  private List<String> regions(JsonNode values, boolean objects) {
    if (!values.isArray() || values.size() > 100) throw new PddPlatformException("FREIGHT_REGIONS_UNSUPPORTED");
    List<Integer> ids = new ArrayList<>();
    for (JsonNode value : values) {
      if (objects && !value.isObject())
        throw new PddPlatformException("FREIGHT_REGIONS_UNSUPPORTED");
      JsonNode id = objects && value.isObject() ? value.get("province") : value;
      Integer number = integer(id);
      if (number == null) throw new PddPlatformException("FREIGHT_REGIONS_UNSUPPORTED");
      ids.add(number);
    }
    if (ids.size() == 1 && ids.get(0) == 0) return List.of();
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (Integer id : ids) {
      int index = id - 2;
      if (index < 0 || index >= PROVINCES.size())
        throw new PddPlatformException("FREIGHT_REGIONS_UNSUPPORTED");
      result.add(PROVINCES.get(index));
    }
    return new ArrayList<>(result);
  }

  private Boolean booleanFlag(JsonNode value) {
    Integer number = integer(value);
    return number != null && (number == 0 || number == 1) ? number == 1 : null;
  }

  private boolean flag(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.asLong() == 1;
  }

  private Integer positiveInt(JsonNode value) {
    Integer number = integer(value);
    return number != null && number > 0 ? number : null;
  }

  private String positiveIdentifier(JsonNode value) {
    if (value == null || value.isNull()) return null;
    String raw;
    if (value.isIntegralNumber()) raw = value.bigIntegerValue().toString();
    else if (value.isTextual()) raw = value.asText();
    else return null;
    if (!raw.matches("[0-9]{1,30}")) return null;
    BigInteger identifier = new BigInteger(raw);
    return identifier.signum() > 0 ? identifier.toString() : null;
  }

  private Integer nonnegativeInt(JsonNode value) {
    Integer number = integer(value);
    return number != null && number >= 0 ? number : null;
  }

  private Integer integer(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : null;
  }

  private Long longInteger(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
  }

  private String text(JsonNode value, int limit) {
    if (value == null || !value.isTextual()) return "";
    String text = value.asText();
    return text.substring(0, Math.min(limit, text.length()));
  }

  private String scalar(JsonNode value) {
    return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
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
}
