package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for AgentRunRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface AgentRunMapper {
  int insertRun(
      @Param("run") Object run,
      @Param("messageId") Object messageId,
      @Param("revision") Object revision,
      @Param("version") Object version,
      @Param("timestamp") Object timestamp);

  int markMessageRunning(@Param("messageId") Object messageId);

  Map<String, Object> findMessage(@Param("messageId") Object messageId);

  List<Map<String, Object>> findDecidedDrafts(@Param("messageId") Object messageId);

  List<Map<String, Object>> findActiveRuns(
      @Param("messageId") Object messageId, @Param("revision") Object revision);

  List<Map<String, Object>> latestInbound(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> productDetail(
      @Param("shopId") String shopId, @Param("goods") String goods);

  int invalidateDraft(@Param("messageId") Object messageId);

  Map<String, Object> findShop(@Param("shopId") Object shopId);

  Map<String, Object> findContextRun(@Param("runId") Object runId);

  Map<String, Object> findContextMessage(@Param("messageId") Object messageId);

  List<Map<String, Object>> findKnowledgeSnapshot(
      @Param("knowledgeVersion") Object knowledgeVersion, @Param("shopId") Object shopId);

  Map<String, Object> findShopMode(@Param("shopId") Object shopId);

  List<Map<String, Object>> listRecentMessages(
      @Param("conversationId") Object conversationId, @Param("before") Object before);

  List<Map<String, Object>> priorProductSources(
      @Param("conversationId") Object conversationId, @Param("timestamp") Object timestamp);

  List<Map<String, Object>> priorOrderSources(
      @Param("conversationId") Object conversationId, @Param("timestamp") Object timestamp);

  List<Map<String, Object>> linkedProductSources(
      @Param("conversationId") Object conversationId,
      @Param("cutoff") Object cutoff,
      @Param("platformId") Object platformId);

  Map<String, Object> findResultRun(@Param("runId") Object runId);

  Map<String, Object> findResultMessage(@Param("messageId") Object messageId);

  int completeRun(@Param("payloadJson") Object payloadJson, @Param("runId") Object runId);

  int supersedeRun(@Param("runId") Object runId);

  List<Map<String, Object>> lockMessageDraft(@Param("messageId") Object messageId);

  int insertDraft(
      @Param("draftId") Object draftId,
      @Param("messageId") Object messageId,
      @Param("runId") Object runId,
      @Param("revision") Object revision,
      @Param("conversationRevision") Object conversationRevision,
      @Param("content") Object content,
      @Param("citations") Object citations,
      @Param("riskCodes") Object riskCodes,
      @Param("knowledgeVersion") Object knowledgeVersion,
      @Param("outcome") Object outcome,
      @Param("timestamp") Object timestamp);

  int updateDraft(
      @Param("runId") Object runId,
      @Param("revision") Object revision,
      @Param("conversationRevision") Object conversationRevision,
      @Param("content") Object content,
      @Param("citations") Object citations,
      @Param("riskCodes") Object riskCodes,
      @Param("knowledgeVersion") Object knowledgeVersion,
      @Param("outcome") Object outcome,
      @Param("timestamp") Object timestamp,
      @Param("draftId") Object draftId);

  int markMessageWaiting(@Param("messageId") Object messageId);

  int saveCards(@Param("cards") String cards, @Param("draft") String draft);
}
