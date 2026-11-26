package com.pddcopilot.core.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class Contracts {
  public record ShopProfile(
      @NotBlank @Size(max = 100) String platformShopId,
      @NotBlank @Size(max = 100) String connectorAccountId,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000)
          @Pattern(
              regexp =
                  "https://(?:[a-zA-Z0-9-]+\\.)*(?:pddpic\\.com|pinduoduo\\.com|yangkeduo\\.com)/[^\\s]*")
          String avatarUrl,
      @Size(max = 500) String mainCategory) {}

  public record Resume(@PositiveOrZero long revision) {}

  public record Cards(
      @Positive long revision,
      @NotNull @Size(max = 3) List<@NotBlank @Size(max = 200) String> productIds,
      @Size(max = 4000) String content) {}

  public record ManualReply(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{16,100}") String requestId,
      @PositiveOrZero long revision,
      @NotBlank @Size(max = 4000) String content,
      @Size(max = 200) List<@NotBlank @Size(max = 100) String> answeredMessageIds) {
    public ManualReply(String requestId, long revision, String content) {
      this(requestId, revision, content, List.of());
    }
  }

  public record ReadMessages(
      @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 100) String> messageIds) {}

  public record ReadReceipt(
      @NotBlank @Size(max = 100) String shopId,
      @NotBlank @Size(max = 100) String platformShopId,
      @NotBlank @Size(max = 100) String connectorAccountId,
      @NotBlank @Size(max = 200) String buyerRef,
      @Pattern(regexp = "[0-9]{1,200}") String userLastRead,
      @NotBlank @Pattern(regexp = "[0-9]{1,200}") String minSupportedMessageId,
      @NotNull OffsetDateTime observedAt) {}

  public record Media(
      @NotBlank @Pattern(regexp = "image|product") String kind,
      @Size(max = 4000)
          @Pattern(
              regexp =
                  "https://(?:[a-zA-Z0-9-]+\\.)*(?:pddpic\\.com|pinduoduo\\.com|yangkeduo\\.com)/[^\\s]*")
          String imageUrl,
      @Size(max = 200) String goodsId,
      @Size(max = 1000) String title,
      @Pattern(regexp = "[0-9]{1,200}") String relatedPlatformMessageId) {
    public Media(String kind, String imageUrl, String goodsId, String title) {
      this(kind, imageUrl, goodsId, title, null);
    }
  }

  public record Inbound(
      @NotBlank @Size(max = 100) String eventId,
      @NotBlank @Size(max = 100) String shopId,
      @NotBlank @Size(max = 100) String platformShopId,
      @NotBlank @Size(max = 100) String connectorAccountId,
      @Size(max = 200) String platformMessageId,
      @NotBlank @Size(max = 200) String buyerRef,
      @NotBlank @Pattern(regexp = "inbound|outbound|system") String direction,
      @NotBlank @Size(max = 40) String messageType,
      @NotNull @Size(max = 20000) String content,
      OffsetDateTime occurredAt,
      @NotNull OffsetDateTime receivedAt,
      @NotBlank @Pattern(regexp = "reply|context_only|handoff|ignore|observe|unknown")
          String action,
      @Valid Media media) {
    public Inbound(
        String eventId,
        String shopId,
        String platformShopId,
        String connectorAccountId,
        String platformMessageId,
        String buyerRef,
        String direction,
        String messageType,
        String content,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt,
        String action) {
      this(
          eventId,
          shopId,
          platformShopId,
          connectorAccountId,
          platformMessageId,
          buyerRef,
          direction,
          messageType,
          content,
          occurredAt,
          receivedAt,
          action,
          null);
    }
  }

  public record Decision(
      @NotBlank @Pattern(regexp = "approve|reject|transfer") String decision,
      @Positive long revision,
      @Size(max = 4000) String finalContent,
      @Size(max = 3) List<@NotBlank @Size(max = 200) String> productIds) {
    public Decision(String decision, long revision, String finalContent) {
      this(decision, revision, finalContent, List.of());
    }
  }

  public record Connection(
      @NotBlank String epoch,
      @Positive long sequence,
      @NotBlank
          @Pattern(
              regexp = "disconnected|connecting|authenticating|online|reconnecting|failed|stopped")
          String state,
      @Pattern(regexp = "[A-Z0-9_]{1,100}") String errorCode) {
    public Connection(String epoch, long sequence, String state) {
      this(epoch, sequence, state, null);
    }
  }

  public record DocumentPreview(
      @NotBlank @Size(max = 200) String filename,
      @NotBlank @Size(max = 7000000) String contentBase64) {}

  public record ProductQuery(@NotBlank @Size(max = 200) String goodsId) {}

  public record OrderQuery(
      @NotBlank @Size(max = 200) String orderId,
      @NotBlank @Size(max = 200) String buyerRef) {}

  public record Knowledge(
      @NotBlank @Size(max = 100) String version,
      @NotNull @Size(max = 500) List<Map<String, Object>> entries,
      @Size(max = 100) String baseVersion) {
    public Knowledge(String version, List<Map<String, Object>> entries) {
      this(version, entries, null);
    }
  }
}
