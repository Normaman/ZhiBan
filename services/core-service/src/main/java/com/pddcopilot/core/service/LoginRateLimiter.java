package com.pddcopilot.core.service;

import com.pddcopilot.core.support.SecurityDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Bounded attempt windows, atomically enforced across replicas. */
@Service
public class LoginRateLimiter {
  private final StringRedisTemplate redis;
  private final SecurityDigest digest;
  private final boolean memory;
  private final Map<String,long[]> local = new HashMap<>();
  private static final int[] LIMITS = {60,20,5};
  private static final int[] WINDOWS = {300,900,60};
  private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
      "for i,k in ipairs(KEYS) do if tonumber(redis.call('GET',k) or '0') >= tonumber(ARGV[i*2-1]) then return 0 end end; "
      + "for i,k in ipairs(KEYS) do local n=redis.call('INCR',k); if n==1 then redis.call('EXPIRE',k,ARGV[i*2]) end end; return 1",Long.class);

  public LoginRateLimiter(StringRedisTemplate redis, SecurityDigest digest,
      @Value("${copilot.session-store:redis}") String store) {
    this.redis=redis; this.digest=digest; memory="memory".equals(store);
  }

  public void check(String username,String address) {
    var keys=List.of("copilot:auth:ip:"+digest.hash(address),
        "copilot:auth:account:"+digest.hash(username),
        "copilot:auth:pair:"+digest.hash(username+"\n"+address));
    boolean allowed;
    if (memory) allowed=localAcquire(keys);
    else {
      try { allowed=Long.valueOf(1).equals(redis.execute(ACQUIRE,keys,"60","300","20","900","5","60")); }
      catch (RuntimeException error) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_SECURITY_UNAVAILABLE");
      }
    }
    if (!allowed) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"LOGIN_RATE_LIMITED");
  }

  private synchronized boolean localAcquire(List<String> keys) {
    long now=System.currentTimeMillis();
    local.entrySet().removeIf(e -> e.getValue()[1]<=now);
    if (local.size()>10000) return false;
    for (int i=0;i<keys.size();i++) {
      var value=local.get(keys.get(i));
      if (value!=null && value[0]>=LIMITS[i]) return false;
    }
    for (int i=0;i<keys.size();i++) {
      int seconds=WINDOWS[i];
      local.computeIfAbsent(keys.get(i),k -> new long[]{0,now+seconds*1000L})[0]++;
    }
    return true;
  }
}
