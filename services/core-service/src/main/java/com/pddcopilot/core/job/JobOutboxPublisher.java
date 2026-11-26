package com.pddcopilot.core.job;

import static com.pddcopilot.core.support.WorkflowValues.nowMillis;

import com.pddcopilot.core.repository.JobOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes committed job intents; duplicate stream entries are safe because jobs are DB-claimed. */
@Component
public class JobOutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(JobOutboxPublisher.class);
  private final JobOutboxRepository outbox;
  private final RedisJobQueue queue;
  private final MeterRegistry metrics;

  public JobOutboxPublisher(
      JobOutboxRepository outbox, RedisJobQueue queue, MeterRegistry metrics) {
    this.outbox = outbox;
    this.queue = queue;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${copilot.job-outbox-poll-ms:250}")
  public void publishDue() {
    long now = nowMillis();
    String claimId = UUID.randomUUID().toString();
    var events = outbox.claimDue(now, now + 15_000, claimId, 64);
    for (var event : events) {
      String id = event.get("id").toString();
      try {
        queue.publish(event.get("job_id").toString());
        outbox.published(id, claimId, nowMillis());
        metrics.counter("copilot.job.outbox", "result", "published").increment();
      } catch (RuntimeException error) {
        int attempts = ((Number) event.get("attempts")).intValue();
        long delay = Math.min(60_000L, 500L << Math.min(7, Math.max(0, attempts - 1)));
        outbox.retry(id, claimId, nowMillis() + delay, error.getClass().getSimpleName());
        metrics.counter("copilot.job.outbox", "result", "retry").increment();
        log.warn("job_outbox_publish_failed error_type={} retry_delay_ms={}",
            error.getClass().getSimpleName(), delay);
      }
    }
  }
}
