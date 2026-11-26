package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pddcopilot.core.api.*;
import com.pddcopilot.core.config.Security;
import com.pddcopilot.core.config.SessionConfiguration;
import com.pddcopilot.core.domain.UserAccount;
import com.pddcopilot.core.repository.*;
import com.pddcopilot.core.service.*;
import com.pddcopilot.core.support.SecurityDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.*;

/** Exercises the real HTTP filter chain, password verification, CSRF and session repository. */
@SpringBootTest(classes=AccountSecurityWebTest.TestApp.class,
    webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
      "spring.datasource.url=jdbc:h2:mem:account-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa","spring.datasource.password=","spring.flyway.enabled=false",
      "management.endpoints.web.exposure.include=health,prometheus",
      "management.prometheus.metrics.export.enabled=true",
      "copilot.internal-token=isolated-security-test-token-with-32-characters",
      "METRICS_TOKEN=isolated-metrics-test-token-with-more-than-32-characters",
      "copilot.session-store=memory","copilot.redis-enabled=false","copilot.shop-id=security-shop"})
class AccountSecurityWebTest {
  @Configuration @EnableAutoConfiguration
  @Import({Security.class,SessionConfiguration.class,com.pddcopilot.core.config.MetricsSecurity.class,AuthController.class,GlobalExceptionHandler.class,
      UserRepository.class,AccountSessions.class,LoginRateLimiter.class,AuthenticationAudit.class,
      RegistrationRateLimiter.class,SecurityDigest.class,com.pddcopilot.core.support.ClientAddress.class,ProtectedApi.class})
  static class TestApp {}
  @RestController static class ProtectedApi {
    @GetMapping("/api/v1/protected") Object read() { return Map.of("ok",true); }
    @PostMapping("/api/v1/admin/model-config") Object write() { return Map.of("saved",true); }
  }
  @MockitoBean UserService users;
  @MockitoBean WorkspaceRepository workspaces;
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired UserRepository accounts;
  @Autowired PasswordEncoder encoder;
  @Autowired org.springframework.session.FindByIndexNameSessionRepository<? extends org.springframework.session.Session> sessions;
  @SuppressWarnings("unchecked")
  void save(org.springframework.session.Session session) {
    ((org.springframework.session.SessionRepository<org.springframework.session.Session>)sessions).save(session);
  }
  String username;
  static final String PASSWORD="isolated-test-password-123";

  @BeforeEach void setup() {
    jdbc.execute("CREATE TABLE IF NOT EXISTS app_users(id VARCHAR PRIMARY KEY,username VARCHAR UNIQUE,"
        + "password_hash VARCHAR,shop_id VARCHAR,role VARCHAR,enabled BOOLEAN,created_at BIGINT,auth_version BIGINT DEFAULT 0)");
    jdbc.execute("CREATE TABLE IF NOT EXISTS authentication_events(id VARCHAR PRIMARY KEY,event_type VARCHAR,"
        + "user_id VARCHAR,subject_hash VARCHAR,address_hash VARCHAR,created_at BIGINT)");
    username="u"+UUID.randomUUID().toString().replace("-","");
    accounts.insert(new UserAccount(username,username,encoder.encode(PASSWORD),"security-shop","ADMIN",true),0);
    when(workspaces.accessibleShops(anyString())).thenReturn(List.of(new HashMap<>(Map.of(
        "id","security-shop","workspace_id","workspace-one","member_role","OWNER"))));
  }
  record Browser(String cookie,Map csrf) {}
  Browser anonymous() {
    var result=http.getForEntity("/api/v1/auth/session",Map.class);
    assertEquals(200,result.getStatusCode().value());
    return new Browser(result.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";",2)[0],result.getBody());
  }
  HttpHeaders headers(Browser browser,boolean csrf) {
    var h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set(HttpHeaders.COOKIE,browser.cookie());
    if (csrf) h.set(browser.csrf().get("csrf_header").toString(),browser.csrf().get("csrf_token").toString());
    return h;
  }
  ResponseEntity<Map> post(String path,Browser browser,Object input) {
    return http.postForEntity(path,new HttpEntity<>(input,headers(browser,true)),Map.class);
  }
  Browser login() { return login(username,PASSWORD); }
  Browser login(String user,String password) {
    return login(anonymous(),user,password);
  }
  Browser login(Browser before,String user,String password) {
    var response=post("/api/v1/auth/login",before,Map.of("username",user,"password",password));
    assertEquals(200,response.getStatusCode().value());
    var cookie=response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";",2)[0];
    var result=http.exchange("/api/v1/auth/session",HttpMethod.GET,
        new HttpEntity<>(headers(new Browser(cookie,Map.of()),false)),Map.class);
    return new Browser(cookie,result.getBody());
  }
  int read(Browser browser) {
    return http.exchange("/api/v1/protected",HttpMethod.GET,new HttpEntity<>(headers(browser,false)),Map.class)
        .getStatusCode().value();
  }
  List<Map<String,Object>> devices(Browser browser) {
    var response=http.exchange("/api/v1/auth/sessions",HttpMethod.GET,new HttpEntity<>(headers(browser,false)),List.class);
    assertEquals(200,response.getStatusCode().value());
    return (List<Map<String,Object>>)response.getBody();
  }

  @Test void basicCannotAuthenticateOrBypassCsrfAndLoginRotatesSession() {
    assertEquals(401,http.withBasicAuth(username,PASSWORD).getForEntity("/api/v1/protected",String.class).getStatusCode().value());
    var before=anonymous();
    var missing=headers(before,false);missing.setBasicAuth(username,PASSWORD);
    assertEquals(403,http.postForEntity("/api/v1/auth/login",new HttpEntity<>(
        Map.of("username",username,"password",PASSWORD),missing),Map.class).getStatusCode().value());
    var browser=login(before,username,PASSWORD);assertNotEquals(before.cookie(),browser.cookie());assertEquals(200,read(browser));
    assertEquals(401,read(before));
  }

  @Test void failedAttemptsAreLimitedAndNeverRecordSecrets() {
    var browser=anonymous();
    for (int i=0;i<5;i++) assertEquals(401,post("/api/v1/auth/login",browser,
        Map.of("username",username,"password","never-log-this-password")).getStatusCode().value());
    assertEquals(429,post("/api/v1/auth/login",browser,
        Map.of("username",username,"password",PASSWORD)).getStatusCode().value());
    var events=jdbc.queryForList("SELECT * FROM authentication_events WHERE user_id=?",username);
    assertEquals(6,events.size());
    assertFalse(events.toString().contains("never-log-this-password"));
    assertFalse(events.toString().contains("127.0.0.1"));
  }

  @Test void remoteSessionRevocationIsOwnerScopedAndKeepsCurrentSession() {
    var first=login();var second=login();
    var other=devices(first).stream().filter(s -> Boolean.FALSE.equals(s.get("current"))).findFirst().orElseThrow();
    assertFalse(other.get("id").toString().contains("PDD_SESSION"));
    var response=http.exchange("/api/v1/auth/sessions/"+other.get("id"),HttpMethod.DELETE,
        new HttpEntity<>(headers(first,true)),Map.class);
    assertEquals(200,response.getStatusCode().value());
    assertEquals(401,read(second));assertEquals(200,read(first));
    assertEquals(1,devices(first).size());
  }

  @Test void revokeAllRejectsOldCookies() {
    var first=login();var second=login();
    assertEquals(204,post("/api/v1/auth/sessions/revoke-all",first,Map.of()).getStatusCode().value());
    assertEquals(401,read(first));assertEquals(401,read(second));
  }

  @Test void passwordChangeRevokesEverySessionAndOldPasswordStopsWorking() {
    var first=login();var second=login();
    assertEquals(204,post("/api/v1/auth/password",first,
        Map.of("current_password",PASSWORD,"new_password","changed-security-password-456")).getStatusCode().value());
    assertEquals(401,read(first));assertEquals(401,read(second));
    assertTrue(encoder.matches("changed-security-password-456",accounts.findByUsername(username).orElseThrow().passwordHash()));
    var before=anonymous();
    assertEquals(401,post("/api/v1/auth/login",before,Map.of("username",username,"password",PASSWORD)).getStatusCode().value());
    assertEquals(200,read(login(username,"changed-security-password-456")));
  }

  @Test void absoluteTimeoutAndVersionRevocationAreServerEnforced() {
    var browser=login();
    var active=sessions.findByPrincipalName(username).values().iterator().next();
    active.setAttribute(AccountSessions.STARTED,System.currentTimeMillis()-13*60*60*1000L);
    save(active);assertEquals(401,read(browser));
    var next=login();accounts.revokeAll(username);assertEquals(401,read(next));
  }

  @Test void sensitiveWriteRequiresRecentPasswordVerification() {
    var browser=login();
    var active=sessions.findByPrincipalName(username).values().iterator().next();
    active.setAttribute(AccountSessions.VERIFIED,0L);save(active);
    var refused=post("/api/v1/admin/model-config",browser,Map.of());
    assertEquals(403,refused.getStatusCode().value());
    assertEquals("REAUTHENTICATION_REQUIRED",refused.getBody().get("code"));
    assertEquals(200,post("/api/v1/auth/reauthenticate",browser,Map.of("password",PASSWORD)).getStatusCode().value());
    assertEquals(200,post("/api/v1/admin/model-config",browser,Map.of()).getStatusCode().value());
  }

  @Test void writesStillRequireCsrfWithAValidCookieAndBasicHeader() {
    var browser=login();var h=headers(browser,false);h.setBasicAuth(username,PASSWORD);
    assertEquals(403,http.postForEntity("/api/v1/admin/model-config",new HttpEntity<>(Map.of(),h),Map.class).getStatusCode().value());
  }

  @Test void recreatedUsernameCannotReuseOldSession() {
    var browser=login();
    jdbc.update("UPDATE app_users SET id=? WHERE username=?",UUID.randomUUID().toString(),username);
    assertEquals(401,read(browser));
  }

  @Test void failedReauthenticationDoesNotGrantSensitiveAccess() {
    var browser=login();
    var active=sessions.findByPrincipalName(username).values().iterator().next();
    active.setAttribute(AccountSessions.VERIFIED,0L);save(active);
    assertEquals(401,post("/api/v1/auth/reauthenticate",browser,Map.of("password","wrong-password")).getStatusCode().value());
    assertEquals(403,post("/api/v1/admin/model-config",browser,Map.of()).getStatusCode().value());
  }

  @Test void longMultibytePasswordFailsWithoutAnInternalError() {
    var browser=anonymous();
    assertEquals(401,post("/api/v1/auth/login",browser,Map.of("username",username,"password","密".repeat(40))).getStatusCode().value());
  }

  @Test void scrapeTokenCannotAuthenticateBusinessApis() {
    assertEquals(401,http.getForEntity("/actuator/prometheus",String.class).getStatusCode().value());
    var headers=new HttpHeaders();headers.setBearerAuth("isolated-metrics-test-token-with-more-than-32-characters");
    assertEquals(200,http.exchange("/actuator/prometheus",HttpMethod.GET,new HttpEntity<>(headers),String.class).getStatusCode().value());
    assertEquals(401,http.exchange("/api/v1/protected",HttpMethod.GET,new HttpEntity<>(headers),String.class).getStatusCode().value());
  }
}
