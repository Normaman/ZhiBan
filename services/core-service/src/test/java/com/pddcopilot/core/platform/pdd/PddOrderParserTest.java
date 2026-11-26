package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PddOrderParserTest {
  private final ObjectMapper json = new ObjectMapper();
  private final PddOrderParser parser = new PddOrderParser();

  Map<String, Object> basic() throws Exception {
    return parser.basic(json.readTree("""
        {"success":true,"result":{"orderSn":"fixture-order","uid":"fixture-buyer",
          "goodsName":"测试商品","goodsNumber":1}}
        """), "fixture-order", "fixture-buyer");
  }

  @Test
  void observedFrequentOperationResponseIsNotAnExpiredSession() throws Exception {
    var response = json.readTree("""
        {"result":null,"error_code":54001,"error_msg":"操作太过频繁，请稍后再试！"}
        """);
    var error = assertThrows(PddPlatformException.class, () -> parser.merge(basic(), response));
    assertEquals("ORDER_QUERY_RATE_LIMITED", error.code());
  }

  @Test
  void observedVerificationChallengeIsNotTreatedAsAnExpiredLoginOrOrdinaryRateLimit() throws Exception {
    var response = json.readTree("""
        {"result":{"verifyAuthToken":"synthetic-challenge"},"error_code":54001,
          "error_msg":"操作太过频繁，请稍后再试！"}
        """);
    assertEquals("ORDER_QUERY_VERIFICATION_REQUIRED", assertThrows(PddPlatformException.class,
        () -> parser.merge(basic(), response)).code());
  }

  @Test
  void actualAuthenticationFailureRemainsDifferentFromUnavailableStatus() throws Exception {
    assertEquals("ORDER_SESSION_EXPIRED", assertThrows(PddPlatformException.class,
        () -> parser.merge(basic(), json.readTree("{\"error_code\":43001}"))).code());
    assertEquals("ORDER_STATUS_UNAVAILABLE", assertThrows(PddPlatformException.class,
        () -> parser.merge(basic(), json.readTree("{\"error_code\":999}"))).code());
  }

  @Test
  void statusMustBelongToTheVerifiedOrderAndHaveARecognizedLabel() throws Exception {
    var order = parser.merge(basic(), json.readTree("""
        {"success":true,"result":{"order_sn":"fixture-order","order_status_str":"已取消",
          "order_time":1700000000}}
        """));
    assertEquals("cancelled", order.get("order_status"));
    assertFalse(((java.util.List<?>) order.get("missing_fields")).contains("order_status"));
    assertEquals("ORDER_DETAIL_SCOPE_MISMATCH", assertThrows(PddPlatformException.class,
        () -> parser.merge(basic(), json.readTree("""
          {"success":true,"result":{"order_sn":"another-order","order_status_str":"已发货"}}
          """))).code());
    assertEquals("unknown", parser.merge(basic(), json.readTree("""
        {"success":true,"result":{"order_sn":"fixture-order","order_status_str":"未识别的新状态"}}
        """)).get("order_status"));
    assertEquals("ORDER_BUYER_MISMATCH", assertThrows(PddPlatformException.class,
        () -> parser.basic(json.readTree("""
          {"success":true,"result":{"orderSn":"fixture-order","uid":"other-buyer"}}
          """), "fixture-order", "fixture-buyer")).code());
  }
}
