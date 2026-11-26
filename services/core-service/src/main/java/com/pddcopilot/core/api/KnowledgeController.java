package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.service.KnowledgeService;
import com.pddcopilot.core.platform.pdd.PddCommerceService;
import com.pddcopilot.core.service.ProductService;
import com.pddcopilot.core.service.WorkspaceService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class KnowledgeController {
  private final BusinessStore store;
  private final KnowledgeService knowledge;
  private final AgentClient agent;
  private final WorkspaceService workspaces;
  private final ProductService products;
  private final PddCommerceService pddCommerce;

  public KnowledgeController(
      BusinessStore store,
      KnowledgeService knowledge,
      AgentClient agent,
      WorkspaceService workspaces,
      ProductService products,
      PddCommerceService pddCommerce) {
    this.store = store;
    this.knowledge = knowledge;
    this.agent = agent;
    this.workspaces = workspaces;
    this.products = products;
    this.pddCommerce = pddCommerce;
  }

  @GetMapping("/api/v1/shops/{id}/knowledge")
  Object versions(@PathVariable String id) {
    return knowledge.versions(id);
  }

  @GetMapping("/api/v1/shops/{id}/knowledge/retrieval")
  Object retrieval(@PathVariable String id) {
    store.requireShopAccess(id);
    return agent.knowledgeStatus();
  }

  @PostMapping("/api/v1/shops/{id}/knowledge/preview")
  Object preview(@PathVariable String id, @Valid @RequestBody Contracts.DocumentPreview input) {
    store.requireShopAccess(id);
    return agent.previewDocument(input);
  }

  @PostMapping("/api/v1/shops/{id}/knowledge")
  Object publish(
      @PathVariable String id, @Valid @RequestBody Contracts.Knowledge input, Principal principal) {
    return knowledge.publish(id, input, principal.getName());
  }

  @PostMapping("/api/v1/shops/{id}/products/sync")
  Object sync(@PathVariable String id, Principal principal) {
    store.requireShopAccess(id);
    if ("unbound".equals(workspaces.connectorBinding().get("binding_state")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "CONNECTOR_NOT_CONFIGURED");
    var saved =
        new LinkedHashMap<String, Object>(
            knowledge.saveProducts(id, pddCommerce.products(), principal.getName()));
    if (((Number) saved.get("count")).intValue() > 0)
      saved.put("metadata_sync", products.start(principal.getName()));
    return saved;
  }

  @GetMapping("/api/v1/shops/{id}/products")
  Object products(@PathVariable String id) {
    return knowledge.latestProducts(id);
  }
}
