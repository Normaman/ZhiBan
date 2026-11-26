package com.pddcopilot.core.service;

import com.pddcopilot.core.repository.AgentRunRepository;
import com.pddcopilot.core.repository.BusinessStore;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** An order reference is a lookup hint, never proof of ownership or order status. */
@Component
public class OrderContextResolver {
  private static final Pattern ORDER_NUMBER = Pattern.compile("(?<![0-9])[0-9]{6,20}-[0-9]{6,30}(?![0-9])");
  private final AgentRunRepository repository;
  private final BusinessStore store;

  public OrderContextResolver(AgentRunRepository repository, BusinessStore store) {
    this.repository = repository;
    this.store = store;
  }

  public Map<String, Object> resolve(Object conversation, Object timestamp) {
    for (var message : repository.priorOrderSources(conversation, timestamp)) {
      String reference = null;
      if ("order_info".equals(message.get("message_type"))) {
        try {
          Object parsed = store.fromJson(message.get("content").toString());
          if (parsed instanceof Map<?,?> card) {
            Object id = card.get("order_id");
            if (id instanceof String text) reference = text;
            else if (id instanceof Number number) reference = number.toString();
          }
        } catch (RuntimeException ignored) { }
      } else {
        var matcher = ORDER_NUMBER.matcher(message.get("content").toString());
        if (matcher.find()) {
          reference = matcher.group();
          if (matcher.find()) return Map.of("ambiguous", true);
        }
      }
      if (reference != null && !reference.isBlank() && reference.length() <= 200)
        return Map.of("order_id", reference, "source_message_id", message.get("id"));
    }
    return Map.of();
  }
}
