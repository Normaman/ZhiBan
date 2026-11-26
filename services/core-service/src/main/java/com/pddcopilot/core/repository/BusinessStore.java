package com.pddcopilot.core.repository;

import static com.pddcopilot.core.support.WorkflowValues.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pddcopilot.core.mapper.BusinessMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared persistence/JSON operations and the authoritative shop scope. Transactions belong to
 * services.
 */
@Component
public class BusinessStore {
  private final BusinessMapper mapper;
  private final ObjectMapper json;
  private final String configuredShopId;

  public BusinessStore(
      BusinessMapper mapper, ObjectMapper json, @Value("${copilot.shop-id}") String shop) {
    this.mapper = mapper;
    this.json = json;
    this.configuredShopId = shop;
  }

  public String shopId() {
    String scoped = com.pddcopilot.core.support.ShopScope.current();
    return scoped == null ? configuredShopId : scoped;
  }

  public boolean isDefaultShop() {
    return configuredShopId.equals(shopId());
  }

  public String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("invalid JSON");
    }
  }

  @SuppressWarnings("unchecked")
  public Object fromJson(String value) {
    try {
      return json.readValue(value, Object.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("invalid stored JSON");
    }
  }

  public void requireShopAccess(String shop) {
    if (!shopId().equals(shop))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SHOP_FORBIDDEN");
  }

  public Map<String, Object> scopedConversation(String conversationId, boolean lock) {
    var conversation = DatabaseRows.required(mapper.scopedConversation(conversationId, lock));
    requireShopAccess((String) conversation.get("shop_id"));
    return conversation;
  }

  public void recordAudit(String type, String subject, String actor, String trace) {
    mapper.recordAudit(newId(), shopId(), type, subject, actor, trace, nowMillis());
  }

  public void enqueueJob(String kind, String ref) {
    String id = newId();
    long now = nowMillis();
    mapper.enqueueJob(id, kind, ref, now, shopId());
    enqueueJobEvent(id, now);
  }

  public void enqueueExistingJob(String kind, String ref, long availableAt) {
    var job = DatabaseRows.required(mapper.findJob(kind, ref));
    enqueueJobEvent(job.get("id").toString(), availableAt);
  }

  public void enqueueJobEvent(String jobId, long availableAt) {
    mapper.enqueueJobEvent(newId(), jobId, availableAt, nowMillis());
  }
}
