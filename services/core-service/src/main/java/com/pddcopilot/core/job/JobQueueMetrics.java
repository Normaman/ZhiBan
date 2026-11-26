package com.pddcopilot.core.job;

import com.pddcopilot.core.repository.JobOutboxRepository;
import com.pddcopilot.core.repository.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Queue depth gauges exported through the existing Prometheus endpoint. */
@Component
public class JobQueueMetrics {
  public JobQueueMetrics(
      MeterRegistry registry,
      JobRepository jobs,
      JobOutboxRepository outbox,
      RedisJobQueue queue) {
    Gauge.builder("copilot.jobs.pending", jobs, repository -> repository.countState("pending"))
        .register(registry);
    Gauge.builder("copilot.jobs.running", jobs, repository -> repository.countState("running"))
        .register(registry);
    Gauge.builder("copilot.jobs.failed", jobs, repository -> repository.countState("failed"))
        .register(registry);
    Gauge.builder("copilot.job.outbox.active", outbox, JobOutboxRepository::countActive)
        .register(registry);
    Gauge.builder("copilot.job.stream.entries", queue, RedisJobQueue::streamLength)
        .register(registry);
    Gauge.builder("copilot.job.stream.pending", queue, RedisJobQueue::pendingCount)
        .register(registry);
  }
}
