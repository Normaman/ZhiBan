package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ConversationMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for ConversationService. Mutations use the calling service's transaction. */
@Repository
public class ConversationRepository {
  private final ConversationMapper mapper;
  private final BusinessStore store;

  public ConversationRepository(ConversationMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> listConversations(Object shopId, String reader) {
    return mapper.listConversations(reader, shopId);
  }

  public long unreadCount(Object conversationId, String reader) {
    return mapper.unreadCount(conversationId, reader);
  }

  public List<Map<String, Object>> readMessageIds(Object conversationId, String reader) {
    return mapper.readMessageIds(conversationId, reader);
  }

  public Map<String, Object> readableMessage(String messageId, Object conversationId) {
    return DatabaseRows.required(mapper.readableMessage(messageId, conversationId));
  }

  public void markRead(String messageId, String reader, long time) {
    mapper.markRead(messageId, reader, time);
  }

  public List<Map<String, Object>> listMessages(Object conversationId) {
    return mapper.listMessages(conversationId);
  }

  public List<Map<String, Object>> listDrafts(Object conversationId) {
    return mapper.listDrafts(conversationId);
  }

  public List<Map<String, Object>> listSendCommands(Object conversationId) {
    return mapper.listSendCommands(conversationId);
  }

  public List<Map<String, Object>> listAgentRuns(Object conversationId) {
    return mapper.listAgentRuns(conversationId);
  }

  public List<Map<String, Object>> findPendingSends(Object conversationId) {
    return mapper.findPendingSends(conversationId);
  }

  public int transferConversation(Object conversationId) {
    return mapper.transferConversation(conversationId);
  }

  public void resumeConversation(String conversationId) {
    mapper.resumeConversation(conversationId);
  }

  public boolean hasUnknownSend(String conversationId) {
    return mapper.hasUnknownSend(conversationId) > 0;
  }

  public int transferDrafts(Object conversationId) {
    return mapper.transferDrafts(conversationId);
  }

  public List<Map<String, Object>> listAuditEvents(Object shopId) {
    return mapper.listAuditEvents(shopId);
  }
}
