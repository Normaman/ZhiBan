package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.api.dto.KnowledgeArchive;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.KnowledgeRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeTransferService {
  public static final String FORMAT = "pdd-copilot-knowledge";
  private final BusinessStore store;
  private final KnowledgeRepository repository;
  private final KnowledgeService knowledge;

  public KnowledgeTransferService(
      BusinessStore store, KnowledgeRepository repository, KnowledgeService knowledge) {
    this.store = store;
    this.repository = repository;
    this.knowledge = knowledge;
  }

  @Transactional
  public KnowledgeArchive exportArchive(String shop) {
    store.requireShopAccess(shop);
    var bound = repository.lockShop(shop);
    var versions = repository.activeVersion(shop);
    if (versions.isEmpty()) throw invalid("KNOWLEDGE_EXPORT_EMPTY");
    var active = versions.get(0);
    String snapshot = active.get("snapshot").toString();
    return new KnowledgeArchive(
        FORMAT, 1, Instant.now().toString(),
        new KnowledgeArchive.Source(shop, bound.get("name").toString(), bound.get("mode").toString(),
            "live".equals(bound.get("mode")) && "bound".equals(bound.get("binding_state"))
                ? Objects.toString(bound.get("platform_shop_id"), null) : null,
            active.get("version_label").toString()),
        snapshot, sha256(snapshot));
  }

  private record Plan(Map<String, Object> summary, List<Map<String, Object>> merged) {}

  @Transactional
  public Map<String, Object> preview(String shop, KnowledgeArchive.Migration input) {
    store.requireShopAccess(shop);
    return plan(shop, repository.lockShop(shop), input).summary();
  }

  @Transactional
  public Map<String, Object> migrate(String shop, KnowledgeArchive.Migration input, String actor) {
    store.requireShopAccess(shop);
    var target = repository.lockShop(shop);
    var plan = plan(shop, target, input);
    var result = new LinkedHashMap<>(plan.summary());
    String base = Objects.toString(target.get("knowledge_version"), "");
    if (Boolean.TRUE.equals(result.get("unchanged"))) {
      result.put("knowledge_version", base);
      return result;
    }
    if (!base.equals(input.baseVersion())) throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    if (!Boolean.TRUE.equals(result.get("can_import")))
      throw conflict("KNOWLEDGE_MIGRATION_BLOCKED");
    var published = knowledge.publish(shop,
        new Contracts.Knowledge("migration-" + newId(), plan.merged(), base), actor);
    result.putAll(published);
    store.recordAudit("knowledge.migrated", published.get("knowledge_version").toString(),
        actor, input.archive().snapshotSha256());
    return result;
  }

  private Plan plan(String shop, Map<String, Object> target, KnowledgeArchive.Migration input) {
    var archive = input.archive();
    if (!FORMAT.equals(archive.format()) || archive.schemaVersion() != 1
        || !sha256(archive.snapshotJson()).equals(archive.snapshotSha256()))
      throw invalid("KNOWLEDGE_ARCHIVE_INVALID");
    if (!Set.of("keep_target", "replace_matches").contains(input.conflictPolicy())
        || input.includeProducts() == null)
      throw invalid("KNOWLEDGE_ARCHIVE_INVALID");
    var incoming = parse(archive.snapshotJson());
    var selected = incoming.stream()
        .filter(entry -> input.includeProducts() || !"product".equals(entry.get("source_type")))
        .toList();
    knowledge.validateEntries(shop, target, selected, false);
    var current = repository.activeVersion(shop);
    var merged = new LinkedHashMap<String, Map<String, Object>>();
    if (!current.isEmpty()) {
      for (var entry : parse(current.get(0).get("snapshot").toString()))
        merged.put(entry.get("source_id").toString(), entry);
    }
    var signatures = new HashMap<Map<String, Object>, Integer>();
    for (var entry : merged.values()) signatures.merge(signature(entry), 1, Integer::sum);
    int added = 0, updated = 0, skipped = 0, conflicts = 0;
    for (var entry : selected) {
      String id = entry.get("source_id").toString();
      var prior = merged.get(id);
      if (prior != null) {
        if (prior.equals(entry)) {
          skipped++;
        } else {
          conflicts++;
          if ("replace_matches".equals(input.conflictPolicy())) {
            signatures.computeIfPresent(signature(prior), (key, count) -> count <= 1 ? null : count - 1);
            merged.put(id, entry);
            signatures.merge(signature(entry), 1, Integer::sum);
            updated++;
          } else skipped++;
        }
      } else if (signatures.containsKey(signature(entry))) {
        skipped++;
      } else {
        merged.put(id, entry);
        signatures.merge(signature(entry), 1, Integer::sum);
        added++;
      }
    }
    boolean unchanged = added + updated == 0;
    var productIds = new HashSet<String>();
    for (var snapshot : repository.findLatestProducts(shop)) {
      if (store.fromJson(snapshot.get("products").toString()) instanceof List<?> products) {
        for (var item : products)
          if (item instanceof Map<?, ?> product && product.get("goods_id") != null)
            productIds.add(product.get("goods_id").toString());
      }
    }
    var missing = new TreeSet<String>();
    if (!unchanged) {
      for (var entry : merged.values()) {
        if ("product".equals(entry.get("source_type"))
            && !productIds.contains(Objects.toString(entry.get("goods_id"), "")))
          missing.add(Objects.toString(entry.get("goods_id"), ""));
      }
    }
    var entries = new ArrayList<>(merged.values());
    boolean canImport = unchanged || (missing.isEmpty() && entries.size() <= 500);
    if (canImport && !unchanged) knowledge.validateEntries(shop, target, entries, true);
    var summary = new LinkedHashMap<String, Object>();
    summary.put("source_shop_name", archive.source().shopName());
    summary.put("target_shop_name", target.get("name"));
    summary.put("source_mode", archive.source().mode());
    summary.put("target_mode", target.get("mode"));
    summary.put("base_version", Objects.toString(target.get("knowledge_version"), ""));
    summary.put("source_count", incoming.size());
    summary.put("added", added);
    summary.put("updated", updated);
    summary.put("skipped", skipped);
    summary.put("conflicts", conflicts);
    summary.put("excluded_products", incoming.size() - selected.size());
    summary.put("total_after", entries.size());
    summary.put("missing_goods_ids", missing);
    summary.put("can_import", canImport);
    summary.put("unchanged", unchanged);
    return new Plan(summary, entries);
  }

  private Map<String, Object> signature(Map<String, Object> entry) {
    var value = new LinkedHashMap<>(entry);
    value.remove("source_id");
    return value;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> parse(String snapshot) {
    try {
      if (!(store.fromJson(snapshot) instanceof List<?> entries) || entries.size() > 500)
        throw invalid("KNOWLEDGE_ARCHIVE_INVALID");
      var result = new ArrayList<Map<String, Object>>();
      for (var entry : entries) {
        if (!(entry instanceof Map<?, ?> map)) throw invalid("KNOWLEDGE_ARCHIVE_INVALID");
        result.add(new LinkedHashMap<>((Map<String, Object>) map));
      }
      return result;
    } catch (ResponseStatusException error) {
      throw error;
    } catch (RuntimeException error) {
      throw invalid("KNOWLEDGE_ARCHIVE_INVALID");
    }
  }

  private ResponseStatusException invalid(String code) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, code);
  }
}
