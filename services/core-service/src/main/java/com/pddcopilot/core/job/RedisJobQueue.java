package com.pddcopilot.core.job;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Streams transport; PostgreSQL remains authoritative for job state. */
@Component
public class RedisJobQueue {
  private final StringRedisTemplate redis;
  private final String stream;
  private final String group;
  private final String consumer = UUID.randomUUID().toString();
  private String pendingScanCursor = "-";

  public RedisJobQueue(
      StringRedisTemplate redis,
      @Value("${copilot.job-stream-key:copilot:jobs}") String stream,
      @Value("${copilot.job-consumer-group:copilot-workers}") String group) {
    this.redis = redis;
    this.stream = stream;
    this.group = group;
  }

  @PostConstruct
  public void ensureConsumerGroup() {
    if (!Boolean.TRUE.equals(redis.hasKey(stream)))
      redis.opsForStream().add(stream, Map.of("bootstrap", "true"));
    try {
      redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
    } catch (DataAccessException error) {
      if (!isExistingGroup(error)) throw error;
    }
  }

  public void publish(String jobId) {
    redis.opsForStream().add(stream, Map.of("job_id", jobId));
  }

  public List<MapRecord<String, Object, Object>> readNew(int count) {
    var records = redis.opsForStream().read(
        Consumer.from(group, consumer),
        StreamReadOptions.empty().count(count),
        StreamOffset.create(stream, ReadOffset.lastConsumed()));
    return records == null ? List.of() : records;
  }

  public List<MapRecord<String, Object, Object>> reclaimExpired(int count, Duration minIdle) {
    var pending = redis.opsForStream().pending(
        stream, group, Range.leftOpen(pendingScanCursor, "+"), Math.max(count, 64));
    if (pending.isEmpty()) {
      pendingScanCursor = "-";
      return List.of();
    }
    pendingScanCursor = pending.get(pending.size() - 1).getId().getValue();
    List<RecordId> expired = new ArrayList<>();
    for (PendingMessage message : pending) {
      if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
        expired.add(message.getId());
    }
    if (expired.isEmpty()) return List.of();
    var records = redis.opsForStream().claim(stream, group, consumer, minIdle, expired.toArray(RecordId[]::new));
    return records == null ? List.of() : records;
  }

  public void acknowledge(RecordId id) {
    redis.opsForStream().acknowledge(stream, group, id);
    redis.opsForStream().delete(stream, id);
  }

  public long streamLength() {
    Long length = redis.opsForStream().size(stream);
    return length == null ? 0 : length;
  }

  public long pendingCount() {
    var summary = redis.opsForStream().pending(stream, group);
    return summary == null ? 0 : summary.getTotalPendingMessages();
  }

  public String consumerName() {
    return consumer;
  }

  private boolean isExistingGroup(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) return true;
    return false;
  }
}
