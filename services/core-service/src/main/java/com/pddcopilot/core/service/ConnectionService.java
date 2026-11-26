package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publish connector state and expose stale connections as offline. */
@Service
public class ConnectionService {
  private final BusinessStore store;
  private final ConnectionRepository repository;
  private final SendPolicy sendPolicy;

  public ConnectionService(
      BusinessStore store, ConnectionRepository repository, SendPolicy sendPolicy) {
    this.repository = repository;
    this.store = store;
    this.sendPolicy = sendPolicy;
  }

  public List<Map<String, Object>> shops() {
    var rows = repository.findShop(store.shopId());
    rows.forEach(
        shop -> {
          shop.put("send_enabled", sendPolicy.shopEnabled());
          if (nowMillis() - asLong(shop.get("connection_seen_at")) > CONNECTOR_STALE_AFTER_MS
              && !Set.of("stopped", "failed", "disconnected")
                  .contains(shop.get("connection_state")))
            shop.put("connection_state", "disconnected");
        });
    return rows;
  }

  @Transactional
  public void update(String shop, Contracts.Connection input) {
    store.requireShopAccess(shop);
    var old = repository.lockShop(shop);
    if (input.epoch().equals(old.get("connection_epoch"))
        && input.sequence() <= asLong(old.get("connection_sequence"))) return;
    repository.updateConnection(
        input.state(), nowMillis(), input.epoch(), input.sequence(), input.errorCode(), shop);
  }

  @Transactional
  public void updateProfile(String shop, Contracts.ShopProfile profile) {
    store.requireShopAccess(shop);
    var bound = repository.lockShop(shop);
    if (!profile.platformShopId().equals(bound.get("platform_shop_id"))
        || !profile.connectorAccountId().equals(bound.get("connector_account_id"))
        || !"bound".equals(bound.get("binding_state")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "ACCOUNT_MISMATCH");
    repository.updateProfile(
        shop, profile.name().trim(), profile.avatarUrl(), profile.mainCategory(), nowMillis());
  }
}
