package com.example.payment.infrastructure.scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class CancelOutboxRedriveTaskExecutor {

    private final ThreadPoolTaskExecutor executor;

    public CancelOutboxRedriveTaskExecutor(
        @Qualifier("cancelRedriveExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.executor = executor;
    }

    public boolean tryExecute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException exception) {
            return false;
        }
    }

    public int activeCount() {
        return executor.getActiveCount();
    }
}
