package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ApprovalRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ApprovalMapper {
  Map<String, Object> findDraftConversation(@Param("draftId") Object draftId);

  Map<String, Object> lockDraft(@Param("draftId") Object draftId);

  List<Map<String, Object>> findDecision(
      @Param("draftId") Object draftId, @Param("revision") Object revision);

  List<Map<String, Object>> findPendingSends(@Param("conversationId") Object conversationId);

  Map<String, Object> findShop(@Param("shopId") Object shopId);

  int insertDecision(
      @Param("approvalId") Object approvalId,
      @Param("draftId") Object draftId,
      @Param("revision") Object revision,
      @Param("decision") Object decision,
      @Param("text") Object text,
      @Param("actor") Object actor,
      @Param("timestamp") Object timestamp);

  int updateDraftState(
      @Param("state") Object state,
      @Param("timestamp") Object timestamp,
      @Param("draftId") Object draftId);

  int updateMessageState(@Param("state") Object state, @Param("messageId") Object messageId);

  int insertSendCommand(
      @Param("command") Object command,
      @Param("approvalId") Object approvalId,
      @Param("draftId") Object draftId,
      @Param("revision") Object revision,
      @Param("timestamp") Object timestamp);

  int transferConversation(@Param("conversationId") Object conversationId);

  int saveCards(@Param("cards") String cards, @Param("approval") String approval);

  int replaceCards(
      @Param("cards") String cards,
      @Param("content") Object content,
      @Param("time") long time,
      @Param("draft") String draft);
}
