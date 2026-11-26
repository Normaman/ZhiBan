package com.pddcopilot.core.service;

import com.pddcopilot.core.mapper.AuthenticationEventMapper;
import com.pddcopilot.core.repository.UserRepository;
import com.pddcopilot.core.support.SecurityDigest;
import com.pddcopilot.core.support.WorkflowValues;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationAudit {
  private final AuthenticationEventMapper mapper;
  private final UserRepository users;
  private final SecurityDigest digest;
  private final MeterRegistry metrics;

  public AuthenticationAudit(
      AuthenticationEventMapper mapper,
      UserRepository users,
      SecurityDigest digest,
      MeterRegistry metrics) {
    this.mapper = mapper;
    this.users = users;
    this.digest = digest;
    this.metrics = metrics;
  }

  public void record(String type, String username, String address) {
    var account = users.findByUsername(username);
    mapper.record(
        WorkflowValues.newId(),
        type,
        account.map(a -> a.id()).orElse(null),
        digest.hash(username),
        digest.hash(address),
        WorkflowValues.nowMillis());
    metrics.counter("copilot.auth.events", "type", type).increment();
    if (type.endsWith("failed") || type.endsWith("limited"))
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("authentication_event type={} subject={}", type, digest.hash(username));
  }

  public List<Map<String, Object>> recent(String username) {
    return mapper.recent(username);
  }
}
