package com.pddcopilot.core.platform.pdd;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class PddOrderQueryThrottleTest {
  @Test
  void cooldownIsShopScopedAndExpiresWithoutSlidingOnReads() {
    Clock clock = mock(Clock.class);
    when(clock.millis()).thenReturn(1000L);
    var guard = new PddOrderQueryThrottle(60, clock);
    guard.limited("shop-a");
    assertEquals(60, guard.retryAfterSeconds("shop-a"));
    assertEquals(0, guard.retryAfterSeconds("shop-b"));
    when(clock.millis()).thenReturn(60500L);
    assertEquals(1, guard.retryAfterSeconds("shop-a"));
    when(clock.millis()).thenReturn(61000L);
    assertEquals(0, guard.retryAfterSeconds("shop-a"));
    when(clock.millis()).thenReturn(62000L);
    guard.limited("shop-a");
    assertEquals(60, guard.retryAfterSeconds("shop-a"));
  }
}
