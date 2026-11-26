package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.service.AgentRunService;
import com.pddcopilot.core.service.ConnectionService;
import com.pddcopilot.core.service.InboxService;
import com.pddcopilot.core.service.ReadReceiptService;
import com.pddcopilot.core.service.ReplySendService;
import com.pddcopilot.core.service.ProductService;
import com.pddcopilot.core.platform.pdd.PddCommerceService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalController {
  private final InboxService inbox;
  private final ConnectionService connections;
  private final AgentRunService runs;
  private final ReplySendService sends;
  private final ReadReceiptService receipts;
  private final PddCommerceService commerce;
  private final ProductService products;

  public InternalController(
      InboxService inbox,
      ConnectionService connections,
      AgentRunService runs,
      ReplySendService sends,
      ReadReceiptService receipts,
      PddCommerceService commerce,
      ProductService products) {
    this.inbox = inbox;
    this.connections = connections;
    this.runs = runs;
    this.sends = sends;
    this.receipts = receipts;
    this.commerce = commerce;
    this.products = products;
  }

  @PostMapping("/internal/v1/pdd/read-receipts")
  Object readReceipt(@Valid @RequestBody Contracts.ReadReceipt input) {
    return receipts.receive(input);
  }

  @PostMapping("/internal/v1/pdd/messages")
  Object receive(
      @Valid @RequestBody Contracts.Inbound input, @RequestHeader("Idempotency-Key") String key) {
    return inbox.receive(input, key);
  }

  @PostMapping("/internal/v1/agent-tools/products")
  Object agentProducts() {
    return commerce.products();
  }

  @PostMapping("/internal/v1/agent-tools/product-detail")
  Object agentProductDetail(@Valid @RequestBody Contracts.ProductQuery input) {
    var detail = commerce.productDetail(input.goodsId());
    products.captureAgentDetail(detail);
    return detail;
  }

  @PostMapping("/internal/v1/agent-tools/order-detail")
  Object agentOrderDetail(@Valid @RequestBody Contracts.OrderQuery input) {
    return commerce.orderDetail(input.orderId(), input.buyerRef());
  }

  @PostMapping("/internal/v1/shops/{id}/connection")
  void heartbeat(@PathVariable String id, @Valid @RequestBody Contracts.Connection input) {
    connections.update(id, input);
  }

  @PostMapping("/internal/v1/shops/{id}/profile")
  void profile(@PathVariable String id, @Valid @RequestBody Contracts.ShopProfile input) {
    connections.updateProfile(id, input);
  }

  @GetMapping("/internal/v1/agent-runs/{id}/context")
  Object context(@PathVariable String id) {
    return runs.context(id);
  }

  @GetMapping("/internal/v1/send-commands/{id}")
  Object command(@PathVariable String id) {
    return sends.command(id);
  }
}
