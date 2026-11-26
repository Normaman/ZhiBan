package com.pddcopilot.core.api;

import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.repository.*;
import com.pddcopilot.core.support.KnowledgeEntries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class KnowledgeSearchController {
  private final BusinessStore store;
  private final KnowledgeRepository repository;
  private final AgentClient agent;
  public KnowledgeSearchController(BusinessStore store,KnowledgeRepository repository,AgentClient agent) {
    this.store=store; this.repository=repository; this.agent=agent;
  }
  public record Query(@NotBlank @Size(max=1000) String query,
      @Pattern(regexp="product|shop_policy") String sourceType,
      @Pattern(regexp="shipping|policy|spec|recommendation|faq|response_guidelines") String topic,
      @Size(max=200) String goodsId,
      @NotBlank @Pattern(regexp="current|bm25|hybrid") String mode) {}
  @PostMapping("/api/v1/shops/{shop}/knowledge/search")
  Object search(@PathVariable String shop,@Valid @RequestBody Query input) {
    store.requireShopAccess(shop);
    var current=repository.activeVersion(shop);
    var context=new LinkedHashMap<String,Object>();
    context.put("shop_id",shop);
    context.put("knowledge_version",current.isEmpty()?"":current.get(0).get("id"));
    context.put("knowledge",current.isEmpty()?List.of():KnowledgeEntries.active(
        store.fromJson(current.get(0).get("snapshot").toString()),System.currentTimeMillis()));
    context.put("knowledge_index",current.isEmpty()?List.of():store.fromJson(current.get(0).get("snapshot").toString()));
    var request=new LinkedHashMap<String,Object>();
    request.put("context",context);request.put("query",input.query());request.put("mode",input.mode());
    request.put("source_type",input.sourceType());request.put("topic",input.topic());request.put("goods_id",input.goodsId());
    return agent.debugKnowledge(request);
  }
}
