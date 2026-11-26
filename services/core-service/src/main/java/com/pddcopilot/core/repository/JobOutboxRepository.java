package com.pddcopilot.core.repository;

import com.pddcopilot.core.mapper.JobOutboxMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Leased transactional-outbox records awaiting publication to the Redis job stream. */
@Repository
public class JobOutboxRepository {
  private final JobOutboxMapper mapper;

  public JobOutboxRepository(JobOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional
  public List<Map<String, Object>> claimDue(long now, long leaseUntil, String claimId, int limit) {
    return mapper.claimDue(now, limit, leaseUntil, claimId);
  }

  public int published(String id, String claimId, long timestamp) {
    return mapper.published(timestamp, id, claimId);
  }

  public int retry(String id, String claimId, long availableAt, String errorCode) {
    return mapper.retry(availableAt, errorCode, id, claimId);
  }

  public long countActive() {
    Long count = mapper.countActive();
    return count == null ? 0 : count;
  }
}
