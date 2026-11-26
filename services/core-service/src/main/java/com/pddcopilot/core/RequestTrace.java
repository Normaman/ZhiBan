package com.pddcopilot.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class RequestTrace extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String trace = request.getHeader("X-Trace-Id");
    if (trace == null || !trace.matches("[A-Za-z0-9_-]{1,100}"))
      trace = UUID.randomUUID().toString();
    response.setHeader("X-Trace-Id", trace);
    request.setAttribute("trace_id", trace);
    long start = System.currentTimeMillis();
    try {
      chain.doFilter(request, response);
    } finally {
      LoggerFactory.getLogger(RequestTrace.class)
          .info(
              "request_complete trace_id={} method={} status={} duration_ms={}",
              trace,
              request.getMethod(),
              response.getStatus(),
              System.currentTimeMillis() - start);
    }
  }
}
