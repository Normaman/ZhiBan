package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.service.KnowledgeReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
public class KnowledgeReviewController {
  private final KnowledgeReviewService reviews;

  public KnowledgeReviewController(KnowledgeReviewService reviews) {
    this.reviews = reviews;
  }

  public record Decision(
      @Positive long revision,
      @NotBlank @Pattern(regexp = "submit|withdraw|approve|reject") String action,
      @NotBlank @Size(max = 1000) String note,
      boolean acknowledgeConflicts) {}

  @GetMapping("/api/v1/shops/{shop}/knowledge/proposals")
  Object list(@PathVariable String shop) {
    return reviews.list(shop);
  }

  @GetMapping("/api/v1/shops/{shop}/knowledge/proposals/{id}")
  Object detail(@PathVariable String shop, @PathVariable String id) {
    return reviews.detail(shop, id);
  }

  @PostMapping("/api/v1/shops/{shop}/knowledge/proposals")
  Object create(
      @PathVariable String shop, @Valid @RequestBody Contracts.Knowledge input, Principal user) {
    return reviews.create(shop, input, user.getName());
  }

  public record Update(
      @Positive long revision,
      @jakarta.validation.constraints.NotNull @Valid Contracts.Knowledge knowledge) {}

  @PutMapping("/api/v1/shops/{shop}/knowledge/proposals/{id}")
  Object update(
      @PathVariable String shop,
      @PathVariable String id,
      @Valid @RequestBody Update input,
      Principal user) {
    return reviews.update(shop, id, input.revision(), input.knowledge(), user.getName());
  }

  @PostMapping("/api/v1/shops/{shop}/knowledge/proposals/{id}/decisions")
  Object submit(
      @PathVariable String shop,
      @PathVariable String id,
      @Valid @RequestBody Decision input,
      Principal user) {
    if (!java.util.Set.of("submit", "withdraw").contains(input.action()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    return decide(shop, id, input, user);
  }

  @PostMapping("/api/v1/admin/shops/{shop}/knowledge/proposals/{id}/decisions")
  Object decide(
      @PathVariable String shop,
      @PathVariable String id,
      @Valid @RequestBody Decision input,
      Principal user) {
    return reviews.decide(
        shop,
        id,
        input.revision(),
        input.action(),
        input.note(),
        input.acknowledgeConflicts(),
        user.getName());
  }
}
