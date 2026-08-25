package com.example.product.infrastructure.scheduler;

import com.example.product.application.interfaces.CancelRestoreDlqRepository;
import com.example.product.application.service.CancelRestoreRedriveService;
import com.example.product.application.service.OrphanReservationRecoveryService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrphanReservationRecoverySchedulerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerConfiguration.class)
            .withPropertyValues(
                    "orphan.threshold-minutes=5",
                    "scheduler.lock.orphan-recovery=lock:orphan",
                    "scheduler.lock.cancel-restore-redrive=lock:cancel-restore")
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(OrphanReservationRecoveryService.class,
                    () -> mock(OrphanReservationRecoveryService.class))
            .withBean(CancelRestoreDlqRepository.class,
                    () -> mock(CancelRestoreDlqRepository.class))
            .withBean(CancelRestoreRedriveService.class,
                    () -> mock(CancelRestoreRedriveService.class))
            .withBean(ObjectMapper.class, () -> mock(ObjectMapper.class));

    @Test
    void orphanRecoveryIsEnabledByDefaultForFullTopology() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OrphanReservationRecoveryScheduler.class);
            assertThat(context).hasSingleBean(CancelRestoreRedriveScheduler.class);
        });
    }

    @Test
    void disablingOrphanRecoveryDoesNotDisableCancelRestore() {
        runner.withPropertyValues("product.orphan-recovery.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OrphanReservationRecoveryScheduler.class);
                    assertThat(context).hasSingleBean(CancelRestoreRedriveScheduler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OrphanReservationRecoveryScheduler.class, CancelRestoreRedriveScheduler.class})
    static class SchedulerConfiguration {
    }
}
