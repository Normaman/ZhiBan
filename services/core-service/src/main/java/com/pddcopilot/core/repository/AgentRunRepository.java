package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.AgentRunMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for AgentRunService. Mutations use the calling service's transaction. */
@Repository
public class AgentRunRepository {
  private final AgentRunMapper mapper;
  private final BusinessStore store;

  public AgentRunRepository(AgentRunMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public int insertRun(
      Object run, Object messageId, Object revision, Object version, Object timestamp) {
    return mapper.insertRun(run, messageId, revision, version, timestamp);
  }

  public int markMessageRunning(Object messageId) {
    return mapper.markMessageRunning(messageId);
  }

  public Map<String, Object> findMessage(Object messageId) {
    return DatabaseRows.required(mapper.findMessage(messageId));
  }

  public List<Map<String, Object>> findDecidedDrafts(Object messageId) {
    return mapper.findDecidedDrafts(messageId);
  }

  public List<Map<String, Object>> findActiveRuns(Object messageId, Object revision) {
    return mapper.findActiveRuns(messageId, revision);
  }

  public String latestInbound(Object conversationId) {
    var rows = mapper.latestInbound(conversationId);
    return rows.isEmpty() ? "" : rows.get(0).get("id").toString();
  }

  public List<Map<String, Object>> productDetail(String goods) {
    return mapper.productDetail(store.shopId(), goods);
  }

  public int invalidateDraft(Object messageId) {
    return mapper.invalidateDraft(messageId);
  }

  public Map<String, Object> findShop(Object shopId) {
    return DatabaseRows.required(mapper.findShop(shopId));
  }

  public Map<String, Object> findContextRun(Object runId) {
    return DatabaseRows.required(mapper.findContextRun(runId));
  }

  public Map<String, Object> findContextMessage(Object messageId) {
    return DatabaseRows.required(mapper.findContextMessage(messageId));
  }

  public List<Map<String, Object>> findKnowledgeSnapshot(Object knowledgeVersion, Object shopId) {
    return mapper.findKnowledgeSnapshot(knowledgeVersion, shopId);
  }

  public Map<String, Object> findShopMode(Object shopId) {
    return DatabaseRows.required(mapper.findShopMode(shopId));
  }

  public List<Map<String, Object>> listRecentMessages(Object conversationId, Object before) {
    return mapper.listRecentMessages(conversationId, before);
  }

  public List<Map<String, Object>> priorProductSources(Object conversationId, Object timestamp) {
    return mapper.priorProductSources(conversationId, timestamp);
  }

  public List<Map<String, Object>> priorOrderSources(Object conversationId, Object timestamp) {
    return mapper.priorOrderSources(conversationId, timestamp);
  }

  public List<Map<String, Object>> linkedProductSources(
      Object conversationId, Object platformId, Object cutoff) {
    if (platformId == null) return List.of();
    return mapper.linkedProductSources(conversationId, cutoff, platformId);
  }

  public Map<String, Object> findResultRun(Object runId) {
    return DatabaseRows.required(mapper.findResultRun(runId));
  }

  public Map<String, Object> findResultMessage(Object messageId) {
    return DatabaseRows.required(mapper.findResultMessage(messageId));
  }

  public int completeRun(Object payloadJson, Object runId) {
    return mapper.completeRun(payloadJson, runId);
  }

  public int supersedeRun(Object runId) {
    return mapper.supersedeRun(runId);
  }

  public List<Map<String, Object>> lockMessageDraft(Object messageId) {
    return mapper.lockMessageDraft(messageId);
  }

  public int insertDraft(
      Object draftId,
      Object messageId,
      Object runId,
      Object revision,
      Object conversationRevision,
      Object content,
      Object citations,
      Object riskCodes,
      Object knowledgeVersion,
      Object outcome,
      Object timestamp) {
    return mapper.insertDraft(
        draftId,
        messageId,
        runId,
        revision,
        conversationRevision,
        content,
        citations,
        riskCodes,
        knowledgeVersion,
        outcome,
        timestamp);
  }

  public int updateDraft(
      Object runId,
      Object revision,
      Object conversationRevision,
      Object content,
      Object citations,
      Object riskCodes,
      Object knowledgeVersion,
      Object outcome,
      Object timestamp,
      Object draftId) {
    return mapper.updateDraft(
        runId,
        revision,
        conversationRevision,
        content,
        citations,
        riskCodes,
        knowledgeVersion,
        outcome,
        timestamp,
        draftId);
  }

  public int markMessageWaiting(Object messageId) {
    return mapper.markMessageWaiting(messageId);
  }

  public void saveCards(String draft, String cards) {
    mapper.saveCards(cards, draft);
  }
}
