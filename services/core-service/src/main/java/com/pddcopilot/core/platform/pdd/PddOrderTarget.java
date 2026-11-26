package com.pddcopilot.core.platform.pdd;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Restricts the public URL entry point to known merchant order pages. */
record PddOrderTarget(String url, String orderId, int orderType, int page) {
  boolean detail() { return orderId != null; }

  static PddOrderTarget parse(String url) {
    try {
      URI uri = URI.create(url);
      if (!"https".equals(uri.getScheme()) || !"mms.pinduoduo.com".equals(uri.getHost())
          || uri.getUserInfo() != null || uri.getFragment() != null
          || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
      Map<String, String> query = new HashMap<>();
      if (uri.getRawQuery() != null) {
        for (String pair : uri.getRawQuery().split("&")) {
          String[] parts = pair.split("=", 2);
          String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
          String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
          if (query.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
        }
      }
      if ("/orders/detail".equals(uri.getPath())) {
        String order = query.getOrDefault("sn", "");
        if (!query.keySet().equals(Set.of("sn")) || order.isBlank() || order.length() > 200)
          throw new IllegalArgumentException();
        return new PddOrderTarget(url, order, 0, 1);
      }
      if (!"/orders/list".equals(uri.getPath())
          || !Set.of("msfrom", "type", "orderType", "page").containsAll(query.keySet()))
        throw new IllegalArgumentException();
      // Only the observed merchant route type=1 -> API orderType=2 is supported.
      if (query.containsKey("type") && (!"1".equals(query.get("type")) || query.containsKey("orderType")))
        throw new IllegalArgumentException();
      int type = Integer.parseInt(query.getOrDefault("orderType", "2"));
      int page = Integer.parseInt(query.getOrDefault("page", "1"));
      if (!Set.of(1, 2, 7).contains(type) || page < 1 || page > 100)
        throw new IllegalArgumentException();
      return list(type, page);
    } catch (Exception invalid) {
      throw new PddPlatformException("ORDER_QUERY_URL_INVALID");
    }
  }

  static PddOrderTarget list(int type, int page) {
    String url = PddWebClient.BASE_URL + "/orders/list?msfrom=mms_sidenav" + (type == 2 ? "&type=1" : "");
    return new PddOrderTarget(url, null, type, page);
  }
}
