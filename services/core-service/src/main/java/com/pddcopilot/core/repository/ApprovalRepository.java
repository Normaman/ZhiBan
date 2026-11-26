package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ApprovalMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for ReplyApprovalService. Mutations use the calling service's transaction. */
@Repository
public class ApprovalRepository {
  private final ApprovalMapper mapper;
  private final BusinessStore store;

  public ApprovalRepository(ApprovalMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> findDraftConversation(Object draftId) {
    return DatabaseRows.required(mapper.findDraftConversation(draftId));
  }

  public Map<String, Object> lockDraft(Object draftId) {
    return DatabaseRows.required(mapper.lockDraft(draftId));
  }

  public List<Map<String, Object>> findDecision(Object draftId, Object revision) {
    return mapper.findDecision(draftId, revision);
  }

  public List<Map<String, Object>> findPendingSends(Object conversationId) {
    return mapper.findPendingSends(conversationId);
  }

  public Map<String, Object> findShop(Object shopId) {
    return DatabaseRows.required(mapper.findShop(shopId));
  }

  public int insertDecision(
      Object approvalId,
      Object draftId,
      Object revision,
      Object decision,
      Object text,
      Object actor,
      Object timestamp) {
    return mapper.insertDecision(approvalId, draftId, revision, decision, text, actor, timestamp);
  }

  public int updateDraftState(Object state, Object timestamp, Object draftId) {
    return mapper.updateDraftState(state, timestamp, draftId);
  }

  public int updateMessageState(Object state, Object messageId) {
    return mapper.updateMessageState(state, messageId);
  }

  public int insertSendCommand(
      Object command, Object approvalId, Object draftId, Object revision, Object timestamp) {
    return mapper.insertSendCommand(command, approvalId, draftId, revision, timestamp);
  }

  public int transferConversation(Object conversationId) {
    return mapper.transferConversation(conversationId);
  }

  public void saveCards(String approval, String cards) {
    mapper.saveCards(cards, approval);
  }

  public void replaceCards(String draft, String cards, Object content, long time) {
    mapper.replaceCards(cards, content, time, draft);
  }
}
