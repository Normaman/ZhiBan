package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.KnowledgeMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for KnowledgeService. Mutations use the calling service's transaction. */
@Repository
public class KnowledgeRepository {
  private final KnowledgeMapper mapper;
  private final BusinessStore store;

  public KnowledgeRepository(KnowledgeMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> lockShop(Object shop) {
    return DatabaseRows.required(mapper.lockShop(shop));
  }

  public List<Map<String, Object>> findVersion(Object version) {
    return mapper.findVersion(store.shopId(), version);
  }

  public int insertVersion(Object version, Object shop, Object snapshot, Object timestamp) {
    return insertVersion(version, shop, snapshot, timestamp, version);
  }

  public int insertVersion(
      Object version, Object shop, Object snapshot, Object timestamp, Object label) {
    return mapper.insertVersion(version, shop, snapshot, timestamp, label);
  }

  public int activateVersion(Object version, Object shop) {
    return mapper.activateVersion(version, shop);
  }

  public int insertProductSnapshot(
      Object snapshot, Object shop, Object payloadJson, Object timestamp) {
    return mapper.insertProductSnapshot(snapshot, shop, payloadJson, timestamp);
  }

  public void checkedProducts(Object id, long now) {
    mapper.checkedProducts(now, id);
  }

  public List<Map<String, Object>> listVersions(Object shop) {
    return mapper.listVersions(shop);
  }

  public List<Map<String, Object>> activeVersion(String shop) {
    return mapper.activeVersion(shop);
  }

  public List<Map<String, Object>> findLatestProducts(Object shop) {
    return mapper.findLatestProducts(shop);
  }
}
