package com.pddcopilot.core.service;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.InboxRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persist each platform message once and enqueue draft generation in the same transaction. */
@Service
public class InboxService {
  private final BusinessStore store;
  private final com.pddcopilot.core.repository.ReplyBatchRepository batches;
  private final InboxRepository repository;
  private final AgentRunService runs;

  public InboxService(
      BusinessStore store,
      AgentRunService runs,
      InboxRepository repository,
      com.pddcopilot.core.repository.ReplyBatchRepository batches) {
    this.repository = repository;
    this.store = store;
    this.batches = batches;
    this.runs = runs;
  }

  @Transactional
  public Map<String, Object> receive(Contracts.Inbound input, String key) {
    store.requireShopAccess(input.shopId());
    var shop = repository.lockShop(input.shopId());
    if (!shop.get("platform_shop_id").equals(input.platformShopId())
        || !shop.get("connector_account_id").equals(input.connectorAccountId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_MISMATCH");
    String platformMessageId = input.platformMessageId();
    if (platformMessageId != null && platformMessageId.isBlank())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_PLATFORM_MESSAGE_ID");
    String expected =
        platformMessageId == null
            ? "event:" + input.shopId() + ":" + input.eventId()
            : "pdd:" + input.shopId() + ":" + platformMessageId;
    if (!expected.equals(key)) throw conflict("IDEMPOTENCY_KEY_MISMATCH");
    // Delivery IDs and received_at may differ on platform redelivery; compare semantic
    // identity/content.
    String digest =
        sha256(
            store.toJson(
                Arrays.asList(
                    input.shopId(),
                    input.buyerRef(),
                    input.direction(),
                    input.messageType(),
                    input.content(),
                    input.action(),
                    input.occurredAt())));
    var existing =
        repository.findDuplicateMessages(input.shopId(), input.eventId(), platformMessageId);
    if (!existing.isEmpty()) {
      var old = existing.get(0);
      if (old.get("event_id").toString().startsWith("reply-")
          && "outbound".equals(input.direction())
          && "outbound".equals(old.get("direction"))
          && sameOutbound(input, old)
          && Objects.equals(platformMessageId, old.get("platform_message_id"))) {
        var conversation = store.scopedConversation(old.get("conversation_id").toString(), false);
        if (!input.buyerRef().equals(conversation.get("buyer_ref")))
          throw conflict("IDEMPOTENCY_PAYLOAD_CONFLICT");
        if ("goods_card".equals(input.messageType()))
          repository.mergeOutboundCard(
              old.get("id"),
              input.content(),
              input.media() == null ? null : store.toJson(input.media()),
              digest);
        return Map.of("message_id", old.get("id"), "conversation_id", old.get("conversation_id"));
      }
      if (!digest.equals(old.get("payload_hash"))) throw conflict("IDEMPOTENCY_PAYLOAD_CONFLICT");
      if (input.media() != null) {
        String media = store.toJson(input.media());
        if (old.get("media_json") instanceof String oldJson) {
          var prior = new LinkedHashMap<>((Map<?, ?>) store.fromJson(oldJson));
          var incoming = new LinkedHashMap<>((Map<?, ?>) store.fromJson(media));
          Object oldLink = prior.remove("related_platform_message_id");
          Object newLink = incoming.remove("related_platform_message_id");
          if (!prior.equals(incoming) || (oldLink != null && !oldLink.equals(newLink)))
            throw conflict("IDEMPOTENCY_MEDIA_CONFLICT");
        }
        repository.saveMedia(old.get("id"), media);
      }
      return Map.of("message_id", old.get("id"), "conversation_id", old.get("conversation_id"));
    }
    var existingConversations = repository.lockBuyerConversation(input.shopId(), input.buyerRef());
    String conversationId;
    if (existingConversations.isEmpty()) {
      conversationId = newId();
      repository.insertConversation(conversationId, input.shopId(), input.buyerRef(), nowMillis());
    } else conversationId = (String) existingConversations.get(0).get("id");
    boolean conversational =
        !"system".equals(input.direction())
            && !("outbound".equals(input.direction())
                && ("goods_card".equals(input.messageType())
                    || repository.isApprovedPart(conversationId, input.content())))
            && !"system_hint".equals(input.messageType())
            && !"order_info".equals(input.messageType())
            && Set.of("reply", "context_only", "handoff", "unknown").contains(input.action());
    if (conversational) {
      repository.advanceConversation(nowMillis(), conversationId);
      repository.invalidateDrafts(conversationId);
    }
    String messageId = newId();
    repository.insertInboundMessage(
        messageId,
        input.shopId(),
        conversationId,
        platformMessageId,
        input.eventId(),
        input.direction(),
        input.messageType(),
        input.content(),
        input.occurredAt() == null ? null : input.occurredAt().toString(),
        input.receivedAt().toString(),
        digest,
        nowMillis());
    if (input.media() != null) repository.saveMedia(messageId, store.toJson(input.media()));
    if ("outbound".equals(input.direction()) && platformMessageId != null)
      repository.markOutboundSent(messageId);
    var cardQuestion =
        "inbound".equals(input.direction())
                && "goods_inquiry".equals(input.messageType())
                && input.media() != null
                && input.media().relatedPlatformMessageId() != null
            ? repository.questionForProductCard(
                conversationId, input.media().relatedPlatformMessageId())
            : List.<Map<String, Object>>of();
    boolean contextCard = !cardQuestion.isEmpty();
    boolean replyableInbound =
        "inbound".equals(input.direction())
            && "reply".equals(input.action())
            && !"order_info".equals(input.messageType());
    if (replyableInbound && platformMessageId != null && !contextCard)
      batches.track(messageId, conversationId);
    var conversation = store.scopedConversation(conversationId, false);
    if ("system_hint".equals(input.messageType())
        && "inbound".equals(input.direction())
        && input.media() != null
        && input.media().relatedPlatformMessageId() != null) {
      var changed =
          repository.generatedQuestionForSource(
              conversationId, input.media().relatedPlatformMessageId());
      if (!changed.isEmpty()) {
        repository.advanceConversation(nowMillis(), conversationId);
        repository.invalidateDrafts(conversationId);
        conversation = store.scopedConversation(conversationId, false);
        if ("open".equals(conversation.get("state"))) {
          String run =
              runs.start(
                  changed.get(0).get("id").toString(), conversation, shop.get("knowledge_version"));
          batches.single(run, conversationId);
        }
      }
    }
    if (contextCard
        && cardQuestion.get(0).get("answered_at") == null
        && "open".equals(conversation.get("state"))
        && Boolean.TRUE.equals(cardQuestion.get(0).get("can_generate"))) {
      runs.start(
          cardQuestion.get(0).get("id").toString(), conversation, shop.get("knowledge_version"));
    } else if (!contextCard
        && replyableInbound
        && platformMessageId != null
        && "open".equals(conversation.get("state"))) {
      runs.start(messageId, conversation, shop.get("knowledge_version"));
    } else if ("handoff".equals(input.action())
        || ("inbound".equals(input.direction())
            && "reply".equals(input.action())
            && platformMessageId == null)) {
      repository.markHumanReview(messageId);
    }
    store.recordAudit("message.persisted", messageId, "connector", input.eventId());
    return Map.of("message_id", messageId, "conversation_id", conversationId);
  }

  private boolean sameOutbound(Contracts.Inbound input, Map<String, Object> old) {
    if (!"goods_card".equals(input.messageType()))
      return !"goods_card".equals(old.get("message_type"))
          && input.content().equals(old.get("content"));
    if (!"goods_card".equals(old.get("message_type"))) return false;
    Object a = store.fromJson(input.content()), b = store.fromJson(old.get("content").toString());
    return a instanceof Map<?, ?> left
        && b instanceof Map<?, ?> right
        && left.get("goods_id") != null
        && left.get("goods_id").toString().equals(Objects.toString(right.get("goods_id"), ""));
  }
}
