package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.InboxMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for InboxService. Mutations use the calling service's transaction. */
@Repository
public class InboxRepository {
  private final InboxMapper mapper;

  public boolean isApprovedPart(String conversation, String content) {
    return mapper.isApprovedPart(conversation, content) > 0;
  }

  public void mergeOutboundCard(Object id, String content, String media, String digest) {
    mapper.mergeOutboundCard(content, media, digest, id);
  }

  private final BusinessStore store;

  public InboxRepository(InboxMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> lockShop(Object shopId) {
    return DatabaseRows.required(mapper.lockShop(shopId));
  }

  public List<Map<String, Object>> findDuplicateMessages(
      Object shopId, Object eventId, Object platformMessageId) {
    return mapper.findDuplicateMessages(shopId, eventId, platformMessageId);
  }

  public List<Map<String, Object>> lockBuyerConversation(Object shopId, Object buyerRef) {
    return mapper.lockBuyerConversation(shopId, buyerRef);
  }

  public int insertConversation(
      Object conversationId, Object shopId, Object buyerRef, Object timestamp) {
    return mapper.insertConversation(conversationId, shopId, buyerRef, timestamp);
  }

  public int advanceConversation(Object timestamp, Object conversationId) {
    return mapper.advanceConversation(timestamp, conversationId);
  }

  public int invalidateDrafts(Object conversationId) {
    return mapper.invalidateDrafts(conversationId);
  }

  public int insertInboundMessage(
      Object messageId,
      Object shopId,
      Object conversationId,
      Object platformMessageId,
      Object eventId,
      Object direction,
      Object messageType,
      Object content,
      Object occurredAt,
      Object receivedAt,
      Object digest,
      Object timestamp) {
    return mapper.insertInboundMessage(
        messageId,
        shopId,
        conversationId,
        platformMessageId,
        eventId,
        direction,
        messageType,
        content,
        occurredAt,
        receivedAt,
        digest,
        timestamp);
  }

  public int markHumanReview(Object messageId) {
    return mapper.markHumanReview(messageId);
  }

  public void saveMedia(Object messageId, String media) {
    Object related = ((Map<?, ?>) store.fromJson(media)).get("related_platform_message_id");
    mapper.saveMedia(media, related, messageId);
  }

  public void markOutboundSent(Object messageId) {
    mapper.markOutboundSent(messageId);
  }

  public List<Map<String, Object>> generatedQuestionForSource(
      Object conversation, Object platformId) {
    return mapper.generatedQuestionForSource(conversation, platformId);
  }

  public List<Map<String, Object>> questionForProductCard(Object conversation, Object platformId) {
    return mapper.questionForProductCard(conversation, platformId);
  }
}
