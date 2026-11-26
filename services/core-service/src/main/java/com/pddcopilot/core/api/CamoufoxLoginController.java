package com.pddcopilot.core.api;

import com.pddcopilot.core.platform.pdd.CamoufoxLoginService;
import com.pddcopilot.core.repository.BusinessStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

/** Existing admin role, shop membership, CSRF and recent-password policies apply. */
@RestController
@RequestMapping("/api/v1/admin/shop/camoufox-login")
public class CamoufoxLoginController {
  public record Session(@NotBlank @Size(max = 36) String sessionId) {}
  public record Click(@NotBlank @Size(max = 36) String sessionId,
      @DecimalMin("0") @DecimalMax("0.999999") double x,
      @DecimalMin("0") @DecimalMax("0.999999") double y) {}
  private final BusinessStore store;
  private final CamoufoxLoginService login;
  public CamoufoxLoginController(BusinessStore store, CamoufoxLoginService login) {
    this.store = store; this.login = login;
  }
  @GetMapping public Object state() { return login.view(store.shopId()); }
  @PostMapping("/start") public Object start(Principal actor) {
    return login.start(store.shopId(), actor.getName());
  }
  @GetMapping("/frame") public Object frame(@RequestParam String sessionId) {
    return login.frame(store.shopId(), sessionId);
  }
  @PostMapping("/click") public Object click(@Valid @RequestBody Click input) {
    return login.click(store.shopId(), input.sessionId(), input.x(), input.y());
  }
  @PostMapping("/continue") public Object verify(@Valid @RequestBody Session input) {
    return login.verify(store.shopId(), input.sessionId());
  }
  @PostMapping("/confirm") public Object confirm(@Valid @RequestBody Session input, Principal actor) {
    return login.confirm(store.shopId(), input.sessionId(), actor.getName());
  }
  @PostMapping("/cancel") public Object cancel(@Valid @RequestBody Session input) {
    return login.cancel(store.shopId(), input.sessionId());
  }
}
