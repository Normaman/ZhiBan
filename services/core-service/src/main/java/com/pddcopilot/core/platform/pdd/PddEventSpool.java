package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.mapper.PddEventSpoolMapper;
import com.pddcopilot.core.service.InboxService;
import com.pddcopilot.core.service.ReadReceiptService;
import com.pddcopilot.core.support.ShopScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable, idempotent handoff from the socket reader into the existing inbox pipeline. */
@Service
public class PddEventSpool {
  private final PddEventSpoolMapper mapper;
  private final ObjectMapper json;
  private final InboxService inbox;
  private final ReadReceiptService receipts;

  public PddEventSpool(
      PddEventSpoolMapper mapper,
      ObjectMapper json,
      InboxService inbox,
      ReadReceiptService receipts) {
    this.mapper = mapper;
    this.json = json;
    this.inbox = inbox;
    this.receipts = receipts;
  }

  public void enqueue(String shopId, String eventKey, Contracts.Inbound message) {
    insert(shopId, eventKey, "message", message);
  }

  public void enqueue(String shopId, String eventKey, Contracts.ReadReceipt receipt) {
    insert(shopId, eventKey, "read_receipt", receipt);
  }

  private void insert(String shopId, String eventKey, String type, Object payload) {
    try {
      mapper.insert(
          UUID.randomUUID().toString(),
          shopId,
          eventKey,
          type,
          json.writeValueAsString(payload),
          System.currentTimeMillis(),
          System.currentTimeMillis());
    } catch (DuplicateKeyException duplicate) {
      // Platform redelivery is expected; the durable row is already queued or delivered.
    } catch (Exception error) {
      throw new IllegalStateException("PDD_EVENT_PERSIST_FAILED");
    }
  }

  @Scheduled(fixedDelayString = "${copilot.pdd-spool-poll-ms:500}")
  public void drain() {
    List<Map<String, Object>> rows = mapper.drain(System.currentTimeMillis());
    for (Map<String, Object> row : rows) deliver(row);
  }

  private void deliver(Map<String, Object> row) {
    String shopId = row.get("shop_id").toString();
    try (var scope = ShopScope.open(shopId)) {
      String type = row.get("event_type").toString();
      String payload = row.get("payload").toString();
      if ("message".equals(type)) {
        Contracts.Inbound message = json.readValue(payload, Contracts.Inbound.class);
        String key =
            message.platformMessageId() == null
                ? "event:" + shopId + ":" + message.eventId()
                : "pdd:" + shopId + ":" + message.platformMessageId();
        inbox.receive(message, key);
      } else if ("read_receipt".equals(type)) {
        receipts.receive(json.readValue(payload, Contracts.ReadReceipt.class));
      } else {
        throw new IllegalStateException("PDD_EVENT_TYPE_UNKNOWN");
      }
      mapper.deliverUpdatePddEventSpool(System.currentTimeMillis(), row.get("id"));
    } catch (Exception error) {
      int attempts = ((Number) row.get("attempts")).intValue() + 1;
      long delay = Math.min(60_000L, 500L << Math.min(attempts, 7));
      String code =
          error instanceof org.springframework.web.server.ResponseStatusException response
              ? safeCode(response.getReason())
              : "EVENT_PROCESSING_FAILED";
      mapper.deliverUpdatePddEventSpool2(System.currentTimeMillis() + delay, code, row.get("id"));
    }
  }

  private String safeCode(String value) {
    return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : "EVENT_PROCESSING_FAILED";
  }
}
