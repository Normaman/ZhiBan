package com.pddcopilot.core.api;

import com.pddcopilot.core.platform.pdd.PddCommerceService;
import com.pddcopilot.core.platform.pdd.PddOrderCacheService;
import com.pddcopilot.core.repository.BusinessStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.pddcopilot.core.api.dto.Contracts;

@RestController
public class OrderController {
  private final BusinessStore store;
  private final PddCommerceService commerce;
  private final PddOrderCacheService views;

  public OrderController(BusinessStore store, PddCommerceService commerce, PddOrderCacheService views) {
    this.store = store;
    this.commerce = commerce;
    this.views = views;
  }

  @GetMapping("/api/v1/shops/{id}/orders/overview")
  Object overview(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
    store.requireShopAccess(id);
    return views.overview(refresh);
  }

  @GetMapping("/api/v1/shops/{id}/orders")
  Object orders(@PathVariable String id,
      @RequestParam(defaultValue = "2") int orderType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "false") boolean refresh) {
    store.requireShopAccess(id);
    return views.list(orderType, page, refresh);
  }

  @GetMapping("/api/v1/shop/order-query/browser")
  org.springframework.http.ResponseEntity<Void> orderBrowser() {
    String viewer = String.valueOf(commerce.openOrderListBrowser().get("viewer_url"));
    return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
        .location(java.net.URI.create(viewer)).build();
  }

  @PostMapping("/api/v1/admin/shop/order-query/verification")
  Object verification(@Valid @RequestBody Contracts.OrderQuery input) {
    return commerce.orderVerification(input.orderId(), input.buyerRef());
  }

  @PostMapping("/api/v1/shop/order-query/retry")
  Object retry(@Valid @RequestBody Contracts.OrderQuery input) {
    return commerce.retryVerifiedOrder(input.orderId(), input.buyerRef());
  }

  @PostMapping("/api/v1/admin/shop/order-query/close")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void closeVerification() {
    commerce.closeOrderVerification();
  }

}
