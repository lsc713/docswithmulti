package com.example.product.infrastructure.config;

import java.util.function.Supplier;

final class ReplicaRouteContext {
    private static final ThreadLocal<ReplicaRoute> CURRENT = new ThreadLocal<>();

    private ReplicaRouteContext() {
    }

    static ReplicaRoute current() {
        ReplicaRoute route = CURRENT.get();
        return route == null ? ReplicaRoute.PRIMARY : route;
    }

    static <T> T call(ReplicaRoute route, Supplier<T> action) {
        ReplicaRoute previous = CURRENT.get();
        CURRENT.set(route);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
