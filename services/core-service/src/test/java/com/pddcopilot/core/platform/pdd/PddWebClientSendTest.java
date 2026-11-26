package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PddWebClientSendTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void textSendBuildsObservedPayloadAndAcceptsOnlyRecognizedReceipt() throws Exception {
    var body = new AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/plateau/chat/send_message", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "{\"success\":true,\"result\":{\"error_code\":0,\"msg_id\":12345}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var client = new PddWebClient(json, Map.of("session", "fixture"),
          "http://127.0.0.1:" + server.getAddress().getPort());
      var receipt = client.sendText("buyer-fixture", "合成测试回复");
      var payload = json.readTree(body.get());
      assertEquals("send_message", payload.path("data").path("cmd").asText());
      assertEquals("buyer-fixture", payload.path("data").path("message").path("to").path("uid").asText());
      assertEquals("mall_cs", payload.path("data").path("message").path("from").path("role").asText());
      assertEquals("合成测试回复", payload.path("data").path("message").path("content").asText());
      assertEquals(1, payload.path("data").path("message").path("manual_reply").asInt());
      assertEquals("sent", receipt.get("state"));
      assertEquals("12345", receipt.get("platform_message_id"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void productCardSendUsesObservedGoodsCardPayloadAndReceipt() throws Exception {
    var body = new AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/plateau/message/send/mallGoodsCard", exchange -> {
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "{\"success\":true,\"result\":{\"error_code\":0,\"msg_id\":67890}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var client = new PddWebClient(json, Map.of("session", "fixture"),
          "http://127.0.0.1:" + server.getAddress().getPort());
      var receipt = client.sendProductCard("buyer-fixture", "12345678901234567890");
      var payload = json.readTree(body.get());
      assertEquals("buyer-fixture", payload.path("uid").asText());
      assertEquals("12345678901234567890", payload.path("goods_id").asText());
      assertEquals(3, payload.path("biz_type").asInt());
      assertEquals("sent", receipt.get("state"));
      assertEquals("67890", receipt.get("platform_message_id"));
      assertEquals("send_failed", client.sendProductCard("buyer-fixture", "not-a-platform-id").get("state"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void shopProfileUsesOnlyPublicMerchantIdentityFields() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/janus/api/new/userinfo", exchange -> {
      byte[] response = "{\"success\":true,\"result\":{\"id\":\"account-fixture\"}}"
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.createContext("/earth/api/merchant/queryMerchantInfoByMallId", exchange -> {
      byte[] response = ("{\"success\":true,\"result\":{\"mallId\":\"shop-fixture\","
          + "\"mallName\":\"测试店铺\",\"mallLogo\":\"https://img.pddpic.com/logo.png\","
          + "\"mallCategoryStr\":\"纸品\",\"operatorIdCard\":\"private\"}}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var client = new PddWebClient(json, Map.of("session", "fixture"),
          "http://127.0.0.1:" + server.getAddress().getPort());
      var profile = client.shopProfile();
      assertEquals("shop-fixture", profile.platformShopId());
      assertEquals("account-fixture", profile.connectorAccountId());
      assertEquals("测试店铺", profile.name());
      assertEquals("https://img.pddpic.com/logo.png", profile.avatarUrl());
      assertEquals("纸品", profile.mainCategory());
    } finally {
      server.stop(0);
    }
  }
}
