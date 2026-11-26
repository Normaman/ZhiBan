package com.pddcopilot.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "copilot.session-store=memory",
      "spring.datasource.url=jdbc:h2:mem:browser-session;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "copilot.reviewer-password=test-reviewer-password-123",
      "copilot.internal-token=test-internal-token-with-32-characters",
      "copilot.worker-enabled=false",
      "copilot.redis-enabled=false",
      "copilot.mode=demo"
    })
class BrowserSessionIntegrationTest {
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired TestRestTemplate http;
  @Autowired com.pddcopilot.core.repository.BusinessStore store;
  @Autowired com.pddcopilot.core.service.WorkspaceService workspaces;
  @Autowired com.pddcopilot.core.service.UserService users;

  String cookie(ResponseEntity<?> response) {
    return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];
  }

  ResponseEntity<Map> session(String cookie) {
    var headers = new HttpHeaders();
    if (cookie != null) headers.set(HttpHeaders.COOKIE, cookie);
    return http.exchange(
        "/api/v1/auth/session", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  HttpHeaders headers(String cookie, Map session) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.COOKIE, cookie);
    if (session != null)
      headers.set((String) session.get("csrf_header"), (String) session.get("csrf_token"));
    return headers;
  }

  @Test
  void cookieRestoresSessionProtectsWritesAndLogoutInvalidatesIt() {
    var anonymous = session(null);
    assertEquals(false, anonymous.getBody().get("authenticated"));
    String before = cookie(anonymous);
    var login =
        http.postForEntity(
            "/api/v1/auth/login",
            new HttpEntity<>(
                Map.of("username", "reviewer", "password", "test-reviewer-password-123"),
                headers(before, anonymous.getBody())),
            Map.class);
    assertEquals(200, login.getStatusCode().value());
    String signedIn = cookie(login);
    assertNotEquals(before, signedIn);
    assertTrue(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("HttpOnly"));
    assertTrue(login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).contains("SameSite=Lax"));
    var current = session(signedIn);
    assertEquals(true, current.getBody().get("authenticated"));
    assertEquals(
        200,
        http.exchange(
                "/api/v1/bootstrap",
                HttpMethod.GET,
                new HttpEntity<>(headers(signedIn, null)),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        401,
        http.exchange(
                "/api/v1/bootstrap",
                HttpMethod.GET,
                new HttpEntity<>(headers(before, null)),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        http.postForEntity(
                "/api/v1/auth/logout",
                new HttpEntity<>(Map.of(), headers(signedIn, null)),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(true, session(signedIn).getBody().get("authenticated"));
    assertEquals(
        204,
        http.postForEntity(
                "/api/v1/auth/logout",
                new HttpEntity<>(Map.of(), headers(signedIn, current.getBody())),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        401,
        http.exchange(
                "/api/v1/bootstrap",
                HttpMethod.GET,
                new HttpEntity<>(headers(signedIn, null)),
                String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void loginRequiresCsrfAndRejectsWrongPasswordWithoutLeakingIt() {
    var credentials = Map.of("username", "reviewer", "password", "incorrect-private-password");
    assertEquals(
        403,
        http.postForEntity("/api/v1/auth/login", credentials, String.class)
            .getStatusCode()
            .value());
    var anonymous = session(null);
    var rejected =
        http.postForEntity(
            "/api/v1/auth/login",
            new HttpEntity<>(credentials, headers(cookie(anonymous), anonymous.getBody())),
            String.class);
    assertEquals(401, rejected.getStatusCode().value());
    assertFalse(rejected.getBody().contains("incorrect-private-password"));
    assertEquals(
        401,
        http.withBasicAuth("reviewer", "test-reviewer-password-123")
            .getForEntity("/api/v1/bootstrap", String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void existingSessionLosesShopAccessImmediatelyAfterMembershipRevocation() {
    var invited =
        users.register(
            "revoked-session-user",
            "revoked-session-password",
            workspaces.invite("reviewer").get("invite_code").toString());
    var anonymous = session(null);
    var login =
        http.postForEntity(
            "/api/v1/auth/login",
            new HttpEntity<>(
                Map.of("username", "revoked-session-user", "password", "revoked-session-password"),
                headers(cookie(anonymous), anonymous.getBody())),
            Map.class);
    assertEquals(200, login.getStatusCode().value());
    String signedIn = cookie(login);
    assertEquals(
        200,
        http.exchange(
                "/api/v1/bootstrap",
                HttpMethod.GET,
                new HttpEntity<>(headers(signedIn, null)),
                String.class)
            .getStatusCode()
            .value());
    jdbc
        .update(
            "DELETE FROM workspace_memberships WHERE user_id=(SELECT id FROM app_users WHERE"
                + " username=?)",
            "revoked-session-user");
    assertEquals(
        403,
        http.exchange(
                "/api/v1/bootstrap",
                HttpMethod.GET,
                new HttpEntity<>(headers(signedIn, null)),
                String.class)
            .getStatusCode()
            .value());
    assertEquals(true, session(signedIn).getBody().get("authenticated"));
  }
}
