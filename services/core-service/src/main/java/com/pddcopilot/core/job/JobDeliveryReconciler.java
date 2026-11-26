package com.pddcopilot.core.job;

import com.pddcopilot.core.service.JobService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-publishes due PostgreSQL jobs if Redis loses a previously published stream entry. */
@Component
public class JobDeliveryReconciler {
  private static final Logger log = LoggerFactory.getLogger(JobDeliveryReconciler.class);
  private final JobService jobs;
  private final MeterRegistry metrics;

  public JobDeliveryReconciler(JobService jobs, MeterRegistry metrics) {
    this.jobs = jobs;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${copilot.job-reconcile-ms:30000}")
  public void reconcile() {
    try {
      int count = jobs.reconcileUndelivered();
      if (count > 0) {
        metrics.counter("copilot.job.reconcile", "result", "republished").increment(count);
        log.warn("job_delivery_reconciled count={}", count);
      }
    } catch (RuntimeException error) {
      metrics.counter("copilot.job.reconcile", "result", "failed").increment();
      log.warn("job_delivery_reconcile_failed error_type={}", error.getClass().getSimpleName());
    }
  }
}
