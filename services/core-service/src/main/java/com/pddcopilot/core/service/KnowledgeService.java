package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.KnowledgeRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Publish immutable knowledge versions and retain timestamped product snapshots. */
@Service
public class KnowledgeService {
  private final BusinessStore store;
  private final KnowledgeRepository repository;
  private final ProductService products;

  public KnowledgeService(
      BusinessStore store, KnowledgeRepository repository, ProductService products) {
    this.repository = repository;
    this.store = store;
    this.products = products;
  }

  @Transactional
  public Map<String, Object> publish(String shop, Contracts.Knowledge input, String actor) {
    store.requireShopAccess(shop);
    var boundShop = repository.lockShop(shop);
    if (input.entries().isEmpty() && input.baseVersion() == null)
      throw conflict("KNOWLEDGE_VERSION_REQUIRED");
    if (input.baseVersion() != null
        && !input.baseVersion().equals(Objects.toString(boundShop.get("knowledge_version"), "")))
      throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    validateEntries(shop, boundShop, input.entries(), true);
    String snapshot = store.toJson(input.entries());
    var old = repository.findVersion(input.version());
    String version = old.isEmpty() ? newId() : old.get(0).get("id").toString();
    if (!old.isEmpty()) {
      if (!snapshot.equals(old.get(0).get("snapshot")) || !shop.equals(old.get(0).get("shop_id")))
        throw conflict("KNOWLEDGE_VERSION_IMMUTABLE");
    } else repository.insertVersion(version, shop, snapshot, nowMillis(), input.version());
    repository.activateVersion(version, shop);
    store.recordAudit("knowledge.published", version, actor, version);
    return Map.of("knowledge_version", version);
  }

  void validateEntries(
      String shop, Map<String, Object> boundShop, List<Map<String, Object>> entries,
      boolean checkProductCatalog) {
    if (entries.size() > 500)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_LIMIT_EXCEEDED");
    Set<String> ids = new HashSet<>();
    var productIds = new HashSet<String>();
    for (var snapshot : repository.findLatestProducts(shop)) {
      if (store.fromJson((String) snapshot.get("products")) instanceof List<?> products) {
        for (Object item : products)
          if (item instanceof Map<?, ?> product && product.get("goods_id") != null)
            productIds.add(product.get("goods_id").toString());
      }
    }
    for (var entry : entries) {
      try {
        var from=com.pddcopilot.core.support.KnowledgeEntries.time(entry,"valid_from");
        var until=com.pddcopilot.core.support.KnowledgeEntries.time(entry,"valid_until");
        if (from!=null && until!=null && !until.isAfter(from)) throw new IllegalArgumentException();
      } catch (RuntimeException invalid) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_KNOWLEDGE_VALIDITY");
      }
      if (entry == null
          || !(entry.get("source_id") instanceof String sid)
          || sid.isBlank()
          || sid.length() > 100
          || !ids.add(sid)
          || !(entry.get("text") instanceof String text)
          || text.isBlank()
          || !(entry.get("source_type") instanceof String sourceType)
          || !Set.of("product", "shop_policy").contains(sourceType))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_ENTRY");
      if (!(entry.get("topics") instanceof List<?> topics)
          || topics.isEmpty()
          || topics.stream().anyMatch(topic -> !(topic instanceof String)
              || !Set.of("shipping", "policy", "spec", "recommendation", "faq", "response_guidelines")
                  .contains(topic))
          || text.length() > 10000)
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_TOPICS");
      if (entry.get("classification_method") != null
          && !Set.of("rule", "semantic", "manual").contains(entry.get("classification_method")))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_SOURCE");
      for (String field : List.of("document_name", "source_location", "document_sha256")) {
        if (entry.get(field) != null
            && (!(entry.get(field) instanceof String value)
                || value.isBlank()
                || value.length() > 200))
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_SOURCE");
      }
      if (entry.get("document_sha256") instanceof String hash && !hash.matches("[a-f0-9]{64}"))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_SOURCE");
      if (entry.get("title") != null
          && (!(entry.get("title") instanceof String title)
              || title.isBlank()
              || title.length() > 120))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_TITLE");
      if ("product".equals(entry.get("source_type"))
          && (!(entry.get("goods_id") instanceof String goodsId)
              || goodsId.isBlank()
              || goodsId.length() > 200
              || (checkProductCatalog && !productIds.contains(goodsId))))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_PRODUCT_REQUIRED");
      if ("product".equals(entry.get("source_type"))
          && !Set.of("spec", "recommendation").containsAll((List<?>) entry.get("topics")))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_TOPICS");
      if ("shop_policy".equals(entry.get("source_type"))
          && !Set.of("shipping", "policy", "faq", "response_guidelines")
              .containsAll((List<?>) entry.get("topics")))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_TOPICS");
      if (((List<?>) entry.get("topics")).contains("response_guidelines")
          && ((List<?>) entry.get("topics")).size() != 1)
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_KNOWLEDGE_TOPICS");
    }
  }

  @Transactional
  public Map<String, Object> saveProducts(String shop, Map<String, Object> result, String actor) {
    return saveProducts(shop, result, actor, true);
  }

  @Transactional
  public Map<String, Object> saveProducts(
      String shop, Map<String, Object> result, String actor, boolean auditUnchanged) {
    store.requireShopAccess(shop);
    if (!shop.equals(result.get("shop_id"))
        || !(result.get("products") instanceof List<?> products))
      throw conflict("PRODUCT_SCOPE_MISMATCH");
    repository.lockShop(shop);
    var previous = repository.findLatestProducts(shop);
    if (!previous.isEmpty()
        && store.fromJson(previous.get(0).get("products").toString()) instanceof List<?> old
        && com.pddcopilot.core.support.ProductFingerprint.catalog(old)
            .equals(com.pddcopilot.core.support.ProductFingerprint.catalog(products))) {
      String snapshot = previous.get(0).get("id").toString();
      repository.checkedProducts(snapshot, nowMillis());
      if (auditUnchanged) store.recordAudit("products.checked", snapshot, actor, snapshot);
      return Map.of("snapshot_id", snapshot, "count", products.size(), "unchanged", true);
    }
    String snapshot = newId();
    repository.insertProductSnapshot(snapshot, shop, store.toJson(products), nowMillis());
    store.recordAudit("products.synced", snapshot, actor, snapshot);
    return Map.of("snapshot_id", snapshot, "count", products.size(), "unchanged", false);
  }

  public List<Map<String, Object>> versions(String shop) {
    store.requireShopAccess(shop);
    return repository.listVersions(shop);
  }

  public List<Map<String, Object>> latestProducts(String shop) {
    store.requireShopAccess(shop);
    return products.decorateSnapshots(shop, repository.findLatestProducts(shop));
  }
}
