package com.pddcopilot.core.config;

import com.pddcopilot.core.service.AccountSessions;
import com.pddcopilot.core.service.AuthenticationAudit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies credential revocation and server-enforced absolute lifetime before API execution. */
public class BrowserSessionGuard extends OncePerRequestFilter {
  private final AccountSessions sessions;
  private final AuthenticationAudit audit;
  private final com.pddcopilot.core.support.ClientAddress addresses;
  public BrowserSessionGuard(AccountSessions sessions,AuthenticationAudit audit,com.pddcopilot.core.support.ClientAddress addresses) {
    this.sessions=sessions; this.audit=audit;
    this.addresses=addresses;
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    var auth=SecurityContextHolder.getContext().getAuthentication();
    if (!request.getRequestURI().startsWith("/internal/") && auth!=null && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken)
        && !sessions.valid(request.getSession(false),auth.getName())) {
      sessions.clear(request);
      audit.record("session.expired",auth.getName(),addresses.resolve(request));
      if (!request.getRequestURI().equals("/api/v1/auth/session")) {
        response.setStatus(401); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"SESSION_EXPIRED\"}");
        return;
      }
    }
    chain.doFilter(request,response);
  }
}
