package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SQL mappings for ConversationRepository; transaction boundaries remain in the calling service.
 */
@Mapper
public interface ConversationMapper {
  List<Map<String, Object>> listConversations(
      @Param("reader") String reader, @Param("shopId") Object shopId);

  Long unreadCount(@Param("conversationId") Object conversationId, @Param("reader") String reader);

  List<Map<String, Object>> readMessageIds(
      @Param("conversationId") Object conversationId, @Param("reader") String reader);

  Map<String, Object> readableMessage(
      @Param("messageId") String messageId, @Param("conversationId") Object conversationId);

  int markRead(
      @Param("messageId") String messageId,
      @Param("reader") String reader,
      @Param("time") long time);

  List<Map<String, Object>> listMessages(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> listDrafts(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> listSendCommands(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> listAgentRuns(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> findPendingSends(@Param("conversationId") Object conversationId);

  int transferConversation(@Param("conversationId") Object conversationId);

  int resumeConversation(@Param("conversationId") String conversationId);

  Long hasUnknownSend(@Param("conversationId") String conversationId);

  int transferDrafts(@Param("conversationId") Object conversationId);

  List<Map<String, Object>> listAuditEvents(@Param("shopId") Object shopId);
}
