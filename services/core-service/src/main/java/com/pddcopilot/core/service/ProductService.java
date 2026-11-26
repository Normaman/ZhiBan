package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Platform metadata remains a dated snapshot; synchronization never publishes or edits goods. */
@Service
public class ProductService {
  private final BusinessStore store;
  private final ProductRepository repository;
  private final KnowledgeRepository knowledge;

  public ProductService(
      BusinessStore store, ProductRepository repository, KnowledgeRepository knowledge) {
    this.store = store;
    this.repository = repository;
    this.knowledge = knowledge;
  }

  public List<Map<String, Object>> catalog() {
    var rows = knowledge.findLatestProducts(store.shopId());
    if (rows.isEmpty()) return List.of();
    Object parsed = store.fromJson(rows.get(0).get("products").toString());
    if (!(parsed instanceof List<?> entries))
      throw new IllegalStateException("INVALID_PRODUCT_SNAPSHOT");
    var products = new ArrayList<Map<String, Object>>();
    for (Object entry : entries)
      if (entry instanceof Map<?, ?> raw) {
        var product = new LinkedHashMap<String, Object>();
        raw.forEach((k, v) -> product.put(k.toString(), v));
        products.add(product);
      }
    return products;
  }

  private void requireProduct(String goods) {
    if (catalog().stream().noneMatch(p -> goods.equals(Objects.toString(p.get("goods_id"), ""))))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
  }

  @Transactional
  public Map<String, Object> start(String actor) {
    return start(actor, false);
  }

  @Transactional
  public Map<String, Object> start(String actor, boolean force) {
    var shop = knowledge.lockShop(store.shopId());
    if (!"bound".equals(shop.get("binding_state"))) throw conflict("CONNECTOR_NOT_CONFIGURED");
    var active = repository.activeRun();
    if (!active.isEmpty()) return active.get(0);
    var catalog = catalog();
    var goods =
        catalog.stream()
            .map(p -> Objects.toString(p.get("goods_id"), ""))
            .filter(id -> !id.isBlank())
            .distinct()
            .toList();
    if (goods.isEmpty()) throw conflict("PRODUCT_SNAPSHOT_REQUIRED");
    String run = newId();
    repository.insertRun(run, goods.size(), nowMillis());
    for (String id : goods) {
      String item = newId();
      repository.insertItem(item, run, id);
      var product =
          catalog.stream()
              .filter(p -> id.equals(Objects.toString(p.get("goods_id"), "")))
              .findFirst()
              .orElseThrow();
      String hash = com.pddcopilot.core.support.ProductFingerprint.hash(store, product);
      repository.sourceHash(item, hash);
      if (!force && reusable(id, product)) repository.skip(item);
      else store.enqueueJob("product_detail", item);
    }
    repository.updateProgress(run, nowMillis());
    store.recordAudit("products.details_started", run, actor, run);
    return repository.run(run);
  }

  public boolean detailsNeedSync() {
    if (!repository.activeRun().isEmpty()) return false;
    for (var product : catalog()) {
      String id = Objects.toString(product.get("goods_id"), "");
      if (!id.isBlank() && !reusable(id, product)) return true;
    }
    return false;
  }

  private boolean reusable(String goods, Map<String, Object> product) {
    String hash = com.pddcopilot.core.support.ProductFingerprint.hash(store, product);
    var cached = repository.details(goods);
    return !cached.isEmpty()
        && hash.equals(cached.get(0).get("source_hash"))
        && nowMillis() - asLong(cached.get(0).get("updated_at")) < 6 * 60 * 60 * 1000L
        && store.fromJson(cached.get(0).get("payload").toString()) instanceof Map<?, ?> data
        && data.get("detail_schema_version") instanceof Number schema
        && schema.intValue() >= 3;
  }

  public Map<String, Object> status() {
    var rows = repository.latestRun();
    return rows.isEmpty()
        ? Map.of("state", "idle", "total", 0, "completed", 0, "failed", 0)
        : rows.get(0);
  }

  public Map<String, Object> detail(String goods) {
    requireProduct(goods);
    var rows = repository.details(goods);
    var out = new LinkedHashMap<String, Object>();
    out.put(
        "detail", rows.isEmpty() ? null : store.fromJson(rows.get(0).get("payload").toString()));
    return out;
  }

  @Transactional
  public void captureAgentDetail(Map<String, Object> response) {
    if (!store.shopId().equals(response.get("shop_id"))
        || !(response.get("detail") instanceof Map<?, ?> raw))
      throw new IllegalStateException("PRODUCT_DETAIL_SCOPE_MISMATCH");
    var detail = new LinkedHashMap<String, Object>();
    raw.forEach((key, value) -> detail.put(key.toString(), value));
    String goods = Objects.toString(detail.get("goods_id"), "");
    var product = catalog().stream()
        .filter(p -> goods.equals(Objects.toString(p.get("goods_id"), "")))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("PRODUCT_NOT_IN_SHOP_CATALOG"));
    if (!(detail.get("category_path") instanceof List<?> categories)
        || !(detail.get("attributes") instanceof List<?> attributes)
        || !(detail.get("sku_count") instanceof Number skuCount)
        || !(detail.get("fetched_at") instanceof String fetchedAt))
      throw new IllegalStateException("PRODUCT_DETAIL_SCHEMA_UNSUPPORTED");

    String json = store.toJson(detail);
    if (json.length() > 2_000_000) throw new IllegalStateException("PRODUCT_DETAIL_TOO_LARGE");
    knowledge.lockShop(store.shopId());
    if (!olderThanStored(goods, detail))
      repository.saveDetail(
          goods, json, store.toJson(categories), skuCount.intValue(), attributes.size(), nowMillis());
    repository.savedSource(goods, com.pddcopilot.core.support.ProductFingerprint.hash(store, product));
  }

  public Map<String, Object> item(String id) {
    var item = repository.item(id);
    store.requireShopAccess(item.get("shop_id").toString());
    return item;
  }

  @Transactional
  public void complete(String id, Map<String, Object> result) {
    var item = item(id);
    String goods = item.get("goods_id").toString();
    requireProduct(goods);
    if (!store.shopId().equals(result.get("shop_id"))
        || !(result.get("detail") instanceof Map<?, ?> detail)
        || !goods.equals(detail.get("goods_id"))
        || !(detail.get("category_path") instanceof List<?> categories)
        || !(detail.get("attributes") instanceof List<?> attributes)
        || !(detail.get("sku_count") instanceof Number skuCount))
      throw new IllegalStateException("PRODUCT_DETAIL_SCOPE_MISMATCH");
    String json = store.toJson(detail);
    if (json.length() > 2_000_000) throw new IllegalStateException("PRODUCT_DETAIL_TOO_LARGE");
    knowledge.lockShop(store.shopId());
    repository.lockRun(item.get("run_id"));
    if (!olderThanStored(goods, detail))
      repository.saveDetail(
          goods, json, store.toJson(categories), skuCount.intValue(), attributes.size(), nowMillis());
    repository.savedSource(goods, Objects.toString(item.get("source_hash"), null));
    repository.finishItem(id, true);
    repository.updateProgress(item.get("run_id"), nowMillis());
  }

  private boolean olderThanStored(String goods, Map<?, ?> incoming) {
    var cached = repository.details(goods);
    if (cached.isEmpty() || !(incoming.get("fetched_at") instanceof String fetchedAt)) return false;
    Object parsed = store.fromJson(cached.get(0).get("payload").toString());
    if (!(parsed instanceof Map<?, ?> prior) || !(prior.get("fetched_at") instanceof String previousAt))
      return false;
    try {
      return java.time.Instant.parse(previousAt).isAfter(java.time.Instant.parse(fetchedAt));
    } catch (java.time.format.DateTimeParseException ignored) {
      return false;
    }
  }

  @Transactional
  public void fail(String id) {
    var item = item(id);
    repository.lockRun(item.get("run_id"));
    repository.finishItem(id, false);
    repository.updateProgress(item.get("run_id"), nowMillis());
  }

  public List<Map<String, Object>> decorateSnapshots(
      String shop, List<Map<String, Object>> snapshots) {
    store.requireShopAccess(shop);
    if (snapshots.isEmpty()) return snapshots;
    var details = new HashMap<String, Map<String, Object>>();
    for (var row : repository.summaries(shop)) details.put(row.get("goods_id").toString(), row);
    var products = catalog();
    for (var product : products) {
      var detail = details.get(Objects.toString(product.get("goods_id"), ""));
      if (detail != null) {
        product.put("category_path", store.fromJson(detail.get("category_path").toString()));
        product.put("sku_count", detail.get("sku_count"));
        product.put("attributes_count", detail.get("attributes_count"));
        product.put("detail_updated_at", detail.get("updated_at"));
      }
    }
    snapshots.get(0).put("products", store.toJson(products));
    return snapshots;
  }
}
