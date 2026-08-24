package com.example.product.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Locale;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReplicaReadAspect {
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;
    private final boolean replicaEnabled;

    public ReplicaReadAspect(PlatformTransactionManager transactionManager,
                             MeterRegistry meterRegistry,
                             @Value("${product.datasource.replica.enabled:false}") boolean replicaEnabled) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry;
        this.replicaEnabled = replicaEnabled;
    }

    @Around("@annotation(ReplicaRead)")
    public Object read(ProceedingJoinPoint invocation) {
        if (!replicaEnabled) return invokeIn(ReplicaRoute.PRIMARY, invocation, "success");
        try {
            return invokeIn(ReplicaRoute.REPLICA, invocation, "success");
        } catch (RuntimeException failure) {
            if (!isConnectionFailure(failure)) throw failure;
            return invokeIn(ReplicaRoute.PRIMARY, invocation, "fallback");
        }
    }

    private Object invokeIn(ReplicaRoute route, ProceedingJoinPoint invocation, String outcome) {
        Object result = transaction.execute(status -> ReplicaRouteContext.call(route, () -> proceed(invocation)));
        meterRegistry.counter("product.datasource.route",
                "target", route.name().toLowerCase(Locale.ROOT), "outcome", outcome).increment();
        return result;
    }

    private static Object proceed(ProceedingJoinPoint invocation) {
        try {
            return invocation.proceed();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new UndeclaredThrowableException(failure);
        }
    }

    private static boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof JDBCConnectionException) return true;
        }
        return false;
    }
}
