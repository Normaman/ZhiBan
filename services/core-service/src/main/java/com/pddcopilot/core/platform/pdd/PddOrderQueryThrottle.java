package com.pddcopilot.core.platform.pdd;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shop-scoped cooldown after an observed platform order-query rate limit. */
@Component
public class PddOrderQueryThrottle {
  private record Pause(long deadline, String code) {}
  private final ConcurrentHashMap<String, Pause> deadlines = new ConcurrentHashMap<>();
  private final long cooldownMillis;
  private final Clock clock;

  @Autowired
  public PddOrderQueryThrottle(
      @Value("${PDD_ORDER_QUERY_COOLDOWN_SECONDS:60}") long seconds) {
    this(seconds, Clock.systemUTC());
  }

  PddOrderQueryThrottle(long seconds, Clock clock) {
    if (seconds < 1 || seconds > 3600)
      throw new IllegalArgumentException("Order query cooldown must be between 1 and 3600 seconds");
    this.cooldownMillis = seconds * 1000;
    this.clock = clock;
  }

  public void limited(String shopId) {
    pause(shopId, "ORDER_QUERY_RATE_LIMITED");
  }

  public void pause(String shopId, String code) {
    if (!java.util.Set.of("ORDER_QUERY_RATE_LIMITED", "ORDER_QUERY_VERIFICATION_REQUIRED").contains(code))
      throw new IllegalArgumentException("Invalid order query pause reason");
    deadlines.put(shopId, new Pause(clock.millis() + cooldownMillis, code));
  }

  public String code(String shopId) {
    Pause pause = deadlines.get(shopId);
    return pause == null ? "ORDER_QUERY_RATE_LIMITED" : pause.code();
  }

  public void clear(String shopId) {
    deadlines.remove(shopId);
  }

  public long retryAfterSeconds(String shopId) {
    Pause pause = deadlines.get(shopId);
    if (pause == null) return 0;
    long remaining = pause.deadline() - clock.millis();
    if (remaining <= 0) {
      deadlines.remove(shopId, pause);
      return 0;
    }
    return (remaining + 999) / 1000;
  }
}
