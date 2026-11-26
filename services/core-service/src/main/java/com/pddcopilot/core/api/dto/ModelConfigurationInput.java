package com.pddcopilot.core.api.dto;

import jakarta.validation.constraints.*;

public record ModelConfigurationInput(
    @PositiveOrZero long revision,
    @NotBlank @Size(max = 1000) String baseUrl,
    @NotBlank @Size(max = 200) String model,
    @Size(max = 4096) String apiKey,
    @Min(1) @Max(8) int maxRounds,
    @Min(5) @Max(70) int timeoutSeconds) {
  @Override
  public String toString() {
    return "ModelConfigurationInput[redacted]";
  }
}
