package com.example.product.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaRoutingDataSourceTest {
    private final ExposedRoutingDataSource routing = new ExposedRoutingDataSource();

    @Test
    void defaults_to_primary() {
        assertThat(routing.key()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    void replica_scope_is_restored_after_success_and_failure() {
        assertThat(ReplicaRouteContext.call(ReplicaRoute.REPLICA, routing::key))
                .isEqualTo(ReplicaRoute.REPLICA);
        assertThatThrownBy(() -> ReplicaRouteContext.call(ReplicaRoute.REPLICA, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(routing.key()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    static final class ExposedRoutingDataSource extends ReplicaRoutingDataSource {
        Object key() {
            return determineCurrentLookupKey();
        }
    }
}
