package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Treat 40002 as an observed business rejection, without guessing its cause. */
final class PddOrderResponseCodes {
  static final String REJECTED = "ORDER_BROWSER_REJECTED_40002";
  private static final List<String> FIELDS = List.of("errorCode", "error_code", "code");

  static boolean rejected(JsonNode body) {
    return FIELDS.stream().anyMatch(field -> "40002".equals(body.path(field).asText()));
  }

  static String summary(JsonNode body) {
    StringBuilder result = new StringBuilder();
    for (String field : FIELDS) {
      JsonNode value = body.get(field);
      if (value == null) continue;
      String text = value.asText();
      // Platform strings may contain private data; log numeric codes only.
      if (!text.matches("-?[0-9]{1,10}")) continue;
      if (!result.isEmpty()) result.append(',');
      result.append(field).append('=').append(text);
    }
    return result.isEmpty() ? "missing" : result.toString();
  }

  static boolean orderEndpoint(String url) {
    return listEndpoint(url) || PddBrowserSessionService.ORDER_PATHS.stream().anyMatch(
        path -> PddBrowserSessionService.isEndpoint(url, "mms.pinduoduo.com", path));
  }

  static boolean listEndpoint(String url) {
    return PddBrowserSessionService.isEndpoint(url, "mms.pinduoduo.com",
        "/mangkhut/mms/recentOrderList");
  }

  private PddOrderResponseCodes() {}
}
