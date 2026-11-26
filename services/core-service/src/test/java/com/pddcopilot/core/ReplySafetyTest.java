package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;
import com.pddcopilot.core.support.ReplySafety;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReplySafetyTest {
  @Test
  void solicitingAndReviewIncentivesAreBlockedIncludingObfuscation() {
    for (String text : new String[] {"加我微信私下转账", "好 评返现五元", "给五星评价送红包", "好\u200b评返现"}) {
      assertThrows(ResponseStatusException.class, () -> ReplySafety.validate(text, true));
      assertThrows(ResponseStatusException.class, () -> ReplySafety.validate(text, false));
    }
  }

  @Test
  void agentApprovalCannotClaimAnOrderActionButPolicyGuidanceIsAllowed() {
    assertThrows(ResponseStatusException.class, () -> ReplySafety.validate("我这边给你同意退款", true));
    assertDoesNotThrow(() -> ReplySafety.validate("符合条件时可在订单页申请售后，工作人员会核实处理。", true));
    assertDoesNotThrow(() -> ReplySafety.validate("本店不支持好评返现。", true));
  }
}
