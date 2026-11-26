package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.ProductMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
  private final ProductMapper mapper;
  private final BusinessStore store;

  public ProductRepository(ProductMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public List<Map<String, Object>> details(String goods) {
    return mapper.details(store.shopId(), goods);
  }

  public List<Map<String, Object>> summaries(String shop) {
    return mapper.summaries(shop);
  }

  public List<Map<String, Object>> latestRun() {
    return mapper.latestRun(store.shopId());
  }

  public Map<String, Object> run(String id) {
    return DatabaseRows.required(mapper.run(id, store.shopId()));
  }

  public List<Map<String, Object>> activeRun() {
    return mapper.activeRun(store.shopId());
  }

  public void insertRun(String id, int count, long now) {
    mapper.insertRun(id, store.shopId(), count, now);
  }

  public void insertItem(String id, String run, String goods) {
    mapper.insertItem(id, run, goods);
  }

  public void sourceHash(String item, String hash) {
    mapper.sourceHash(hash, item);
  }

  public void skip(String item) {
    mapper.skip(item);
  }

  public void savedSource(String goods, String hash) {
    mapper.savedSource(hash, store.shopId(), goods);
  }

  public Map<String, Object> item(String id) {
    return DatabaseRows.required(mapper.item(id));
  }

  public void markRunning(Object id) {
    mapper.markRunning(id);
  }

  public void lockRun(Object id) {
    DatabaseRows.required(mapper.lockRun(id));
  }

  public void finishItem(Object id, boolean success) {
    mapper.finishItem(
        success ? "completed" : "failed", success ? null : "PRODUCT_DETAIL_UNAVAILABLE", id);
  }

  public void updateProgress(Object run, long now) {
    var totals = DatabaseRows.required(mapper.countItemStates(run));
    long total = ((Number) totals.get("total")).longValue(),
        done = ((Number) totals.get("completed")).longValue(),
        skipped = ((Number) totals.get("skipped")).longValue(),
        failed = ((Number) totals.get("failed")).longValue();
    mapper.updateProgress(
        done,
        skipped,
        failed,
        done + failed + skipped < total
            ? "running"
            : failed > 0 ? "completed_with_errors" : "completed",
        now,
        run);
  }

  public void saveDetail(
      String goods, String payload, String category, int skus, int attributes, long now) {
    if (details(goods).isEmpty())
      mapper.insertDetail(store.shopId(), goods, payload, category, skus, attributes, now);
    else mapper.updateDetail(payload, category, skus, attributes, now, store.shopId(), goods);
  }
}
