package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.AgentExecutionMapper;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Execution results and leases share the authoritative PostgreSQL database. */
@Repository
public class AgentExecutionRepository {
  private final AgentExecutionMapper mapper;
  private final BusinessStore store;

  public AgentExecutionRepository(AgentExecutionMapper mapper, BusinessStore store) {
    this.mapper = mapper;
    this.store = store;
  }

  public Map<String, Object> lockRun(String id) {
    var row = DatabaseRows.required(mapper.lockRun(id));
    store.requireShopAccess(row.get("shop_id").toString());
    return row;
  }

  public Map<String, Object> find(String id) {
    var rows = mapper.find(id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void insert(String id, String request, String lease, long until) {
    mapper.insert(id, request, lease, until);
  }

  public void lease(String id, String lease, long until) {
    mapper.lease(lease, until, id);
  }

  public void complete(String id, String result, long now) {
    mapper.complete(result, now, id);
  }
}
