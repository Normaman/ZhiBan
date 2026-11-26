package com.pddcopilot.core.api.dto;

import jakarta.validation.constraints.*;
import java.util.Map;

public final class AgentExecutionInput {
  private AgentExecutionInput() {}

  public record Request(
      @NotBlank @Size(max = 100) String runId,
      @NotBlank @Size(max = 100) String shopId,
      @NotBlank @Size(max = 100) String conversationId,
      @NotBlank @Size(max = 100) String messageId,
      @NotNull @Size(max = 100) String knowledgeVersion) {}

  public record Completion(
      @NotBlank @Size(max = 100) String leaseId,
      @NotNull Map<String, Object> result) {}

  public record Release(@NotBlank @Size(max = 100) String leaseId) {}
}
