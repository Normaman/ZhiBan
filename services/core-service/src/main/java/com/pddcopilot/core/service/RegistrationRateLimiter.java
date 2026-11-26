package com.pddcopilot.core.service;

import com.pddcopilot.core.support.WorkflowValues;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Redis limits registration attempts; it never decides whether an approved reply was sent. */
@Service
public class RegistrationRateLimiter {
  private static final int LIMIT = 10;
  private final boolean redisEnabled;
  private final StringRedisTemplate redis;
  private final ConcurrentHashMap<String, AtomicInteger> local = new ConcurrentHashMap<>();
  private static final DefaultRedisScript<Long> INCREMENT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],65) end;"
              + " return n",
          Long.class);

  public RegistrationRateLimiter(
      StringRedisTemplate redis, @Value("${copilot.redis-enabled}") boolean enabled) {
    this.redis = redis;
    this.redisEnabled = enabled;
  }

  public void check(String address) {
    String key =
        "copilot:registration:"
            + WorkflowValues.sha256(address)
            + ":"
            + (System.currentTimeMillis() / 60000);
    long count;
    if (redisEnabled) {
      try {
        count = redis.execute(INCREMENT, List.of(key));
      } catch (Exception error) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_LIMITER_UNAVAILABLE");
      }
    } else {
      if (local.size() > 1000) local.clear();
      count = local.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }
    if (count > LIMIT)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "REGISTRATION_RATE_LIMITED");
  }
}
