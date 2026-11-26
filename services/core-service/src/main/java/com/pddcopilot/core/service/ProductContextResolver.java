package com.pddcopilot.core.service;

import com.pddcopilot.core.repository.AgentRunRepository;
import com.pddcopilot.core.repository.BusinessStore;
import java.util.*;
import org.springframework.stereotype.Component;

/** Resolve a question's own card, its explicitly linked source card, then prior context. */
@Component
public class ProductContextResolver {
  private final BusinessStore store;
  private final AgentRunRepository repository;

  public ProductContextResolver(BusinessStore store, AgentRunRepository repository) {
    this.store = store;
    this.repository = repository;
  }

  public Map<String, Object> resolve(
      Map<String, Object> message, Object conversationId, Object cutoff) {
    var own = product(message);
    if (!own.isEmpty()) return own;
    var linked =
        repository.linkedProductSources(conversationId, message.get("platform_message_id"), cutoff);
    var products = new LinkedHashMap<String, Map<String, Object>>();
    for (var source : linked) {
      var product = product(source);
      if (!product.isEmpty()) products.putIfAbsent(product.get("goods_id").toString(), product);
    }
    if (products.size() > 1) return Map.of("ambiguous", true);
    if (!products.isEmpty()) return products.values().iterator().next();
    for (var source : repository.priorProductSources(conversationId, message.get("created_at"))) {
      var product = product(source);
      if (!product.isEmpty()) return product;
    }
    return Map.of();
  }

  private Map<String, Object> product(Map<String, Object> source) {
    if (!(source.get("media_json") instanceof String raw)) return orderProduct(source);
    Object value = store.fromJson(raw);
    if (!(value instanceof Map<?, ?> media)
        || !"product".equals(media.get("kind"))
        || !(media.get("goods_id") instanceof String goods)
        || goods.isBlank()) return orderProduct(source);
    return Map.of(
        "goods_id",
        goods,
        "title",
        Objects.toString(media.get("title"), ""),
        "source_message_id",
        source.get("id"));
  }

  private Map<String, Object> orderProduct(Map<String, Object> source) {
    if (!"order_info".equals(source.get("message_type"))
        || !(source.get("content") instanceof String raw)) return Map.of();
    try {
      if (!(store.fromJson(raw) instanceof Map<?, ?> card)) return Map.of();
      Object value = card.get("goods_id");
      if (!(value instanceof String || value instanceof Number)) return Map.of();
      String goods = value.toString();
      if (!goods.matches("[0-9]{1,200}")) return Map.of();
      return Map.of(
          "goods_id", goods,
          "title", card.get("goods_name") instanceof String title ? title : "",
          "source_message_id", source.get("id"));
    } catch (RuntimeException ignored) {
      return Map.of();
    }
  }
}
