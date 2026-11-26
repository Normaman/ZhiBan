package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.AgentExecutionInput;
import com.pddcopilot.core.service.AgentExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/agent-runs/{id}/execution")
public class AgentExecutionController {
  private final AgentExecutionService executions;

  public AgentExecutionController(AgentExecutionService executions) {
    this.executions = executions;
  }

  @PostMapping("/claim")
  Object claim(@PathVariable String id, @Valid @RequestBody AgentExecutionInput.Request input) {
    return executions.claim(id, input);
  }

  @PostMapping("/complete")
  void complete(@PathVariable String id, @Valid @RequestBody AgentExecutionInput.Completion input) {
    executions.complete(id, input);
  }

  @PostMapping("/release")
  void release(@PathVariable String id, @Valid @RequestBody AgentExecutionInput.Release input) {
    executions.release(id, input);
  }
}
