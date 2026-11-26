package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ShopMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** SQL persistence for Bootstrap. Mutations use the calling service's transaction. */
@Repository
public class ShopRepository {
  private final ShopMapper mapper;
  private final BusinessStore store;

  public ShopRepository(ShopMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> findShopIds(Object shopId) {
    return mapper.findShopIds(shopId);
  }

  public int insertShop(
      Object shopId, Object shopName, Object account, Object platform, Object mode) {
    int count = mapper.insertShop(shopId, shopName, account, platform, mode, "unbound");
    return count;
  }

  public Map<String, Object> findShop(Object shopId) {
    return DatabaseRows.required(mapper.findShop(shopId));
  }

  public int resetConnection(Object shopId) {
    return mapper.resetConnection(shopId);
  }
}
