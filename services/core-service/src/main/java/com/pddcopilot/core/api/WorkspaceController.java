package com.pddcopilot.core.api;

import com.pddcopilot.core.service.PlatformLoginService;
import com.pddcopilot.core.service.ShopRemovalService;
import com.pddcopilot.core.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class WorkspaceController {
  public record ShopName(@NotBlank @Size(max = 100) String name) {}

  public record Binding(
      @NotBlank @Size(max = 100) String platformShopId,
      @NotBlank @Size(max = 100) String connectorAccountId) {}

  private final WorkspaceService workspaces;
  private final PlatformLoginService platformLogin;
  private final com.pddcopilot.core.platform.pdd.CamoufoxLoginService camoufoxLogin;
  private final ShopRemovalService shopRemoval;
  private final String enrollmentToken;

  public WorkspaceController(
      WorkspaceService workspaces,
      PlatformLoginService platformLogin,
      com.pddcopilot.core.platform.pdd.CamoufoxLoginService camoufoxLogin,
      ShopRemovalService shopRemoval,
      @Value("${copilot.internal-token}") String enrollmentToken) {
    this.workspaces = workspaces;
    this.platformLogin = platformLogin;
    this.camoufoxLogin = camoufoxLogin;
    this.shopRemoval = shopRemoval;
    this.enrollmentToken = enrollmentToken;
  }

  @GetMapping("/api/v1/admin/workspace")
  Object overview(Principal user) {
    return workspaces.overview(user.getName());
  }

  @PostMapping("/api/v1/admin/shops")
  Object create(@Valid @RequestBody ShopName input, Principal user) {
    return workspaces.addShop(input.name(), user.getName());
  }

  @PostMapping("/api/v1/admin/shops/live")
  Object createLive(@Valid @RequestBody ShopName input, Principal user) {
    return workspaces.addLiveShop(input.name(), user.getName());
  }

  @DeleteMapping("/api/v1/admin/shops/{id}")
  Object remove(@PathVariable String id, Principal user) {
    shopRemoval.validateRemoval(id);
    platformLogin.stopForShopRemoval(id);
    camoufoxLogin.stopForShopRemoval(id);
    shopRemoval.remove(id, user.getName());
    return Map.of("removed", true);
  }

  @PostMapping("/api/v1/admin/shop/name")
  Object rename(@Valid @RequestBody ShopName input, Principal user) {
    return workspaces.renameShop(input.name(), user.getName());
  }

  @GetMapping("/api/v1/admin/shop/pdd-login")
  Object pddLoginStatus() {
    var binding = workspaces.connectorBinding();
    return platformLogin.view(value(binding.get("id")));
  }

  @PostMapping("/api/v1/admin/shop/pdd-login/start")
  Object startPddLogin(Principal user) {
    var binding = workspaces.connectorBinding();
    if (!"live".equals(binding.get("mode")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "PDD_SHOP_NOT_BOUND");
    return platformLogin.start(
        value(binding.get("id")),
        value(binding.get("mode")),
        value(binding.get("binding_state")),
        value(binding.get("platform_shop_id")),
        value(binding.get("connector_account_id")),
        user.getName());
  }

  @PostMapping("/api/v1/admin/shop/pdd-login/continue")
  Object continuePddLogin() {
    var binding = workspaces.connectorBinding();
    return platformLogin.continueLogin(value(binding.get("id")));
  }

  @PostMapping("/api/v1/admin/shop/pdd-login/confirm")
  Object confirmPddLogin(@RequestBody Map<String, Boolean> input, Principal user) {
    var binding = workspaces.connectorBinding();
    return platformLogin.decide(
        Boolean.TRUE.equals(input.get("accepted")), value(binding.get("id")), user.getName());
  }

  @PostMapping("/api/v1/admin/shop/pdd-login/cancel")
  Object cancelPddLogin() {
    var binding = workspaces.connectorBinding();
    return platformLogin.cancel(value(binding.get("id")));
  }

  private static String value(Object value) {
    return value == null ? "" : value.toString();
  }

  @PostMapping("/api/v1/admin/workspace/invitations")
  Object invite(Principal user) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(workspaces.invite(user.getName()));
  }

  @PostMapping("/api/v1/admin/workspace/invitations/{id}/revoke")
  Object revoke(@PathVariable String id, Principal user) {
    workspaces.revokeInvite(id, user.getName());
    return Map.of("revoked", true);
  }

  @PostMapping("/api/v1/admin/shop/connector-binding")
  Object bind(
      @Valid @RequestBody Binding input,
      Principal user,
      @RequestHeader(value = "X-Connector-Enrollment-Token", defaultValue = "") String token) {
    if (!MessageDigest.isEqual(
        enrollmentToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONNECTOR_ENROLLMENT_REQUIRED");
    return workspaces.bindConnector(
        input.platformShopId(), input.connectorAccountId(), user.getName());
  }

  @GetMapping("/internal/v1/shops/{id}/connector-binding")
  Object binding(@PathVariable String id) {
    if (!id.equals(workspaces.connectorBinding().get("id")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "SHOP_FORBIDDEN");
    return workspaces.connectorBinding();
  }
}
