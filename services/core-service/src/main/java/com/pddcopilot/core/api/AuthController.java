package com.pddcopilot.core.api;

import com.pddcopilot.core.service.LoginRateLimiter;
import com.pddcopilot.core.service.AuthenticationAudit;
import com.pddcopilot.core.service.AccountSessions;
import com.pddcopilot.core.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.pddcopilot.core.support.ClientAddress;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.AuthenticationException;
import java.nio.charset.StandardCharsets;
import com.pddcopilot.core.support.WorkflowValues;

import com.pddcopilot.core.service.RegistrationRateLimiter;
import com.pddcopilot.core.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  public record Registration(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,48}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 256) String inviteCode) {}

  private final UserService users;
  private final RegistrationRateLimiter limiter;
  private final AuthenticationManager authentication;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository csrfTokens;
  private final LoginRateLimiter loginLimiter;
  private final AuthenticationAudit audit;
  private final AccountSessions sessions;
  private final UserRepository accounts;
  private final PasswordEncoder passwords;
  private final ClientAddress addresses;

  public AuthController(
      UserService users,
      RegistrationRateLimiter limiter,
      AuthenticationManager authentication,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrfTokens,
      LoginRateLimiter loginLimiter,
      AuthenticationAudit audit,
      AccountSessions sessions,
      UserRepository accounts,
      PasswordEncoder passwords,
      ClientAddress addresses) {
    this.users = users;
    this.limiter = limiter;
    this.authentication = authentication;
    this.contexts = contexts;
    this.csrfTokens = csrfTokens;
    this.loginLimiter=loginLimiter; this.audit=audit; this.sessions=sessions;
    this.accounts=accounts; this.passwords=passwords;
    this.addresses=addresses;
  }

  public record Login(
      @NotBlank @Size(max = 48) String username, @NotBlank @Size(max = 256) String password) {}

  @GetMapping("/api/v1/auth/session")
  public Object session(Authentication auth, CsrfToken csrf, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return Map.of(
        "authenticated",
        auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken),
        "csrf_token",
        csrf.getToken(),
        "csrf_header",
        csrf.getHeaderName());
  }

  @PostMapping("/api/v1/auth/login")
  public Object login(
      @Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
    String username=input.username().toLowerCase(java.util.Locale.ROOT);
    String accountId=accounts.findByUsername(username).map(a -> a.id()).orElse("");
    long version=accounts.authVersion(username);
    var auth=verify(username,input.password(),request,"login");
    new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response);
    new CsrfAuthenticationStrategy(csrfTokens).onAuthentication(auth, request, response);
    sessions.attach(request,username,version,accountId);
    audit.record("login.success",username,addresses.resolve(request));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    response.setHeader("Cache-Control", "no-store");
    return Map.of("authenticated", true);
  }

  public record Password(@NotBlank @Size(max=256) String password) {}
  public record PasswordChange(@NotBlank @Size(max=256) String currentPassword,
      @NotBlank @Size(min=12,max=72) String newPassword) {}

  private Authentication verify(String username,String password,HttpServletRequest request,String kind) {
    try { loginLimiter.check(username,addresses.resolve(request)); }
    catch (ResponseStatusException error) {
      audit.record(kind+".limited",username,addresses.resolve(request)); throw error;
    }
    try {
      if (password.getBytes(StandardCharsets.UTF_8).length>72)
        throw new BadCredentialsException("AUTHENTICATION_FAILED");
      return authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username,password));
    } catch (AuthenticationException error) {
      audit.record(kind+".failed",username,addresses.resolve(request)); throw error;
    }
  }

  @PostMapping("/api/v1/auth/reauthenticate")
  public Object reauthenticate(@Valid @RequestBody Password input,Authentication auth,HttpServletRequest request) {
    verify(auth.getName(),input.password(),request,"reauth");
    if (!sessions.valid(request.getSession(false),auth.getName()))
      throw new BadCredentialsException("AUTHENTICATION_FAILED");
    request.getSession().setAttribute(AccountSessions.VERIFIED,System.currentTimeMillis());
    audit.record("reauth.success",auth.getName(),addresses.resolve(request));
    return Map.of("verified",true);
  }

  @GetMapping("/api/v1/auth/sessions")
  public Object devices(Authentication auth,HttpServletRequest request) {
    return sessions.list(auth.getName(),request.getSession().getId());
  }

  @DeleteMapping("/api/v1/auth/sessions/{reference}")
  public Object revoke(@PathVariable String reference,Authentication auth,HttpServletRequest request) {
    boolean current=WorkflowValues.sha256(request.getSession().getId()).equals(reference);
    boolean removed=sessions.revoke(auth.getName(),reference);
    if (removed) audit.record("session.revoked",auth.getName(),addresses.resolve(request));
    if (removed && current) sessions.clear(request);
    return Map.of("removed",removed);
  }

  @PostMapping("/api/v1/auth/sessions/revoke-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeAll(Authentication auth,HttpServletRequest request) {
    sessions.revokeAll(auth.getName());
    audit.record("sessions.revoked",auth.getName(),addresses.resolve(request));
    sessions.clear(request);
  }

  @PostMapping("/api/v1/auth/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(@Valid @RequestBody PasswordChange input,Authentication auth,HttpServletRequest request) {
    if (input.newPassword().getBytes(StandardCharsets.UTF_8).length>72)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PASSWORD_TOO_LONG");
    long version=accounts.authVersion(auth.getName());
    verify(auth.getName(),input.currentPassword(),request,"password");
    if (accounts.changePassword(auth.getName(),passwords.encode(input.newPassword()),version,
        request.getSession().getAttribute(AccountSessions.ACCOUNT).toString())!=1)
      throw WorkflowValues.conflict("PASSWORD_CHANGE_CONFLICT");
    sessions.revokeAll(auth.getName());
    audit.record("password.changed",auth.getName(),addresses.resolve(request));
    sessions.clear(request);
  }

  @GetMapping("/api/v1/auth/security-events")
  public Object events(Authentication auth) { return audit.recent(auth.getName()); }

  @PostMapping("/api/v1/auth/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Object register(@Valid @RequestBody Registration input, HttpServletRequest request) {
    limiter.check(addresses.resolve(request));
    return users.register(input.username(), input.password(), input.inviteCode());
  }
}
