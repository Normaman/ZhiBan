package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for InboxRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface InboxMapper {
  Integer isApprovedPart(
      @Param("conversation") String conversation, @Param("content") String content);

  int mergeOutboundCard(
      @Param("content") String content,
      @Param("media") String media,
      @Param("digest") String digest,
      @Param("id") Object id);

  Map<String, Object> lockShop(@Param("shopId") Object shopId);

  List<Map<String, Object>> findDuplicateMessages(
      @Param("shopId") Object shopId,
      @Param("eventId") Object eventId,
      @Param("platformMessageId") Object platformMessageId);

  List<Map<String, Object>> lockBuyerConversation(
      @Param("shopId") Object shopId, @Param("buyerRef") Object buyerRef);

  int insertConversation(
      @Param("conversationId") Object conversationId,
      @Param("shopId") Object shopId,
      @Param("buyerRef") Object buyerRef,
      @Param("timestamp") Object timestamp);

  int advanceConversation(
      @Param("timestamp") Object timestamp, @Param("conversationId") Object conversationId);

  int invalidateDrafts(@Param("conversationId") Object conversationId);

  int insertInboundMessage(
      @Param("messageId") Object messageId,
      @Param("shopId") Object shopId,
      @Param("conversationId") Object conversationId,
      @Param("platformMessageId") Object platformMessageId,
      @Param("eventId") Object eventId,
      @Param("direction") Object direction,
      @Param("messageType") Object messageType,
      @Param("content") Object content,
      @Param("occurredAt") Object occurredAt,
      @Param("receivedAt") Object receivedAt,
      @Param("digest") Object digest,
      @Param("timestamp") Object timestamp);

  int markHumanReview(@Param("messageId") Object messageId);

  int saveMedia(
      @Param("media") String media,
      @Param("related") Object related,
      @Param("messageId") Object messageId);

  int markOutboundSent(@Param("messageId") Object messageId);

  List<Map<String, Object>> generatedQuestionForSource(
      @Param("conversation") Object conversation, @Param("platformId") Object platformId);

  List<Map<String, Object>> questionForProductCard(
      @Param("conversation") Object conversation, @Param("platformId") Object platformId);
}
