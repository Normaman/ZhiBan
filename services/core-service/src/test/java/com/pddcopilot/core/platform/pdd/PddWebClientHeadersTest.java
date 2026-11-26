package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PddWebClientHeadersTest {
  @Test
  void capturedBrowserHeadersReplaceDefaultsRatherThanCreateConflictingFingerprints() throws Exception {
    var userAgents = new AtomicReference<List<String>>();
    var contentTypes = new AtomicReference<List<String>>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/fixture", exchange -> {
      userAgents.set(exchange.getRequestHeaders().get("User-Agent"));
      contentTypes.set(exchange.getRequestHeaders().get("Content-Type"));
      byte[] response = "{\"success\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var client = new PddWebClient(new ObjectMapper(), Map.of("fixture", "value"),
          "http://127.0.0.1:" + server.getAddress().getPort());
      client.postJson("/fixture", Map.of(), Map.of(
          "user-agent", "CapturedBrowser/1", "content-type", "application/json;charset=UTF-8"));
      assertEquals(List.of("CapturedBrowser/1"), userAgents.get());
      assertEquals(List.of("application/json;charset=UTF-8"), contentTypes.get());
    } finally {
      server.stop(0);
    }
  }
}
