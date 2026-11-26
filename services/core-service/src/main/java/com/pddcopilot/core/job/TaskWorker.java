package com.pddcopilot.core.job;

import com.pddcopilot.core.client.AgentClient;
import com.pddcopilot.core.service.AgentRunService;
import com.pddcopilot.core.service.JobService;
import com.pddcopilot.core.service.ProductService;
import com.pddcopilot.core.service.ReplySendService;
import com.pddcopilot.core.platform.pdd.PddCommerceService;
import com.pddcopilot.core.platform.pdd.PddSendService;
import com.pddcopilot.core.support.WorkflowValues;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.stream.MapRecord;

/** Claim work, call remote services outside a transaction, then persist under the original claim. */
@Component
public class TaskWorker {
  private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
  private final JobService jobs;
  private final RedisJobQueue queue;
  private final AgentRunService runs;
  private final ReplySendService sends;
  private final AgentClient agent;
  private final ProductService products;
  private final PddCommerceService commerce;
  private final PddSendService pddSend;
  private final MeterRegistry metrics;
  private final boolean enabled;
  private final ThreadPoolTaskExecutor executor;
  private final Semaphore slots;

  public TaskWorker(
      JobService jobs,
      RedisJobQueue queue,
      AgentRunService runs,
      ReplySendService sends,
      AgentClient agent,
      ProductService products,
      PddCommerceService commerce,
      PddSendService pddSend,
      MeterRegistry metrics,
      @Value("${copilot.worker-enabled}") boolean enabled,
      @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
      @Value("${copilot.worker-concurrency}") int concurrency) {
    this.jobs = jobs;
    this.queue = queue;
    this.runs = runs;
    this.sends = sends;
    this.agent = agent;
    this.products = products;
    this.commerce = commerce;
    this.pddSend = pddSend;
    this.metrics = metrics;
    this.enabled = enabled;
    this.executor = executor;
    this.slots = new Semaphore(concurrency);
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!enabled) return;
    int available = slots.availablePermits();
    if (available <= 0) return;
    List<MapRecord<String, Object, Object>> records = new ArrayList<>();
    try {
      records.addAll(queue.reclaimExpired(available, Duration.ofMinutes(3)));
      if (records.size() < available)
        records.addAll(queue.readNew(available - records.size()));
    } catch (RuntimeException error) {
      LOG.warn("job_queue_read_failed error_type={}", error.getClass().getSimpleName());
      return;
    }
    for (var record : records) {
      if (!slots.tryAcquire()) return;
      Object rawJobId = record.getValue().get("job_id");
      String jobId = rawJobId == null ? null : rawJobId.toString();
      if (jobId == null || jobId.isBlank()) {
        acknowledge(record);
        slots.release();
        continue;
      }
      Map<String, Object> job = null;
      try {
        job = jobs.claim(jobId);
        if (job == null) {
          jobs.deferDelivery(jobId);
          acknowledge(record);
          slots.release();
          continue;
        }
        Map<String, Object> claimedJob = job;
        executor.execute(
            () -> {
              try {
                process(claimedJob, record);
              } finally {
                slots.release();
              }
            });
      } catch (RuntimeException error) {
        if (job != null && jobs.fail(job)) acknowledge(record);
        slots.release();
        LOG.warn("job_queue_dispatch_failed error_type={}", error.getClass().getSimpleName());
      }
    }
  }

  public void process(Map<String, Object> job, MapRecord<String, Object, Object> record) {
    try (var scope = com.pddcopilot.core.support.ShopScope.open(job.get("shop_id").toString())) {
      processScoped(job, record);
    }
  }

  public void process(Map<String, Object> job) {
    try (var scope = com.pddcopilot.core.support.ShopScope.open(job.get("shop_id").toString())) {
      processScoped(job, null);
    }
  }

  private void processScoped(Map<String, Object> job, MapRecord<String, Object, Object> record) {
    String kind = (String) job.get("kind");
    String reference = (String) job.get("reference_id");
    long started = WorkflowValues.nowMillis();
    try {
      Map<String, Object> result =
          switch (kind) {
            case "generate" -> agent.generate(reference, runs.context(reference));
            case "send" -> pddSend.send(sends.command(reference));
            case "product_detail" ->
                commerce.productDetail(products.item(reference).get("goods_id").toString());
            default -> throw new IllegalStateException("UNKNOWN_JOB_KIND");
          };
      if (!jobs.complete(job, result)) return;
      acknowledge(record);
      metrics.counter("copilot.jobs", "kind", kind, "result", "completed").increment();
      LOG.info(
          "job_completed kind={} reference_id={} duration_ms={}",
          kind,
          reference,
          WorkflowValues.nowMillis() - started);
    } catch (Exception error) {
      if (!jobs.fail(job)) return;
      acknowledge(record);
      metrics.counter("copilot.jobs", "kind", kind, "result", "failed").increment();
      LOG.warn(
          "job_failed kind={} reference_id={} error_type={}",
          kind,
          reference,
          error.getClass().getSimpleName());
    }
  }

  private void acknowledge(MapRecord<String, Object, Object> record) {
    if (record == null) return;
    try {
      queue.acknowledge(record.getId());
    } catch (RuntimeException error) {
      LOG.warn("job_queue_ack_failed error_type={}", error.getClass().getSimpleName());
    }
  }
}
