package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.KnowledgeReviewMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeReviewRepository {
  private final KnowledgeReviewMapper mapper;
  private final BusinessStore store;

  public KnowledgeReviewRepository(KnowledgeReviewMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> list(String shop) {
    return mapper.list(shop);
  }

  public Map<String, Object> find(String shop, String id, boolean lock) {
    return DatabaseRows.required(mapper.find(shop, id, lock));
  }

  public Map<String, Object> baseVersion(String shop, String version) {
    return mapper.baseVersion(shop, version);
  }

  public void updateDraft(String shop, String id, String base, String snapshot) {
    mapper.updateDraft(shop, id, base, snapshot);
  }

  public void recordRevision(String shop, String id, String actor, long at) {
    mapper.recordRevision(shop, id, actor, at);
  }

  public List<Map<String, Object>> history(String shop, String id) {
    return mapper.history(shop, id);
  }

  public void insert(String id, String shop, String base, String snapshot, String actor, long at) {
    mapper.insert(id, shop, base, snapshot, actor, at);
  }

  public void transition(
      String shop, String id, String state, String actor, long at, String note, Object version) {
    mapper.transition(state, actor, at, note, version, shop, id);
  }
}
