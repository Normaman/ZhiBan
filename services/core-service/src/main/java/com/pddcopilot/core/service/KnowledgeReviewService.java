package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeReviewService {
  private final BusinessStore store;
  private final KnowledgeRepository versions;
  private final KnowledgeReviewRepository proposals;
  private final KnowledgeService knowledge;

  public KnowledgeReviewService(
      BusinessStore store,
      KnowledgeRepository versions,
      KnowledgeReviewRepository proposals,
      KnowledgeService knowledge) {
    this.store = store;
    this.versions = versions;
    this.proposals = proposals;
    this.knowledge = knowledge;
  }

  public List<Map<String, Object>> list(String shop) {
    store.requireShopAccess(shop);
    return proposals.list(shop);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> detail(String shop, String id) {
    store.requireShopAccess(shop);
    var row = new LinkedHashMap<>(proposals.find(shop, id, false));
    var entries = (List<Map<String, Object>>) store.fromJson(row.get("snapshot").toString());
    row.put("conflicts", conflicts(entries));
    var current = versions.activeVersion(shop);
    var old =
        current.isEmpty()
            ? List.<Map<String, Object>>of()
            : (List<Map<String, Object>>) store.fromJson(current.get(0).get("snapshot").toString());
    Map<String, Map<String, Object>> before = new HashMap<>(), after = new HashMap<>();
    old.forEach(e -> before.put(e.get("source_id").toString(), e));
    entries.forEach(e -> after.put(e.get("source_id").toString(), e));
    var changes = new ArrayList<Map<String, Object>>();
    var ids = new TreeSet<>(before.keySet());
    ids.addAll(after.keySet());
    for (String source : ids)
      if (!Objects.equals(before.get(source), after.get(source))) {
        var change = new LinkedHashMap<String, Object>();
        change.put("source_id", source);
        change.put("before", before.get(source));
        change.put("after", after.get(source));
        changes.add(change);
      }
    row.put("changes", changes);
    row.put("history", proposals.history(shop, id));
    row.put("current_base_version", current.isEmpty() ? "" : current.get(0).get("id"));
    row.put("current_entries", old);
    var base = proposals.baseVersion(shop, Objects.toString(row.get("base_version"), ""));
    row.put(
        "base_entries", base == null ? List.of() : store.fromJson(base.get("snapshot").toString()));
    return row;
  }

  @Transactional
  public Map<String, Object> create(String shop, Contracts.Knowledge input, String actor) {
    store.requireShopAccess(shop);
    var current = versions.lockShop(shop);
    if (!Objects.equals(
        Objects.toString(current.get("knowledge_version"), ""),
        Objects.toString(input.baseVersion(), ""))) throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    knowledge.validateEntries(shop, current, input.entries(), true);
    String id = newId();
    proposals.insert(
        id, shop, input.baseVersion(), store.toJson(input.entries()), actor, nowMillis());
    proposals.recordRevision(shop, id, actor, nowMillis());
    store.recordAudit("knowledge.draft_created", id, actor, id);
    return Map.of("id", id, "revision", 1);
  }

  @Transactional
  public Map<String, Object> update(
      String shop, String id, long revision, Contracts.Knowledge input, String actor) {
    store.requireShopAccess(shop);
    var current = versions.lockShop(shop);
    var row = proposals.find(shop, id, true);
    if (!actor.equals(row.get("created_by")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    if (((Number) row.get("revision")).longValue() != revision)
      throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    if (!Set.of("draft", "rejected", "withdrawn").contains(row.get("state")))
      throw conflict("KNOWLEDGE_REVIEW_STATE_CONFLICT");
    if (!Objects.equals(
        Objects.toString(current.get("knowledge_version"), ""),
        Objects.toString(input.baseVersion(), ""))) throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    knowledge.validateEntries(shop, current, input.entries(), true);
    proposals.updateDraft(shop, id, input.baseVersion(), store.toJson(input.entries()));
    proposals.recordRevision(shop, id, actor, nowMillis());
    store.recordAudit("knowledge.draft_updated", id, actor, id);
    return Map.of("id", id, "state", "draft", "revision", revision + 1);
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, Object> decide(
      String shop,
      String id,
      long revision,
      String action,
      String note,
      boolean acknowledged,
      String actor) {
    store.requireShopAccess(shop);
    // Match publication's shop -> proposal lock order for consistent concurrent decisions.
    versions.lockShop(shop);
    var row = proposals.find(shop, id, true);
    if (((Number) row.get("revision")).longValue() != revision)
      throw conflict("KNOWLEDGE_VERSION_CONFLICT");
    String state = row.get("state").toString(), next;
    Object published = null;
    if ("submit".equals(action) && "draft".equals(state)) next = "pending";
    else if ("withdraw".equals(action) && Set.of("draft", "pending").contains(state))
      next = "withdrawn";
    else if ("reject".equals(action) && "pending".equals(state)) next = "rejected";
    else if ("approve".equals(action) && "pending".equals(state)) {
      var entries = (List<Map<String, Object>>) store.fromJson(row.get("snapshot").toString());
      if (!conflicts(entries).isEmpty() && !acknowledged)
        throw conflict("KNOWLEDGE_CONFLICT_REVIEW_REQUIRED");
      published =
          knowledge
              .publish(
                  shop,
                  new Contracts.Knowledge(
                      "review-" + id, entries, Objects.toString(row.get("base_version"), "")),
                  actor)
              .get("knowledge_version");
      next = "published";
    } else throw conflict("KNOWLEDGE_REVIEW_STATE_CONFLICT");
    if (Set.of("submit", "withdraw").contains(action) && !actor.equals(row.get("created_by")))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    proposals.transition(shop, id, next, actor, nowMillis(), note, published);
    proposals.recordRevision(shop, id, actor, nowMillis());
    store.recordAudit("knowledge." + action, id, actor, id);
    return Map.of("state", next, "revision", revision + 1);
  }

  static List<Map<String, String>> conflicts(List<Map<String, Object>> entries) {
    var warnings = new ArrayList<Map<String, String>>();
    for (int i = 0; i < entries.size(); i++)
      for (int j = i + 1; j < entries.size(); j++) {
        var a = entries.get(i);
        var b = entries.get(j);
        if (!Objects.equals(a.get("source_type"), b.get("source_type"))
            || !Objects.equals(a.get("goods_id"), b.get("goods_id"))
            || !Objects.equals(a.get("topics"), b.get("topics"))) continue;
        boolean duplicate = Objects.equals(a.get("text"), b.get("text"));
        boolean sameTitle =
            a.get("title") != null && Objects.equals(a.get("title"), b.get("title"));
        if (duplicate || sameTitle)
          warnings.add(
              Map.of(
                  "first",
                  a.get("source_id").toString(),
                  "second",
                  b.get("source_id").toString(),
                  "reason",
                  duplicate ? "duplicate" : "same_title_different_content"));
        if (warnings.size() >= 50) return warnings;
      }
    return warnings;
  }
}
