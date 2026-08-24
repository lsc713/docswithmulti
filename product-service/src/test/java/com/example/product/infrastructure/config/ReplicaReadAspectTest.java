package com.example.product.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplicaReadAspectTest {

    @Test
    void enabled_call_uses_replica_route() throws Throwable {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReplicaReadAspect aspect = new ReplicaReadAspect(transactions, registry, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(ignored -> ReplicaRouteContext.current());

        assertThat(aspect.read(joinPoint)).isEqualTo(ReplicaRoute.REPLICA);
        verify(joinPoint).proceed();
        assertThat(transactions.definitions).hasSize(1).allSatisfy(definition -> {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        });
        assertThat(routeCount(registry, "replica", "success")).isEqualTo(1);
        assertThat(routeCount(registry, "primary", "fallback")).isZero();
    }

    @Test
    void disabled_call_uses_primary_route() throws Throwable {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReplicaReadAspect aspect = new ReplicaReadAspect(transactions, registry, false);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(ignored -> ReplicaRouteContext.current());

        assertThat(aspect.read(joinPoint)).isEqualTo(ReplicaRoute.PRIMARY);
        verify(joinPoint).proceed();
        assertThat(transactions.definitions).hasSize(1).allSatisfy(definition -> {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        });
        assertThat(routeCount(registry, "primary", "success")).isEqualTo(1);
        assertThat(routeCount(registry, "primary", "fallback")).isZero();
    }

    @Test
    void connection_failure_retries_once_on_primary() throws Throwable {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReplicaReadAspect aspect = new ReplicaReadAspect(transactions, registry, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed())
                .thenThrow(new CannotCreateTransactionException("replica down"))
                .thenAnswer(ignored -> ReplicaRouteContext.current());

        assertThat(aspect.read(joinPoint)).isEqualTo(ReplicaRoute.PRIMARY);
        verify(joinPoint, times(2)).proceed();
        assertThat(transactions.definitions).hasSize(2).allSatisfy(definition -> {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        });
        assertThat(routeCount(registry, "replica", "success")).isZero();
        assertThat(routeCount(registry, "primary", "fallback")).isEqualTo(1);
    }

    @Test
    void domain_or_sql_error_is_not_retried() throws Throwable {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReplicaReadAspect aspect = new ReplicaReadAspect(transactions, registry, true);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("bad row"));

        assertThatThrownBy(() -> aspect.read(joinPoint)).isInstanceOf(IllegalArgumentException.class);
        verify(joinPoint).proceed();
        assertThat(routeCount(registry, "primary", "fallback")).isZero();
    }

    private static double routeCount(SimpleMeterRegistry registry, String target, String outcome) {
        Counter counter = registry.find("product.datasource.route")
                .tags("target", target, "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<TransactionDefinition> definitions = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            definitions.add(definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
