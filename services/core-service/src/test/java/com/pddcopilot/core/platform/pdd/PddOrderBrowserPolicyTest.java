package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.options.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PddOrderBrowserPolicyTest {
  @Test
  void restartingBrowserRestoresMissingSessionCookiesWithoutReplacingCurrentVerificationState() {
    var saved = Map.of("session", "restored-session", "verification", "saved-verification");
    var existing = List.of(new Cookie("verification", "current-verification")
        .setUrl("https://mms.pinduoduo.com"));
    var restored = PddBrowserSessionService.cookiesToRestore(saved, existing, true);
    assertEquals(1, restored.size());
    assertEquals("session", restored.get(0).name);
    assertEquals("restored-session", restored.get(0).value);
    assertEquals(2, PddBrowserSessionService.cookiesToRestore(saved, existing, false).size());
  }

  @Test
  void pageAuthenticationCanInitializeButOrderMutationsRemainBlocked() {
    String host = "https://mms.pinduoduo.com";
    assertTrue(PddBrowserSessionService.allowOrderRequest("POST", host + "/janus/api/checkLogin"));
    assertTrue(PddBrowserSessionService.allowOrderRequest("POST", host + "/space/user/login"));
    assertTrue(PddBrowserSessionService.allowOrderRequest("POST", host + "/api/phantom/vc_pre_ck_b"));
    assertTrue(PddBrowserSessionService.allowOrderRequest("POST", host + "/mangkhut/mms/orderDetail"));
    assertTrue(PddBrowserSessionService.allowOrderRequest("POST", host + "/mangkhut/mms/routeOrderDetail"));
    assertFalse(PddBrowserSessionService.allowOrderRequest("POST", host + "/plateau/chat/send_message"));
    assertFalse(PddBrowserSessionService.allowOrderRequest("POST", host + "/orders/cancel"));
    assertFalse(PddBrowserSessionService.allowOrderRequest("POST", host + "/orders/refund"));
    assertFalse(PddBrowserSessionService.allowOrderRequest("POST", "https://mms.pinduoduo.com.evil.test/mangkhut/mms/orderDetail"));
    assertFalse(PddBrowserSessionService.allowOrderRequest("DELETE", host + "/mangkhut/mms/orderDetail"));
  }
}
