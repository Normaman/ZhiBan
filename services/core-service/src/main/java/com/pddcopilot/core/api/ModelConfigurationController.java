package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.ModelConfigurationInput;
import com.pddcopilot.core.service.ModelConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Admin management uses only redacted responses; secrets have a separate internal route. */
@RestController
public class ModelConfigurationController {
  public record Revision(@PositiveOrZero long revision) {}

  private final ModelConfigurationService configurations;

  public ModelConfigurationController(ModelConfigurationService configurations) {
    this.configurations = configurations;
  }

  @GetMapping("/api/v1/admin/model-config")
  Object overview() {
    return noStore(configurations.overview());
  }

  @PostMapping("/api/v1/admin/model-config")
  Object save(@Valid @RequestBody ModelConfigurationInput input, Principal principal) {
    return noStore(configurations.save(input, principal.getName()));
  }

  @PostMapping("/api/v1/admin/model-config/{id}/test")
  Object test(@PathVariable String id, @Valid @RequestBody Revision input, Principal principal) {
    return noStore(configurations.test(id, input.revision(), principal.getName()));
  }

  @PostMapping("/api/v1/admin/model-config/{id}/activate")
  Object activate(
      @PathVariable String id, @Valid @RequestBody Revision input, Principal principal) {
    return noStore(configurations.activate(id, input.revision(), principal.getName()));
  }

  @PostMapping("/api/v1/admin/model-config/disable")
  Object disable(@Valid @RequestBody Revision input, Principal principal) {
    return noStore(configurations.disable(input.revision(), principal.getName()));
  }

  @GetMapping("/internal/v1/shops/{shopId}/model-config")
  Object effective(@PathVariable String shopId) {
    return noStore(configurations.effective(shopId));
  }

  private Object noStore(Object body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
