package com.pddcopilot.core.platform.pdd;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Both channels return only the existing parser's whitelisted business fields. */
public record OrderResult(String channel, String kind, Map<String, Object> data) {
  public OrderResult {
    data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}
