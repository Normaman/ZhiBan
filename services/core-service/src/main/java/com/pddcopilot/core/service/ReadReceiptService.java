package com.pddcopilot.core.service;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ReadReceiptRepository;
import java.math.BigInteger;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Platform buyer receipts are independent of workbench viewing and delivery success. */
@Service
public class ReadReceiptService {
  private final BusinessStore store;
  private final ReadReceiptRepository repository;

  public ReadReceiptService(BusinessStore store, ReadReceiptRepository repository) {
    this.store = store;
    this.repository = repository;
  }

  @Transactional
  public Map<String, Object> receive(Contracts.ReadReceipt input) {
    store.requireShopAccess(input.shopId());
    var shop = repository.lockShop(input.shopId());
    if (!shop.get("platform_shop_id").equals(input.platformShopId())
        || !shop.get("connector_account_id").equals(input.connectorAccountId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_MISMATCH");
    var old = repository.find(input.shopId(), input.buyerRef());
    if (old.isEmpty())
      repository.insert(
          input.shopId(),
          input.buyerRef(),
          input.userLastRead(),
          input.minSupportedMessageId(),
          input.observedAt().toInstant().toEpochMilli());
    else {
      var row = old.get(0);
      String last = maximum((String) row.get("user_last_read"), input.userLastRead());
      String minimum =
          maximum((String) row.get("min_supported_message_id"), input.minSupportedMessageId());
      long observed =
          Math.max(
              ((Number) row.get("observed_at")).longValue(),
              input.observedAt().toInstant().toEpochMilli());
      repository.update(input.shopId(), input.buyerRef(), last, minimum, observed);
    }
    return Map.of("state", "recorded");
  }

  private static String maximum(String first, String second) {
    if (first == null) return second;
    if (second == null) return first;
    return new BigInteger(first).compareTo(new BigInteger(second)) >= 0 ? first : second;
  }

  public Map<String, Object> marker(String buyer) {
    var rows = repository.find(store.shopId(), buyer);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public static String state(String messageId, Map<String, Object> marker) {
    if (marker == null || messageId == null || !messageId.matches("[0-9]{1,200}")) return "unknown";
    var id = new BigInteger(messageId);
    // Matches the current merchant page's supported interval and comparison;
    // marker values are message boundaries, never assumed to be timestamps.
    if (id.compareTo(new BigInteger((String) marker.get("min_supported_message_id"))) <= 0)
      return "unknown";
    String last = (String) marker.get("user_last_read");
    return last != null && id.compareTo(new BigInteger(last)) <= 0 ? "read" : "unread";
  }
}
