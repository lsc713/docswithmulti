package com.example.payment.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CancelOutboxRedriveExecutorConfig {

    @Bean(name = "cancelRedriveExecutor")
    public ThreadPoolTaskExecutor cancelRedriveExecutor(
        @Value("${cancel.redrive.max-concurrency:5}") int maxConcurrency,
        @Value("${cancel.redrive.shutdown-await-seconds:10}") int shutdownAwaitSeconds
    ) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be greater than 0");
        }
        if (shutdownAwaitSeconds <= 0) {
            throw new IllegalArgumentException("shutdownAwaitSeconds must be greater than 0");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrency);
        executor.setMaxPoolSize(maxConcurrency);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("cancel-redrive-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        return executor;
    }
}
