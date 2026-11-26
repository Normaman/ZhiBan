package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.repository.ApprovalRepository;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.support.ProductCards;
import com.pddcopilot.core.platform.pdd.PddCommerceService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Editing card choices makes a new draft revision; platform reads happen outside locks. */
@Service
public class DraftCardService {
  private final BusinessStore store;
  private final ApprovalRepository approvals;
  private final ProductService products;
  private final PddCommerceService pddCommerce;
  private final TransactionTemplate transaction;

  public DraftCardService(
      BusinessStore store,
      ApprovalRepository approvals,
      ProductService products,
      PddCommerceService pddCommerce,
      TransactionTemplate transaction) {
    this.store = store;
    this.approvals = approvals;
    this.products = products;
    this.pddCommerce = pddCommerce;
    this.transaction = transaction;
  }

  public List<Map<String, Object>> candidates(String id) {
    store.scopedConversation(
        approvals.findDraftConversation(id).get("conversation_id").toString(), false);
    return products.catalog();
  }

  public Map<String, Object> replace(String id, Contracts.Cards input, String actor) {
    String conversation = approvals.findDraftConversation(id).get("conversation_id").toString();
    store.scopedConversation(conversation, false);
    if (new HashSet<>(input.productIds()).size() != input.productIds().size())
      throw conflict("INVALID_PRODUCT_CARDS");
    var previews = new ArrayList<Map<String, Object>>();
    if (!input.productIds().isEmpty()) {
      var response = pddCommerce.products();
      if (!store.shopId().equals(response.get("shop_id"))
          || !(response.get("products") instanceof List<?> rows))
        throw conflict("CARD_PRODUCT_CHECK_FAILED");
      for (String goods : input.productIds()) {
        var row =
            rows.stream()
                .filter(
                    p ->
                        p instanceof Map<?, ?> m
                            && goods.equals(Objects.toString(m.get("goods_id"), "")))
                .findFirst()
                .orElseThrow(() -> conflict("CARD_PRODUCT_UNAVAILABLE"));
        var product = (Map<?, ?>) row;
        if ("20".equals(Objects.toString(product.get("goods_type"), "")))
          throw conflict("CARD_PRODUCT_UNAVAILABLE");
        if (!(product.get("quantity") instanceof Number q)
            || q.longValue() <= 0
            || q.doubleValue() != q.longValue()) throw conflict("CARD_PRODUCT_UNAVAILABLE");
        var card = new LinkedHashMap<String, Object>();
        card.put("goods_id", goods);
        card.put("title", product.get("goods_name"));
        card.put("fetched_at", product.get("fetched_at"));
        card.put(
            "image_url", product.get("thumb_url") instanceof String s && !s.isBlank() ? s : null);
        card.put("price", product.get("price"));
        previews.add(card);
      }
    }
    var cards = ProductCards.validate(previews);
    return transaction.execute(
        status -> {
          var current = store.scopedConversation(conversation, true);
          var draft = approvals.lockDraft(id);
          if (asLong(draft.get("revision")) != input.revision()
              || !"waiting_human".equals(draft.get("state"))
              || !"draft".equals(draft.get("outcome"))
              || !"open".equals(current.get("state"))
              || asLong(current.get("revision")) != asLong(draft.get("conversation_revision")))
            throw conflict("DRAFT_VERSION_CONFLICT");
          approvals.replaceCards(
              id,
              store.toJson(cards),
              input.content() == null ? draft.get("content") : input.content(),
              nowMillis());
          store.recordAudit("draft.cards_changed", id, actor, id);
          return Map.of("revision", input.revision() + 1, "product_cards", cards);
        });
  }
}
