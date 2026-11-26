package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for SendRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface SendMapper {
  Map<String, Object> findApprovedCommand(@Param("commandId") Object commandId);

  Map<String, Object> lockCommand(@Param("commandId") Object commandId);

  Map<String, Object> lockShop(@Param("shopId") String shopId);

  List<Map<String, Object>> platformMessage(
      @Param("shop") Object shop, @Param("platformId") Object platformId);

  int bindOutbound(
      @Param("event") Object event, @Param("platformId") Object platformId, @Param("id") Object id);

  int prepareRetry(@Param("timestamp") Object timestamp, @Param("commandId") Object commandId);

  int requeueSendJob(@Param("timestamp") Object timestamp, @Param("commandId") Object commandId);

  int markDraftApproved(@Param("draftId") Object draftId);

  int recordCommandResult(
      @Param("state") Object state,
      @Param("payloadJson") Object payloadJson,
      @Param("errorCode") Object errorCode,
      @Param("timestamp") Object timestamp,
      @Param("commandId") Object commandId);

  int recordDraftResult(
      @Param("state") Object state,
      @Param("timestamp") Object timestamp,
      @Param("draftId") Object draftId);

  int recordMessageResult(@Param("state") Object state, @Param("draftId") Object draftId);

  List<Map<String, Object>> findOutboundMessage(
      @Param("shopId") Object shopId, @Param("event") Object event);

  int insertOutboundMessage(
      @Param("recordId") Object recordId,
      @Param("shopId") Object shopId,
      @Param("conversationId") Object conversationId,
      @Param("platformMessageId") Object platformMessageId,
      @Param("event") Object event,
      @Param("finalContent") Object finalContent,
      @Param("receivedAt") Object receivedAt,
      @Param("payloadHash") Object payloadHash,
      @Param("timestamp") Object timestamp);

  int insertOutboundPart(
      @Param("id") Object id,
      @Param("shop") Object shop,
      @Param("conversation") Object conversation,
      @Param("platform") Object platform,
      @Param("event") Object event,
      @Param("type") String type,
      @Param("content") String content,
      @Param("value") String value,
      @Param("sha256") Object sha256,
      @Param("timestamp") long timestamp,
      @Param("media") String media);
}
