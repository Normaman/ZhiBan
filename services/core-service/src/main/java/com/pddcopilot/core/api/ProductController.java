package com.pddcopilot.core.api;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.service.ProductService;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProductController {
  private final BusinessStore store;
  private final ProductService products;

  public ProductController(BusinessStore store, ProductService products) {
    this.store = store;
    this.products = products;
  }

  @PostMapping("/api/v1/shops/{id}/products/metadata-syncs")
  Object start(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean force,
      Principal actor) {
    store.requireShopAccess(id);
    return products.start(actor.getName(), force);
  }

  @GetMapping("/api/v1/shops/{id}/products/metadata-syncs")
  Object status(@PathVariable String id) {
    store.requireShopAccess(id);
    return products.status();
  }

  @GetMapping("/api/v1/shops/{id}/products/{goodsId}/details")
  Object detail(@PathVariable String id, @PathVariable String goodsId) {
    store.requireShopAccess(id);
    return products.detail(goodsId);
  }
}
