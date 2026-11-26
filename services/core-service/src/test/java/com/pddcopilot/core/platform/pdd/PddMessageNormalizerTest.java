package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PddMessageNormalizerTest {
  private final ObjectMapper json = new ObjectMapper();
  private final PddMessageNormalizer normalizer = new PddMessageNormalizer(json);

  @Test
  void normalizesSubtypeOrderCardIdAndImage() throws Exception {
    var event = normalizer.normalize(json.readTree("""
        {"response":"push","message":{"msg_id":101,"type":0,"sub_type":1,
        "from":{"role":"user","uid":"buyer-1"},
        "info":{"orderSequenceNo":12345678901234567890,"goodsID":789,
        "goodsName":"订单商品","goodsThumbUrl":"https://img.pddpic.com/order.jpg"}}}
        """), "shop-1", "platform-shop-1", "account-1");

    assertNotNull(event.message());
    assertEquals("order_info", event.message().messageType());
    var content = json.readTree(event.message().content());
    assertEquals("12345678901234567890", content.path("order_id").asText());
    assertEquals("789", content.path("goods_id").asText());
    assertEquals("https://img.pddpic.com/order.jpg", content.path("goods_thumb_url").asText());
    assertEquals("https://img.pddpic.com/order.jpg", event.message().media().imageUrl());
  }

  @Test
  void normalizesNestedOrderCardImageAndOrderId() throws Exception {
    var event = normalizer.normalize(json.readTree("""
        {"response":"push","message":{"msg_id":102,"type":8,
        "from":{"role":"user","uid":"buyer-1"},
        "info":{"goods_info":{"order_sequence_no":12345678901234567890,
        "goods_id":789,"goods_name":"嵌套订单商品",
        "goods_thumb_url":"https://img.pddpic.com/nested-order.jpg"}}}}
        """), "shop-1", "platform-shop-1", "account-1");

    assertNotNull(event.message());
    assertEquals("order_info", event.message().messageType());
    var content = json.readTree(event.message().content());
    assertEquals("12345678901234567890", content.path("order_id").asText());
    assertEquals("https://img.pddpic.com/nested-order.jpg", event.message().media().imageUrl());
  }
}
