package com.pddcopilot.core.config;

import com.pddcopilot.core.repository.UserRepository;
import com.pddcopilot.core.repository.WorkspaceRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
  @Bean
  AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  SecurityContextRepository browserContexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokens() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService reviewers(UserRepository users) {
    return username -> {
      var account =
          users
              .findByUsername(username.toLowerCase(java.util.Locale.ROOT))
              .filter(com.pddcopilot.core.domain.UserAccount::enabled)
              .orElseThrow(() -> new UsernameNotFoundException("ACCOUNT_NOT_FOUND"));
      return User.withUsername(account.username())
          .password(account.passwordHash())
          .roles(
              "ADMIN".equals(account.role())
                  ? new String[] {"ADMIN", "REVIEWER"}
                  : new String[] {"REVIEWER"})
          .build();
    };
  }

  @Bean
  SecurityFilterChain filter(
      HttpSecurity http,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrfTokens,
      WorkspaceRepository workspaces,
      UserRepository users,
      com.pddcopilot.core.service.AccountSessions sessions,
      com.pddcopilot.core.service.AuthenticationAudit audit,
      com.pddcopilot.core.support.ClientAddress addresses,
      @Value("${copilot.shop-id}") String defaultShop,
      @Value("${copilot.internal-token}") String token)
      throws Exception {
    if (token.length() < 24)
      throw new IllegalArgumentException("INTERNAL_TOKEN must have at least 24 characters");
    var internal =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws IOException, ServletException {
            if (req.getRequestURI().startsWith("/internal/")) {
              String supplied = req.getHeader("X-Internal-Token");
              if (supplied == null
                  || !MessageDigest.isEqual(
                      token.getBytes(StandardCharsets.UTF_8),
                      supplied.getBytes(StandardCharsets.UTF_8))) {
                res.sendError(401);
                return;
              }
            }
            chain.doFilter(req, res);
          }
        };
    return http.csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokens)
                    .ignoringRequestMatchers(
                        req ->
                            req.getRequestURI().startsWith("/internal/")))
        .securityContext(c -> c.securityContextRepository(contexts))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(c -> c.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, error) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, error) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  error
                                          instanceof
                                          org.springframework.security.web.csrf.CsrfException
                                      ? "{\"code\":\"CSRF_INVALID\"}"
                                      : "{\"code\":\"ACCESS_DENIED\"}");
                        }))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/internal/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/session",
                        "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/shops/*/knowledge")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasRole("REVIEWER"))
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("PDD_SESSION")
                    .logoutSuccessHandler((req, res, auth) -> {
                      if (auth!=null) audit.record("logout.success",auth.getName(),addresses.resolve(req));
                      res.setStatus(204);
                    }))
        .httpBasic(b -> b.disable())
        .addFilterBefore(internal, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new BrowserSessionGuard(sessions,audit,addresses),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .addFilterAfter(new SensitiveOperationGuard(sessions),AuthorizationFilter.class)
        .addFilterBefore(
            new ShopAuthorizationFilter(workspaces, users, defaultShop), AuthorizationFilter.class)
        .build();
  }
}
