package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ConnectionMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for ConnectionService. Mutations use the calling service's transaction. */
@Repository
public class ConnectionRepository {
  private final ConnectionMapper mapper;
  private final BusinessStore store;

  public ConnectionRepository(ConnectionMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> findShop(Object shopId) {
    return mapper.findShop(shopId);
  }

  public List<Map<String, Object>> findBoundLiveShops() {
    return mapper.findBoundLiveShops();
  }

  public Map<String, Object> lockShop(Object shop) {
    return DatabaseRows.required(mapper.lockShop(shop));
  }

  public int updateConnection(
      Object state,
      Object timestamp,
      Object epoch,
      Object sequence,
      Object errorCode,
      Object shop) {
    return mapper.updateConnection(state, timestamp, epoch, sequence, errorCode, shop);
  }

  public void updateProfile(
      String shop, String name, String avatar, String category, long observedAt) {
    mapper.updateProfile(name, avatar, category, observedAt, shop);
  }
}
