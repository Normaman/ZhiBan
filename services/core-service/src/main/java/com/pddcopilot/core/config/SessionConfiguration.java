package com.pddcopilot.core.config;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionConfiguration {
  @Bean CookieSerializer sessionCookies(@Value("${server.servlet.session.cookie.secure:false}") boolean secure,
      @Value("${PUBLIC_DOMAIN:}") String domain) {
    if (!domain.isBlank() && !secure) throw new IllegalArgumentException("Public deployment requires SESSION_COOKIE_SECURE=true");
    var cookie = new DefaultCookieSerializer();
    cookie.setCookieName("PDD_SESSION");
    cookie.setCookiePath("/");
    cookie.setUseHttpOnlyCookie(true);
    cookie.setUseSecureCookie(secure);
    cookie.setSameSite("Lax");
    cookie.setCookieMaxAge(12*60*60);
    return cookie;
  }

  @Configuration
  @ConditionalOnProperty(name="copilot.session-store",havingValue="redis",matchIfMissing=true)
  @EnableRedisIndexedHttpSession(maxInactiveIntervalInSeconds=1800,redisNamespace="${copilot.session-namespace:copilot:security:sessions:v1}")
  static class SharedSessions {}

  /** Explicit opt-in for isolated tests and local development; never an outage fallback. */
  @Configuration
  @ConditionalOnProperty(name="copilot.session-store",havingValue="memory")
  @EnableSpringHttpSession
  static class LocalSessions {
    @Bean LocalSessionRepository sessionRepository() {
      var repository = new LocalSessionRepository(new ConcurrentHashMap<>());
      repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
      return repository;
    }
  }

  public static class LocalSessionRepository extends MapSessionRepository
      implements FindByIndexNameSessionRepository<MapSession> {
    private final java.util.Map<String,Session> records;
    LocalSessionRepository(java.util.Map<String,Session> records) { super(records); this.records=records; }
    @Override public java.util.Map<String,MapSession> findByIndexNameAndIndexValue(String index,String value) {
      var found=new java.util.HashMap<String,MapSession>();
      if (!PRINCIPAL_NAME_INDEX_NAME.equals(index)) return found;
      for (var id:records.keySet()) {
        var session=findById(id);
        if (session!=null && value.equals(session.getAttribute(index))) found.put(id,session);
      }
      return found;
    }
  }
}
