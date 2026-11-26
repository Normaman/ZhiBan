package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pddcopilot.core.service.LoginRateLimiter;
import com.pddcopilot.core.support.ClientAddress;
import com.pddcopilot.core.support.SecurityDigest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class LoginSecurityPolicyTest {
  @Test void accountLimitsSurviveChangingSourceAndSourceLimitsSurviveChangingAccount() {
    var limiter=new LoginRateLimiter(null,new SecurityDigest("isolated-policy-test-token"),"memory");
    for (int i=0;i<20;i++) limiter.check("same-account","source-"+i);
    assertEquals(429,assertThrows(ResponseStatusException.class,() -> limiter.check("same-account","another-source")).getStatusCode().value());
    for (int i=0;i<60;i++) limiter.check("different-"+i,"same-source");
    assertEquals(429,assertThrows(ResponseStatusException.class,() -> limiter.check("next-account","same-source")).getStatusCode().value());
  }

  @Test void redisFailureCannotFallBackToUnrestrictedAuthentication() {
    var redis=mock(StringRedisTemplate.class);
    doThrow(new IllegalStateException("isolated-redis-failure")).when(redis).execute(any(),anyList(),any(Object[].class));
    var limiter=new LoginRateLimiter(redis,new SecurityDigest("isolated-policy-test-token"),"redis");
    assertEquals(503,assertThrows(ResponseStatusException.class,() -> limiter.check("account","address")).getStatusCode().value());
  }

  @Test void forwardedAddressesAreIgnoredFromUntrustedPeers() {
    var request=new MockHttpServletRequest();request.setRemoteAddr("198.51.100.10");
    request.addHeader("X-Forwarded-For","203.0.113.99");
    assertEquals("198.51.100.10",new ClientAddress("10.0.0.0/8").resolve(request));
  }

  @Test void proxyChainStopsAtActualUntrustedClientAndRejectsHostnames() {
    var resolver=new ClientAddress("10.0.0.0/8");
    var request=new MockHttpServletRequest();request.setRemoteAddr("10.0.0.2");
    request.addHeader("X-Forwarded-For","203.0.113.99,198.51.100.10,10.0.0.3");
    assertEquals("198.51.100.10",resolver.resolve(request));
    request=new MockHttpServletRequest();request.setRemoteAddr("10.0.0.2");
    request.addHeader("X-Forwarded-For","attacker.example");
    assertEquals("10.0.0.2",resolver.resolve(request));
  }
}
