package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ManualReplyMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ManualReplyRepository {
  private final ManualReplyMapper mapper;
  private final BusinessStore store;

  public ManualReplyRepository(ManualReplyMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> find(String conversation, String request) {
    return mapper.find(conversation, request);
  }

  public List<Map<String, Object>> list(String conversation) {
    return mapper.list(conversation);
  }

  public void insert(
      String id,
      String conversation,
      String request,
      long revision,
      String content,
      String actor,
      long timestamp) {
    mapper.insertInsertManualReplies(
        id, conversation, request, revision + 1, revision, content, actor, timestamp);
    mapper.insertUpdateConversations(timestamp, conversation);
  }

  public void enqueue(String command, String reply, long timestamp) {
    mapper.enqueue(command, reply, timestamp);
  }
}
