package com.pddcopilot.core.config;

import com.pddcopilot.core.service.AccountSessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SensitiveOperationGuard extends OncePerRequestFilter {
  private final AccountSessions sessions;

  public SensitiveOperationGuard(AccountSessions sessions) {
    this.sessions = sessions;
  }

  public static boolean sensitive(String method, String path) {
    if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return false;
    return path.startsWith("/api/v1/admin/workspace/members/")
        || path.startsWith("/api/v1/admin/model-config")
        || (method.equals("DELETE") && path.startsWith("/api/v1/admin/shops/"))
        || path.equals("/api/v1/admin/workspace/invitations")
        || path.equals("/api/v1/admin/shop/connector-binding")
        || path.equals("/api/v1/admin/shop/pdd-login/start")
        || path.equals("/api/v1/admin/shop/pdd-login/confirm")
        || path.equals("/api/v1/admin/shop/camoufox-login/start")
        || path.equals("/api/v1/admin/shop/camoufox-login/confirm")
        || path.startsWith("/api/v1/auth/sessions/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken)
        && sensitive(request.getMethod(), request.getRequestURI())
        && !sessions.recent(request.getSession(false))) {
      response.setStatus(403);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"REAUTHENTICATION_REQUIRED\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
