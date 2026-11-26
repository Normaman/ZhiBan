package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.PddSendJournalMapper;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-part send intent; an interrupted in-flight part is never sent again implicitly. */
@Repository
public class PddSendJournal {
  private final PddSendJournalMapper mapper;

  public record Entry(boolean created, String state, Map<String, Object> receipt) {}

  private final BusinessStore store;

  public PddSendJournal(PddSendJournalMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public Entry begin(String commandId, int attempt, String key, String payloadHash) {
    int inserted =
        mapper.beginInsertPddSendParts(
            store.shopId(),
            commandId,
            attempt,
            key,
            payloadHash,
            com.pddcopilot.core.support.WorkflowValues.nowMillis());
    var row = DatabaseRows.required(mapper.beginSelectPddSendParts(commandId, attempt, key));
    if (!payloadHash.equals(row.get("payload_hash")))
      throw new IllegalStateException("SEND_PART_IDENTITY_CONFLICT");
    Map<String, Object> receipt = null;
    if (row.get("receipt") instanceof String value && !value.isBlank()) {
      Object parsed = store.fromJson(value);
      if (parsed instanceof Map<?, ?> map) receipt = (Map<String, Object>) map;
    }
    return new Entry(inserted == 1, row.get("state").toString(), receipt);
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Entry find(String commandId, int attempt, String key, String payloadHash) {
    var rows = mapper.find(store.shopId(), commandId, attempt, key);
    if (rows.isEmpty()) return null;
    var row = rows.get(0);
    if (!payloadHash.equals(row.get("payload_hash")))
      throw new IllegalStateException("SEND_PART_IDENTITY_CONFLICT");
    Map<String, Object> receipt = null;
    if (row.get("receipt") instanceof String value && !value.isBlank()) {
      Object parsed = store.fromJson(value);
      if (parsed instanceof Map<?, ?> map) receipt = (Map<String, Object>) map;
    }
    return new Entry(false, row.get("state").toString(), receipt);
  }

  @Transactional
  public void finish(String commandId, int attempt, String key, Map<String, Object> receipt) {
    String state = String.valueOf(receipt.get("state"));
    if (!Set.of("sent", "send_failed", "send_unknown").contains(state)) state = "send_unknown";
    int updated =
        mapper.finish(
            state,
            store.toJson(receipt),
            com.pddcopilot.core.support.WorkflowValues.nowMillis(),
            store.shopId(),
            commandId,
            attempt,
            key);
    if (updated != 1) throw new IllegalStateException("SEND_PART_JOURNAL_CONFLICT");
  }
}
