package com.pddcopilot.core.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record KnowledgeArchive(
    @NotBlank @Size(max = 60) String format,
    @Min(1) @Max(1) int schemaVersion,
    @NotBlank @Size(max = 60) String exportedAt,
    @NotNull @Valid Source source,
    @NotNull @Size(max = 6_000_000) String snapshotJson,
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotSha256) {
  public record Source(
      @NotBlank @Size(max = 100) String shopId,
      @NotBlank @Size(max = 200) String shopName,
      @NotBlank @Pattern(regexp = "live") String mode,
      @Size(max = 100) String platformShopId,
      @Size(max = 100) String versionLabel) {}

  public record Migration(
      @NotNull @Valid KnowledgeArchive archive,
      @NotNull Boolean includeProducts,
      @NotBlank @Pattern(regexp = "keep_target|replace_matches") String conflictPolicy,
      @NotNull @Size(max = 100) String baseVersion) {}
}
