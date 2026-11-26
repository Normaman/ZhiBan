package com.pddcopilot.core.service;

import com.pddcopilot.core.repository.UserRepository;
import com.pddcopilot.core.support.WorkflowValues;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class AccountSessions {
  public static final String STARTED="auth.started", VERSION="auth.version", VERIFIED="auth.verified", ACCOUNT="auth.account";
  private final FindByIndexNameSessionRepository<? extends Session> sessions;
  private final UserRepository users;
  public AccountSessions(FindByIndexNameSessionRepository<? extends Session> sessions,UserRepository users) {
    this.sessions=sessions; this.users=users;
  }
  public void attach(HttpServletRequest request,String username,long version,String accountId) {
    var account=users.findByUsername(username).filter(a -> a.enabled()).orElseThrow(
        () -> new BadCredentialsException("AUTHENTICATION_FAILED"));
    if (version<0 || users.authVersion(username)!=version || !account.id().equals(accountId))
      throw new BadCredentialsException("AUTHENTICATION_FAILED");
    var session=request.getSession();
    long now=System.currentTimeMillis();
    session.setAttribute(STARTED,now);
    session.setAttribute(VERSION,version);
    session.setAttribute(ACCOUNT,accountId);
    session.setAttribute(VERIFIED,now);
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,username);
    String agent=Objects.toString(request.getHeader("User-Agent"),"未知浏览器");
    session.setAttribute("auth.browser",agent.substring(0,Math.min(agent.length(),200)));
    // Keep at most five active sessions per account; do not expose raw session IDs.
    var existing=new ArrayList<>(sessions.findByPrincipalName(username).values());
    existing.sort(Comparator.comparing(Session::getLastAccessedTime));
    while (existing.size()>=5) sessions.deleteById(existing.remove(0).getId());
  }
  public boolean valid(HttpSession session,String username) {
    if (session==null || !(session.getAttribute(STARTED) instanceof Number started)
        || !(session.getAttribute(VERSION) instanceof Number version)) return false;
    return System.currentTimeMillis()-started.longValue()<Duration.ofHours(12).toMillis()
        && users.authVersion(username)==version.longValue()
        && users.findByUsername(username).filter(a -> a.enabled() && a.id().equals(session.getAttribute(ACCOUNT))).isPresent();
  }
  public boolean recent(HttpSession session) {
    return session!=null && session.getAttribute(VERIFIED) instanceof Number time
        && System.currentTimeMillis()-time.longValue()<Duration.ofMinutes(5).toMillis();
  }
  public List<Map<String,Object>> list(String username,String current) {
    return sessions.findByPrincipalName(username).values().stream()
        .sorted(Comparator.comparing(Session::getLastAccessedTime).reversed())
        .map(s -> Map.<String,Object>of("id",WorkflowValues.sha256(s.getId()),
            "current",s.getId().equals(current),"created_at",s.getCreationTime().toEpochMilli(),
            "last_seen_at",s.getLastAccessedTime().toEpochMilli(),
            "browser",Objects.toString(s.getAttribute("auth.browser"),"未知浏览器")))
        .toList();
  }
  public boolean revoke(String username,String reference) {
    for (var session:sessions.findByPrincipalName(username).values()) {
      if (WorkflowValues.sha256(session.getId()).equals(reference)) {
        sessions.deleteById(session.getId());
        return true;
      }
    }
    return false;
  }
  public void revokeAll(String username) {
    // Version check also rejects sessions that race with an indexed deletion.
    users.revokeAll(username);
    for (var session:sessions.findByPrincipalName(username).values()) sessions.deleteById(session.getId());
  }
  public void clear(HttpServletRequest request) {
    var session=request.getSession(false);
    if (session!=null) session.invalidate();
    SecurityContextHolder.clearContext();
  }
}
