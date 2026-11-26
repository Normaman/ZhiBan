package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.Contracts;
import com.pddcopilot.core.service.AgentRunService;
import com.pddcopilot.core.service.ReplyApprovalService;
import com.pddcopilot.core.service.ReplySendService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DraftController {
  private final ReplyApprovalService approvals;
  private final AgentRunService runs;
  private final ReplySendService sends;
  private final com.pddcopilot.core.service.ManualReplyService manual;
  private final com.pddcopilot.core.service.DraftCardService cards;

  public DraftController(
      ReplyApprovalService approvals,
      AgentRunService runs,
      ReplySendService sends,
      com.pddcopilot.core.service.DraftCardService cards,
      com.pddcopilot.core.service.ManualReplyService manual) {
    this.approvals = approvals;
    this.runs = runs;
    this.sends = sends;
    this.manual = manual;
    this.cards = cards;
  }

  @PostMapping("/api/v1/conversations/{id}/manual-replies")
  Object manual(
      @PathVariable String id, @Valid @RequestBody Contracts.ManualReply input, Principal p) {
    return manual.submit(id, input, p.getName());
  }

  @PostMapping("/api/v1/reply-drafts/{id}/decisions")
  Object decide(
      @PathVariable String id, @Valid @RequestBody Contracts.Decision input, Principal p) {
    return approvals.decide(id, input, p.getName());
  }

  @GetMapping("/api/v1/reply-drafts/{id}/product-candidates")
  Object candidates(@PathVariable String id) {
    return cards.candidates(id);
  }

  @PostMapping("/api/v1/reply-drafts/{id}/product-cards")
  Object replaceCards(
      @PathVariable String id, @Valid @RequestBody Contracts.Cards input, Principal p) {
    return cards.replace(id, input, p.getName());
  }

  @PostMapping("/api/v1/messages/{id}/agent-runs")
  Object regenerate(
      @PathVariable String id, @RequestParam(defaultValue = "false") boolean single, Principal p) {
    return runs.regenerate(id, p.getName(), single);
  }

  @PostMapping("/api/v1/send-commands/{id}/retry")
  Object retry(@PathVariable String id, Principal p) {
    return sends.retry(id, p.getName());
  }
}
