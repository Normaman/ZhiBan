package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.SendMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for ReplySendService. Mutations use the calling service's transaction. */
@Repository
public class SendRepository {
  private final SendMapper mapper;
  private final BusinessStore store;

  public SendRepository(SendMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> findApprovedCommand(Object commandId) {
    return DatabaseRows.required(mapper.findApprovedCommand(commandId));
  }

  public Map<String, Object> lockCommand(Object commandId) {
    return DatabaseRows.required(mapper.lockCommand(commandId));
  }

  public void lockShop() {
    DatabaseRows.required(mapper.lockShop(store.shopId()));
  }

  public List<Map<String, Object>> platformMessage(Object shop, Object platformId) {
    return mapper.platformMessage(shop, platformId);
  }

  public void bindOutbound(Object id, Object event, Object platformId) {
    mapper.bindOutbound(event, platformId, id);
  }

  public int prepareRetry(Object timestamp, Object commandId) {
    return mapper.prepareRetry(timestamp, commandId);
  }

  public int requeueSendJob(Object timestamp, Object commandId) {
    int updated = mapper.requeueSendJob(timestamp, commandId);
    if (updated > 0)
      store.enqueueExistingJob("send", commandId.toString(), ((Number) timestamp).longValue());
    return updated;
  }

  public int markDraftApproved(Object draftId) {
    return mapper.markDraftApproved(draftId);
  }

  public int recordCommandResult(
      Object state, Object payloadJson, Object errorCode, Object timestamp, Object commandId) {
    return mapper.recordCommandResult(state, payloadJson, errorCode, timestamp, commandId);
  }

  public int recordDraftResult(Object state, Object timestamp, Object draftId) {
    return mapper.recordDraftResult(state, timestamp, draftId);
  }

  public int recordMessageResult(Object state, Object draftId) {
    return mapper.recordMessageResult(state, draftId);
  }

  public List<Map<String, Object>> findOutboundMessage(Object shopId, Object event) {
    return mapper.findOutboundMessage(shopId, event);
  }

  public int insertOutboundMessage(
      Object recordId,
      Object shopId,
      Object conversationId,
      Object platformMessageId,
      Object event,
      Object finalContent,
      Object receivedAt,
      Object payloadHash,
      Object timestamp) {
    return mapper.insertOutboundMessage(
        recordId,
        shopId,
        conversationId,
        platformMessageId,
        event,
        finalContent,
        receivedAt,
        payloadHash,
        timestamp);
  }

  public void insertOutboundPart(
      Object id,
      Object shop,
      Object conversation,
      Object platform,
      Object event,
      String type,
      String content,
      String media,
      long timestamp) {
    mapper.insertOutboundPart(
        id,
        shop,
        conversation,
        platform,
        event,
        type,
        content,
        java.time.OffsetDateTime.now().toString(),
        com.pddcopilot.core.support.WorkflowValues.sha256(content),
        timestamp,
        media);
  }
}
