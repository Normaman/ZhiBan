package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.WorkspaceRepository;
import com.pddcopilot.core.service.ConnectionService;
import com.pddcopilot.core.service.ConversationService;
import com.pddcopilot.core.service.ModelConfigurationService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class WorkbenchController {
  private final ConnectionService connections;
  private final ConversationService conversations;
  private final ModelConfigurationService models;
  private final BusinessStore store;
  private final WorkspaceRepository workspaces;

  public WorkbenchController(
      ConnectionService connections,
      ConversationService conversations,
      ModelConfigurationService models,
      BusinessStore store,
      WorkspaceRepository workspaces) {
    this.connections = connections;
    this.conversations = conversations;
    this.models = models;
    this.store = store;
    this.workspaces = workspaces;
  }

  @GetMapping("/api/v1/bootstrap")
  Object bootstrap(Authentication p) {
    var available = workspaces.accessibleShops(p.getName());
    var current = connections.shops().get(0);
    available.replaceAll(
        shop ->
            store.shopId().equals(shop.get("id")) ? new java.util.LinkedHashMap<>(current) : shop);
    available.sort(java.util.Comparator.comparing(s -> !store.shopId().equals(s.get("id"))));
    return Map.of(
        "user",
        p.getName(),
        "roles",
        p.getAuthorities().stream().map(a -> a.getAuthority().replace("ROLE_", "")).toList(),
        "ai",
        models.status(),
        "active_shop_id",
        store.shopId(),
        "shops",
        available,
        "conversations",
        conversations.list(p.getName()));
  }

  @GetMapping("/api/v1/conversations/{id}")
  Object detail(@PathVariable String id, Principal p) {
    return conversations.detail(id, p.getName());
  }

  @PostMapping("/api/v1/conversations/{id}/transfer")
  Object transfer(@PathVariable String id, Principal p) {
    return conversations.transfer(id, p.getName());
  }

  @PostMapping("/api/v1/conversations/{id}/resume")
  Object resume(@PathVariable String id, @Valid @RequestBody Contracts.Resume input, Principal p) {
    return conversations.resume(id, input.revision(), p.getName());
  }

  @PostMapping("/api/v1/conversations/{id}/read")
  Object read(
      @PathVariable String id, @Valid @RequestBody Contracts.ReadMessages input, Principal p) {
    return conversations.markRead(id, input.messageIds(), p.getName());
  }

  @GetMapping("/api/v1/audit-events")
  Object recordAudit() {
    return conversations.auditEvents();
  }
}
