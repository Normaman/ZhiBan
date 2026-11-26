package com.pddcopilot.core.config;

import com.pddcopilot.core.repository.UserRepository;
import com.pddcopilot.core.repository.WorkspaceRepository;
import com.pddcopilot.core.support.ShopScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A shop header selects a scope; it never grants access to it. Internal callers are trusted
 * services.
 */
public class ShopAuthorizationFilter extends OncePerRequestFilter {
  private final WorkspaceRepository workspaces;
  private final UserRepository users;
  private final String defaultShop;

  public ShopAuthorizationFilter(
      WorkspaceRepository workspaces, UserRepository users, String defaultShop) {
    this.workspaces = workspaces;
    this.users = users;
    this.defaultShop = defaultShop;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getRequestURI();
    if (path.startsWith("/api/") || path.startsWith("/internal/"))
      response.setHeader("Cache-Control", "no-store");
    if (path.startsWith("/internal/")) {
      String id = request.getHeader("X-Shop-Id");
      try (var scope = ShopScope.open(id == null || id.isBlank() ? defaultShop : id)) {
        chain.doFilter(request, response);
      }
      return;
    }
    if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/")) {
      chain.doFilter(request, response);
      return;
    }
    var original = SecurityContextHolder.getContext();
    var auth = original.getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      chain.doFilter(request, response);
      return;
    }
    var account = users.findByUsername(auth.getName()).filter(a -> a.enabled()).orElse(null);
    var shops = workspaces.accessibleShops(auth.getName());
    String selected = request.getHeader("X-Shop-Id");
    if (selected == null || selected.isBlank()) {
      selected = account == null ? "" : account.shopId();
      final String preferred = selected;
      if (shops.stream().noneMatch(s -> preferred.equals(s.get("id"))) && !shops.isEmpty())
        selected = shops.get(0).get("id").toString();
    }
    final String wanted = selected;
    Map<String, Object> shop =
        shops.stream().filter(s -> wanted.equals(s.get("id"))).findFirst().orElse(null);
    if (account == null || shop == null) {
      response.setStatus(403);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"SHOP_FORBIDDEN\"}");
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    boolean admin = java.util.Set.of("ADMIN", "OWNER").contains(shop.get("member_role"));
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            auth.getPrincipal(),
            auth.getCredentials(),
            AuthorityUtils.createAuthorityList(
                admin
                    ? new String[] {"ROLE_ADMIN", "ROLE_REVIEWER"}
                    : new String[] {"ROLE_REVIEWER"})));
    SecurityContextHolder.setContext(context);
    request.setAttribute("workspace_id", shop.get("workspace_id"));
    try (var scope = ShopScope.open(wanted)) {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.setContext(original);
    }
  }
}
