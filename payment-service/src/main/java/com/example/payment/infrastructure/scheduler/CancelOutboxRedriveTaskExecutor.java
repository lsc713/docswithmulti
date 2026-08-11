package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.CancelOutboxRedriveTelemetry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class CancelOutboxRedriveTaskExecutor {

    private final ThreadPoolTaskExecutor executor;
    private final CancelOutboxRedriveTelemetry telemetry;

    public CancelOutboxRedriveTaskExecutor(
        @Qualifier("cancelRedriveExecutor") ThreadPoolTaskExecutor executor,
        CancelOutboxRedriveTelemetry telemetry
    ) {
        this.executor = executor;
        this.telemetry = telemetry;
    }

    public boolean tryExecute(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException exception) {
            telemetry.executorRejected();
            return false;
        }
    }

    public int activeCount() {
        return executor.getActiveCount();
    }
}
