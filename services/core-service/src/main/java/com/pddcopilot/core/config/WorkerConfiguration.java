package com.pddcopilot.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class WorkerConfiguration {
  @Bean("orderOverviewExecutor")
  public ThreadPoolTaskExecutor orderOverviewExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("order-overview-");
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }

  @Bean("orderViewExecutor")
  public ThreadPoolTaskExecutor orderViewExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("order-view-");
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }

  @Bean("agentTaskExecutor")
  public ThreadPoolTaskExecutor agentTaskExecutor(
      @Value("${copilot.worker-concurrency}") int concurrency) {
    if (concurrency < 1 || concurrency > 16)
      throw new IllegalArgumentException("WORKER_CONCURRENCY must be between 1 and 16");
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("agent-job-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }
}
