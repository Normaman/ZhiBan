package com.pddcopilot.core.api;

import com.pddcopilot.core.api.dto.KnowledgeArchive;
import com.pddcopilot.core.service.KnowledgeTransferService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/shops/{shop}/knowledge")
public class KnowledgeTransferController {
  private final KnowledgeTransferService transfer;

  public KnowledgeTransferController(KnowledgeTransferService transfer) {
    this.transfer = transfer;
  }

  @GetMapping("/archive")
  Object exportArchive(@PathVariable String shop) {
    var archive = transfer.exportArchive(shop);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename("knowledge-" + archive.exportedAt().replaceAll("[^0-9]", "") + ".json").build().toString())
        .body(archive);
  }

  @PostMapping("/migration/preview")
  Object preview(@PathVariable String shop, @Valid @RequestBody KnowledgeArchive.Migration input) {
    return transfer.preview(shop, input);
  }

  @PostMapping("/migration")
  Object migrate(@PathVariable String shop, @Valid @RequestBody KnowledgeArchive.Migration input,
      Principal principal) {
    return transfer.migrate(shop, input, principal.getName());
  }
}
