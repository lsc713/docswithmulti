package com.example.product.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaRoutingDataSourceTest {
    private final ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();

    @Test
    void defaults_to_primary() {
        assertThat(routing.determineCurrentLookupKey()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    void replica_scope_is_restored_after_success_and_failure() {
        assertThat(ReplicaRouteContext.call(ReplicaRoute.REPLICA, routing::determineCurrentLookupKey))
                .isEqualTo(ReplicaRoute.REPLICA);
        assertThat(routing.determineCurrentLookupKey()).isEqualTo(ReplicaRoute.PRIMARY);
        assertThatThrownBy(() -> ReplicaRouteContext.call(ReplicaRoute.REPLICA, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(routing.determineCurrentLookupKey()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    void nested_replica_scope_restores_outer_primary_scope() {
        assertThat(ReplicaRouteContext.call(ReplicaRoute.PRIMARY, () -> {
            assertThat(ReplicaRouteContext.call(ReplicaRoute.REPLICA, routing::determineCurrentLookupKey))
                    .isEqualTo(ReplicaRoute.REPLICA);
            return routing.determineCurrentLookupKey();
        })).isEqualTo(ReplicaRoute.PRIMARY);
    }
}
