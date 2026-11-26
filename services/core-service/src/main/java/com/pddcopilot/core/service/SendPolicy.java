package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.ApprovalRepository;
import com.pddcopilot.core.repository.BusinessStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Sending permission is evaluated again on each action; trial scope expires automatically. */
@Service
public class SendPolicy {
  private final BusinessStore store;
  private final ApprovalRepository shops;
  private final boolean enabled;
  private final String configuredShop;
  private final String conversation;
  private final long expiresAt;

  public SendPolicy(
      BusinessStore store,
      ApprovalRepository shops,
      @Value("${copilot.live-send-enabled:false}") boolean enabled,
      @Value("${copilot.live-send-shop:}") String configuredShop,
      @Value("${copilot.live-send-conversation:}") String conversation,
      @Value("${copilot.live-send-expires-at:0}") long expiresAt) {
    this.store = store;
    this.shops = shops;
    this.enabled = enabled;
    this.configuredShop = configuredShop;
    this.conversation = conversation;
    this.expiresAt = expiresAt;
  }

  public boolean enabledFor(String conversationId) {
    return shopEnabled() && ("*".equals(conversation) || conversation.equals(conversationId));
  }

  public boolean shopEnabled() {
    return enabled
            && ("*".equals(configuredShop) || configuredShop.equals(store.shopId()))
            && !conversation.isBlank()
            && (expiresAt == -1 || nowMillis() < expiresAt);
  }

  public void requireOnline(String conversationId) {
    if (!enabledFor(conversationId)) throw conflict("LIVE_SEND_NOT_ENABLED");
    var shop = shops.findShop(store.shopId());
    if (!"online".equals(shop.get("connection_state"))
        || nowMillis() - asLong(shop.get("connection_seen_at")) > CONNECTOR_STALE_AFTER_MS)
      throw conflict("CONNECTOR_OFFLINE");
  }
}
