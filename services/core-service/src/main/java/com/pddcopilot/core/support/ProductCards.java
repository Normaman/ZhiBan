package com.pddcopilot.core.support;

import static com.pddcopilot.core.support.WorkflowValues.conflict;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

/** Validate platform-derived previews; clients select IDs, never replace their data. */
public final class ProductCards {
  private static final Set<String> FIELDS =
      Set.of("goods_id", "title", "image_url", "price", "fetched_at");

  public static List<Map<String, Object>> validate(Object value) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> rows) || rows.size() > 3)
      throw conflict("INVALID_PRODUCT_CARDS");
    var cards = new ArrayList<Map<String, Object>>();
    var ids = new HashSet<String>();
    for (var row : rows) {
      if (!(row instanceof Map<?, ?> raw) || !FIELDS.containsAll(raw.keySet()))
        throw conflict("INVALID_PRODUCT_CARDS");
      String id = text(raw.get("goods_id"), 200),
          title = text(raw.get("title"), 1000),
          fetched = text(raw.get("fetched_at"), 100);
      if (!ids.add(id)) throw conflict("INVALID_PRODUCT_CARDS");
      try {
        OffsetDateTime.parse(fetched);
      } catch (RuntimeException e) {
        throw conflict("INVALID_PRODUCT_CARDS");
      }
      var card = new LinkedHashMap<String, Object>();
      card.put("goods_id", id);
      card.put("title", title);
      card.put("fetched_at", fetched);
      Object image = raw.get("image_url"), price = raw.get("price");
      if (image != null) {
        try {
          URI uri = URI.create(text(image, 4000));
          String host = uri.getHost();
          if (!"https".equals(uri.getScheme())
              || host == null
              || uri.getUserInfo() != null
              || uri.getPort() != -1
              || List.of("pddpic.com", "pinduoduo.com", "yangkeduo.com").stream()
                  .noneMatch(h -> host.equals(h) || host.endsWith("." + h)))
            throw new IllegalArgumentException();
        } catch (RuntimeException e) {
          throw conflict("INVALID_PRODUCT_CARDS");
        }
      }
      card.put("image_url", image);
      card.put("price", price == null ? null : text(price, 100));
      cards.add(card);
    }
    return cards;
  }

  private static String text(Object value, int max) {
    if (!(value instanceof String text) || text.isBlank() || text.length() > max)
      throw conflict("INVALID_PRODUCT_CARDS");
    return text;
  }

  public static List<Map<String, Object>> select(
      List<Map<String, Object>> cards, List<String> ids) {
    if (ids == null) return List.of();
    if (ids.size() > 3 || new HashSet<>(ids).size() != ids.size())
      throw conflict("INVALID_PRODUCT_CARDS");
    var selected = new ArrayList<Map<String, Object>>();
    for (String id : ids)
      selected.add(
          cards.stream()
              .filter(c -> id != null && id.equals(c.get("goods_id")))
              .findFirst()
              .orElseThrow(() -> conflict("PRODUCT_CARD_NOT_IN_DRAFT")));
    return selected;
  }
}
