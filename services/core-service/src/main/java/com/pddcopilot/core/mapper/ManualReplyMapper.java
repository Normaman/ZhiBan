package com.pddcopilot.core.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for ManualReplyRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface ManualReplyMapper {
  List<Map<String, Object>> find(
      @Param("conversation") String conversation, @Param("request") String request);

  List<Map<String, Object>> list(@Param("conversation") String conversation);

  int insertInsertManualReplies(
      @Param("id") String id,
      @Param("conversation") String conversation,
      @Param("request") String request,
      @Param("value") Object value,
      @Param("revision") long revision,
      @Param("content") String content,
      @Param("actor") String actor,
      @Param("timestamp") long timestamp);

  int insertUpdateConversations(
      @Param("timestamp") long timestamp, @Param("conversation") String conversation);

  int enqueue(
      @Param("command") String command,
      @Param("reply") String reply,
      @Param("timestamp") long timestamp);
}
