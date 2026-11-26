package com.pddcopilot.core;

import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;

/** Cookie/CSRF authentication for tests that exercise business authorization. */
final class BrowserTestClient {
  static TestRestTemplate login(TestRestTemplate http,String username,String password) {
    var anonymous=http.getForEntity("/api/v1/auth/session",Map.class);
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.COOKIE,anonymous.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";",2)[0]);
    headers.set(anonymous.getBody().get("csrf_header").toString(),anonymous.getBody().get("csrf_token").toString());
    var result=http.postForEntity("/api/v1/auth/login",new HttpEntity<>(
        Map.of("username",username,"password",password),headers),Map.class);
    if (!result.getStatusCode().is2xxSuccessful()) throw new AssertionError("Test login failed: "+result.getStatusCode());
    String cookie=result.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";",2)[0];
    headers.set(HttpHeaders.COOKIE,cookie);
    var state=http.exchange("/api/v1/auth/session",HttpMethod.GET,new HttpEntity<>(headers),Map.class).getBody();
    return new TestRestTemplate(new RestTemplateBuilder().rootUri(http.getRootUri()).additionalInterceptors((request,body,next) -> {
      request.getHeaders().set(HttpHeaders.COOKIE,cookie);
      request.getHeaders().set(state.get("csrf_header").toString(),state.get("csrf_token").toString());
      return next.execute(request,body);
    }));
  }
}
