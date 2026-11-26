package com.pddcopilot.core;

import com.pddcopilot.core.service.AccountSessions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

/** Same HTTP scenarios against the production Redis-backed session and attempt limiter. */
@EnabledIfEnvironmentVariable(named="AUTH_TEST_REDIS_HOST",matches=".+")
@TestPropertySource(properties={
    "copilot.session-store=redis",
    "copilot.internal-token=${AUTH_TEST_INTERNAL_TOKEN:isolated-redis-security-test-token-32}",
    "copilot.session-namespace=copilot:security:integration-test",
    "spring.data.redis.host=${AUTH_TEST_REDIS_HOST}",
    "spring.data.redis.port=${AUTH_TEST_REDIS_PORT:6379}",
    "spring.data.redis.password=${AUTH_TEST_REDIS_PASSWORD:}",
    "spring.data.redis.database=15"})
class AccountSecurityRedisTest extends AccountSecurityWebTest {
  @org.junit.jupiter.api.Test
  void independentRepositoryCanRestoreAndRevokeSerializedAuthentication() {
    var browser=login();
    var original=(org.springframework.session.data.redis.RedisIndexedSessionRepository)sessions;
    var replica=new org.springframework.session.data.redis.RedisIndexedSessionRepository(original.getSessionRedisOperations());
    replica.setRedisKeyNamespace("copilot:security:integration-test");
    var found=replica.findByPrincipalName(username);
    org.junit.jupiter.api.Assertions.assertEquals(1,found.size());
    var restored=found.values().iterator().next();
    org.junit.jupiter.api.Assertions.assertNotNull(restored.getAttribute("SPRING_SECURITY_CONTEXT"));
    var security=(org.springframework.security.core.context.SecurityContext)restored.getAttribute("SPRING_SECURITY_CONTEXT");
    org.junit.jupiter.api.Assertions.assertNull(security.getAuthentication().getCredentials());
    org.junit.jupiter.api.Assertions.assertNull(((org.springframework.security.core.userdetails.UserDetails)security.getAuthentication().getPrincipal()).getPassword());
    org.junit.jupiter.api.Assertions.assertEquals(username,restored.getAttribute(AccountSessions.ACCOUNT));
    replica.deleteById(restored.getId());
    org.junit.jupiter.api.Assertions.assertEquals(401,read(browser));
  }
}
