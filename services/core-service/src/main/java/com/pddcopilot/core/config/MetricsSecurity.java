package com.pddcopilot.core.config;

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
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** A read-only scrape credential does not grant access to business or internal APIs. */
@Configuration
public class MetricsSecurity {
  @Bean @Order(0)
  SecurityFilterChain metrics(HttpSecurity http,@Value("${METRICS_TOKEN:}") String token) throws Exception {
    var guard=new OncePerRequestFilter() {
      @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
          throws ServletException,IOException {
        String supplied=req.getHeader("Authorization");
        if (token.length()<32 || supplied==null || !MessageDigest.isEqual(
            ("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
          res.setStatus(401);return;
        }
        chain.doFilter(req,res);
      }
    };
    return http.securityMatcher("/actuator/prometheus")
        .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable()).requestCache(c -> c.disable())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .addFilterBefore(guard,AuthorizationFilter.class).build();
  }
}
