package com.pddcopilot.core.support;

import com.pddcopilot.core.repository.BusinessStore;
import java.util.*;

/** Compare observed list fields; local fetch timestamps and ordering are not product changes. */
public final class ProductFingerprint {
  public static Object stable(Object value) {
    if (value instanceof Map<?, ?> map) {
      var out = new TreeMap<String, Object>();
      map.forEach(
          (key, item) -> {
            if (!Set.of("fetched_at", "mode").contains(key.toString()))
              out.put(key.toString(), stable(item));
          });
      return out;
    }
    if (value instanceof List<?> items)
      return items.stream().map(ProductFingerprint::stable).toList();
    return value;
  }

  public static String hash(BusinessStore store, Object value) {
    return WorkflowValues.sha256(store.toJson(stable(value)));
  }

  public static Object catalog(List<?> items) {
    return items.stream()
        .map(ProductFingerprint::stable)
        .sorted(
            Comparator.comparing(item -> Objects.toString(((Map<?, ?>) item).get("goods_id"), "")))
        .toList();
  }
}
