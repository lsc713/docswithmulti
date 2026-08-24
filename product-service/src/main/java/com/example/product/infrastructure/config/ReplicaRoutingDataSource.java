package com.example.product.infrastructure.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class ReplicaRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ReplicaRouteContext.current();
    }
}
