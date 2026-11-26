package com.pddcopilot.core.platform.pdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ConnectionRepository;
import com.pddcopilot.core.repository.PddSendJournal;
import com.pddcopilot.core.service.ConnectorRegistryService;
import com.pddcopilot.core.service.SendPolicy;
import com.pddcopilot.core.support.ProductCards;
import com.pddcopilot.core.support.WorkflowValues;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Sends only immutable commands already approved and marked sending by the Core worker. */
@Service
public class PddSendService {
  private record Part(String key, String kind, String goodsId, Map<String, Object> card) {}

  private final BusinessStore store;
  private final ConnectionRepository shops;
  private final ConnectorRegistryService registry;
  private final PddCredentialStore credentials;
  private final ObjectMapper json;
  private final PddCommerceService commerce;
  private final PddSendJournal journal;
  private final SendPolicy policy;

  public PddSendService(
      BusinessStore store,
      ConnectionRepository shops,
      ConnectorRegistryService registry,
      PddCredentialStore credentials,
      ObjectMapper json,
      PddCommerceService commerce,
      PddSendJournal journal,
      SendPolicy policy) {
    this.store = store;
    this.shops = shops;
    this.registry = registry;
    this.credentials = credentials;
    this.json = json;
    this.commerce = commerce;
    this.journal = journal;
    this.policy = policy;
  }

  public Map<String, Object> send(Map<String, Object> command) {
    String shopId = store.shopId();
    if (!shopId.equals(command.get("shop_id"))
        || !"sending".equals(command.get("state"))
        || !"approve".equals(command.get("decision")))
      return result("send_failed", "SEND_COMMAND_NOT_APPROVED");
    String conversationId = String.valueOf(command.get("conversation_id"));
    boolean manual = "manual".equals(command.get("origin"));
    if (!(manual ? "transferred" : "open").equals(command.get("conversation_state")))
      return result("send_failed", "CONVERSATION_TRANSFERRED");
    policy.requireOnline(conversationId);
    if (!"live".equals(command.get("mode"))) return result("send_failed", "PDD_SHOP_NOT_BOUND");
    Map<String, Object> shop = shops.findShop(shopId).stream().findFirst()
        .orElseThrow(() -> new PddPlatformException("PDD_SHOP_NOT_BOUND"));
    if (!"bound".equals(shop.get("binding_state")))
      throw new PddPlatformException("PDD_SHOP_NOT_BOUND");
    String platformShop = String.valueOf(shop.get("platform_shop_id"));
    String accountId = String.valueOf(shop.get("connector_account_id"));
    Path directory = registry.directory(shopId);
    if (directory == null) throw new PddPlatformException("PDD_CREDENTIALS_UNAVAILABLE");
    PddWebClient client = new PddWebClient(json, credentials.load(directory, platformShop, accountId));
    PddWebClient.Identity identity = client.identity();
    if (!platformShop.equals(identity.platformShopId()) || !accountId.equals(identity.accountId()))
      throw new PddPlatformException("AUTH_IDENTITY_MISMATCH");

    String commandId = String.valueOf(command.get("id"));
    String buyer = String.valueOf(command.get("buyer_ref"));
    String content = String.valueOf(command.getOrDefault("final_content", ""));
    int attempt = ((Number) command.get("attempt")).intValue();
    List<Map<String, Object>> cards = ProductCards.validate(
        command.get("product_cards") instanceof String encoded
            ? store.fromJson(encoded)
            : command.get("product_cards"));
    List<Part> parts = new ArrayList<>();
    if (!content.isBlank()) parts.add(new Part("text", "text", null, Map.of()));
    for (Map<String, Object> card : cards)
      parts.add(new Part("card:" + card.get("goods_id"), "product_card",
          String.valueOf(card.get("goods_id")), card));
    if (parts.isEmpty()) return result("send_failed", "EMPTY_REPLY");

    List<Map<String, Object>> prior = new ArrayList<>();
    List<Part> pendingCards = parts.stream()
        .filter(part -> "product_card".equals(part.kind()))
        .filter(part -> !isAlreadySent(commandId, attempt, content, part))
        .toList();
    if (!pendingCards.isEmpty()) {
      try {
        Object rawProducts = commerce.products().get("products");
        if (!(rawProducts instanceof List<?> products)) throw new IllegalStateException();
        Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
        for (Object value : products)
          if (value instanceof Map<?, ?> product && product.get("goods_id") != null)
            byId.put(String.valueOf(product.get("goods_id")), product);
        for (Part part : pendingCards) {
          Map<?, ?> product = byId.get(part.goodsId());
          if (product == null || !(product.get("quantity") instanceof Number quantity)
              || quantity.longValue() <= 0 || "20".equals(String.valueOf(product.get("goods_type"))))
            return partResult("send_failed", "CARD_PRODUCT_UNAVAILABLE", prior);
        }
      } catch (Exception error) {
        return partResult("send_failed", "CARD_PRODUCT_CHECK_FAILED", prior);
      }
    }

    Map<String, Object> textReceipt = null;
    for (Part part : parts) {
      String hash = WorkflowValues.sha256(
          commandId + "\u0000" + part.key() + "\u0000" + content + "\u0000" + store.toJson(part.card()));
      PddSendJournal.Entry entry = journal.begin(commandId, attempt, part.key(), hash);
      Map<String, Object> receipt;
      if (!entry.created()) {
        if ("sent".equals(entry.state()) && entry.receipt() != null) receipt = entry.receipt();
        else if ("send_failed".equals(entry.state()) && entry.receipt() != null)
          return partResult("send_failed", String.valueOf(entry.receipt().get("error_code")), prior);
        else return partResult("send_unknown", "SEND_PART_INTERRUPTED", prior);
      } else {
        try {
          receipt = "text".equals(part.kind())
              ? client.sendText(buyer, content)
              : client.sendProductCard(buyer, part.goodsId());
        } catch (Exception error) {
          receipt = result("send_unknown", "CONNECTOR_RESULT_UNCONFIRMED");
        }
        journal.finish(commandId, attempt, part.key(), receipt);
      }
      Map<String, Object> partReceipt = new LinkedHashMap<>();
      partReceipt.put("key", part.key());
      partReceipt.put("kind", part.kind());
      if (part.goodsId() != null) partReceipt.put("goods_id", part.goodsId());
      partReceipt.putAll(receipt);
      prior.add(partReceipt);
      if ("text".equals(part.kind())) textReceipt = receipt;
      if (!"sent".equals(receipt.get("state")))
        return partResult(String.valueOf(receipt.get("state")),
            String.valueOf(receipt.getOrDefault("error_code", "PART_SEND_FAILED")), prior);
    }
    if (!cards.isEmpty()) return Map.of("state", "sent", "parts", prior, "mode", "live");
    return textReceipt == null ? result("send_failed", "EMPTY_REPLY")
        : new LinkedHashMap<>(textReceipt);
  }

  private boolean isAlreadySent(String commandId, int attempt, String content, Part part) {
    String hash = WorkflowValues.sha256(
        commandId + "\u0000" + part.key() + "\u0000" + content + "\u0000" + store.toJson(part.card()));
    PddSendJournal.Entry entry = journal.find(commandId, attempt, part.key(), hash);
    return entry != null && "sent".equals(entry.state());
  }

  private Map<String, Object> partResult(String state, String errorCode, List<Map<String, Object>> parts) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("state", state);
    result.put("error_code", errorCode);
    result.put("parts", parts);
    result.put("mode", "live");
    return result;
  }

  private Map<String, Object> result(String state, String errorCode) {
    return Map.of("state", state, "error_code", errorCode, "mode", "live");
  }
}
