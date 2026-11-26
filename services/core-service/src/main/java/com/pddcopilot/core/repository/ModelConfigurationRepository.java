package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ModelConfigurationMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ModelConfigurationRepository {
  private final ModelConfigurationMapper mapper;
  private final BusinessStore store;

  public ModelConfigurationRepository(ModelConfigurationMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> settings() {
    var rows = mapper.settings(store.shopId());
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void lockShop() {
    DatabaseRows.required(mapper.lockShop(store.shopId()));
  }

  public Map<String, Object> version(String id) {
    return DatabaseRows.required(mapper.version(id, store.shopId()));
  }

  public void insert(
      String id,
      long version,
      String url,
      String model,
      String encryptedKey,
      String hint,
      int rounds,
      int timeout,
      long now) {
    mapper.insert(
        id, store.shopId(), version, url, model, encryptedKey, hint, rounds, timeout, now);
  }

  public void saveSettings(
      long revision, Object latest, Object active, boolean environmentFallback) {
    if (settings() == null)
      mapper.saveSettingsInsertModelSettings(
          store.shopId(), revision, latest, active, environmentFallback);
    else
      mapper.saveSettingsUpdateModelSettings(
          revision, latest, active, environmentFallback, store.shopId());
  }

  public void tested(String id, boolean passed, Object errorCode, long now) {
    mapper.tested(passed ? "passed" : "failed", errorCode, now, id, store.shopId());
  }
}
