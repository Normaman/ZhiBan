package com.pddcopilot.core.api;

import com.pddcopilot.core.service.WorkspaceMemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/workspace/members")
public class WorkspaceMemberController {
  public record Status(@Positive long revision, @NotNull Boolean enabled) {}

  public record Revision(@Positive long revision) {}

  private final WorkspaceMemberService members;

  public WorkspaceMemberController(WorkspaceMemberService members) {
    this.members = members;
  }

  @PatchMapping("/{id}")
  Object status(@PathVariable String id, @Valid @RequestBody Status input, Principal user) {
    return members.setEnabled(id, input.revision(), input.enabled(), user.getName());
  }

  @DeleteMapping("/{id}")
  Object remove(@PathVariable String id, @Valid @RequestBody Revision input, Principal user) {
    return members.remove(id, input.revision(), user.getName());
  }
}
