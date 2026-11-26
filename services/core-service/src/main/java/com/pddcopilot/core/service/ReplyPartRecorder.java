package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.SendRepository;
import com.pddcopilot.core.support.ProductCards;
import java.util.*;
import org.springframework.stereotype.Service;

/** Reconcile individually acknowledged parts, including successful partial deliveries. */
@Service
public class ReplyPartRecorder {
  private final BusinessStore store;
  private final SendRepository repository;

  public ReplyPartRecorder(BusinessStore store, SendRepository repository) {
    this.store = store;
    this.repository = repository;
  }

  public void record(Map<String, Object> command, Map<String, Object> result) {
    var cards = ProductCards.validate(store.fromJson(command.get("product_cards").toString()));
    var expected = new LinkedHashMap<String, Map<String, Object>>();
    if (!command.get("final_content").toString().isBlank()) expected.put("text", Map.of());
    for (var card : cards) expected.put("card:" + card.get("goods_id"), card);
    if (!(result.get("parts") instanceof List<?> parts) || parts.size() > expected.size())
      throw new IllegalStateException("SEND_PART_RECEIPT_MISMATCH");
    var seen = new HashSet<String>();
    boolean allSent = true;
    for (var item : parts) {
      if (!(item instanceof Map<?, ?> part)
          || !(part.get("key") instanceof String key)
          || !expected.containsKey(key)
          || !seen.add(key)
          || !(key.equals("text") ? "text" : "product_card").equals(part.get("kind"))
          || (!key.equals("text")
              && !expected.get(key).get("goods_id").equals(part.get("goods_id"))))
        throw new IllegalStateException("SEND_PART_RECEIPT_MISMATCH");
      if (!"sent".equals(part.get("state"))) {
        allSent = false;
        continue;
      }
      var card = expected.get(key);
      String event = "reply-" + command.get("id") + ":part:" + sha256(key).substring(0, 16);
      String platform =
          part.get("platform_message_id") instanceof String id && id.matches("[0-9]{1,200}")
              ? id
              : null;
      // Without an identifier, retain the endpoint acknowledgement, and let the
      // native WebSocket echo supply the card message. Do not invent or duplicate it.
      if (!key.equals("text") && platform == null) continue;
      String type = key.equals("text") ? "text" : "goods_card";
      var media = new LinkedHashMap<String, Object>();
      var content = new LinkedHashMap<String, Object>();
      if (!card.isEmpty()) {
        content.put("goods_id", card.get("goods_id"));
        content.put("goods_name", card.get("title"));
        content.put("goods_price", card.get("price"));
        content.put("goods_thumb_url", card.get("image_url"));
        media.put("kind", "product");
        media.put("goods_id", card.get("goods_id"));
        media.put("title", card.get("title"));
        media.put("image_url", card.get("image_url"));
      }
      String text =
          card.isEmpty() ? command.get("final_content").toString() : store.toJson(content);
      var local = repository.findOutboundMessage(store.shopId(), event);
      var echoed =
          platform == null
              ? List.<Map<String, Object>>of()
              : repository.platformMessage(store.shopId(), platform);
      if (!echoed.isEmpty()) {
        var row = echoed.get(0);
        boolean same =
            type.equals("text")
                ? text.equals(row.get("content"))
                : store.fromJson(row.get("content").toString()) instanceof Map<?, ?> raw
                    && card.get("goods_id")
                        .toString()
                        .equals(Objects.toString(raw.get("goods_id"), ""));
        if (!same
            || (type.equals("goods_card") && !type.equals(row.get("message_type")))
            || !"outbound".equals(row.get("direction"))
            || !command.get("conversation_id").equals(row.get("conversation_id"))
            || (row.get("event_id").toString().startsWith("reply-")
                && !event.equals(row.get("event_id")))
            || (!local.isEmpty() && !local.get(0).get("id").equals(row.get("id"))))
          throw new IllegalStateException("SEND_RECEIPT_ID_CONFLICT");
        repository.bindOutbound(row.get("id"), event, platform);
      } else if (!local.isEmpty()) {
        if (platform != null
            && local.get(0).get("platform_message_id") != null
            && !platform.equals(local.get(0).get("platform_message_id")))
          throw new IllegalStateException("SEND_RECEIPT_ID_CONFLICT");
        repository.bindOutbound(local.get(0).get("id"), event, platform);
      } else
        repository.insertOutboundPart(
            newId(),
            store.shopId(),
            command.get("conversation_id"),
            platform,
            event,
            type,
            text,
            media.isEmpty() ? null : store.toJson(media),
            nowMillis());
    }
    if ("sent".equals(result.get("state")) && (!allSent || !seen.equals(expected.keySet())))
      throw new IllegalStateException("SEND_PART_RECEIPT_MISMATCH");
  }
}
