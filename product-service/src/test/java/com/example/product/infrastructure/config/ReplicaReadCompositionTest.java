package com.example.product.infrastructure.config;

import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.infrastructure.cache.ProductStockSnapshotCacheService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplicaReadCompositionTest {

    @Test
    void replica_success_selects_only_replica_and_restores_route() {
        RoutingFixture fixture = routing(0, 0);

        ReadObservation observation = fixture.reader.read(null);

        assertThat(observation).isEqualTo(new ReadObservation(ReplicaRoute.REPLICA, true, true));
        assertThat(fixture.replica.connections).isEqualTo(1);
        assertThat(fixture.primary.connections).isZero();
        assertThat(fixture.transactions.begins).hasSize(1).allSatisfy(this::assertReplicaTransaction);
        assertThat(ReplicaRouteContext.current()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    void replica_connection_failure_uses_fresh_primary_transaction_once() {
        RoutingFixture fixture = routing(0, 1);

        assertThat(fixture.reader.read(null).route()).isEqualTo(ReplicaRoute.PRIMARY);

        assertThat(fixture.replica.connections).isEqualTo(1);
        assertThat(fixture.primary.connections).isEqualTo(1);
        assertThat(fixture.transactions.begins).hasSize(2).allSatisfy(this::assertReplicaTransaction);
        assertThat(fixture.transactions.begins.get(0).transaction)
                .isNotSameAs(fixture.transactions.begins.get(1).transaction);
        assertThat(ReplicaRouteContext.current()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @ParameterizedTest
    @MethodSource("nonConnectionFailures")
    void non_connection_failure_does_not_select_primary(RuntimeException failure) {
        RoutingFixture fixture = routing(0, 0);

        assertThatThrownBy(() -> fixture.reader.read(failure)).isSameAs(failure);

        assertThat(fixture.replica.connections).isEqualTo(1);
        assertThat(fixture.primary.connections).isZero();
        assertThat(fixture.transactions.begins).hasSize(1);
        assertThat(ReplicaRouteContext.current()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    void primary_fallback_failure_propagates_without_third_attempt() {
        RoutingFixture fixture = routing(1, 1);

        assertThatThrownBy(() -> fixture.reader.read(null))
                .isInstanceOf(CannotGetJdbcConnectionException.class);

        assertThat(fixture.replica.connections).isEqualTo(1);
        assertThat(fixture.primary.connections).isEqualTo(1);
        assertThat(fixture.transactions.begins).hasSize(2);
        assertThat(ReplicaRouteContext.current()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stock_cache_hit_through_proxy_does_not_acquire_database_connection() {
        RoutingFixture fixture = routing(0, 0);
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(bucket.get()).thenReturn(Map.of(101L, 7));
        ProductStockSnapshotCacheService target = new ProductStockSnapshotCacheService(
                redisson, repository, new SimpleMeterRegistry(), 5);
        ProductStockSnapshotCacheService service = proxy(target, fixture.aspect);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);

        assertThat(fixture.replica.connections).isZero();
        assertThat(fixture.primary.connections).isZero();
        assertThat(fixture.transactions.begins).hasSize(1).allSatisfy(this::assertReplicaTransaction);
        verifyNoInteractions(repository);
        assertThat(ReplicaRouteContext.current()).isEqualTo(ReplicaRoute.PRIMARY);
    }

    private void assertReplicaTransaction(TransactionBegin begin) {
        assertThat(begin.readOnly).isTrue();
        assertThat(begin.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static Stream<RuntimeException> nonConnectionFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("generic resource failure"),
                new BadSqlGrammarException("read", "select broken", new SQLException("bad SQL")),
                new IllegalArgumentException("bad mapping"));
    }

    private static RoutingFixture routing(int primaryFailures, int replicaFailures) {
        CountingDataSource primary = new CountingDataSource();
        CountingDataSource replica = new CountingDataSource();
        ReplicaRoutingDataSource routing = new ReplicaRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                ReplicaRoute.PRIMARY, primary,
                ReplicaRoute.REPLICA, replica));
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();
        LazyConnectionDataSourceProxy dataSource = new LazyConnectionDataSourceProxy(routing);
        dataSource.checkDefaultConnectionProperties();
        primary.reset(primaryFailures);
        replica.reset(replicaFailures);
        RecordingTransactionManager transactions = new RecordingTransactionManager(dataSource);
        ReplicaReadAspect aspect = new ReplicaReadAspect(transactions, new SimpleMeterRegistry(), true);
        return new RoutingFixture(primary, replica, transactions, aspect,
                proxy(new RoutedReader(dataSource), aspect));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, ReplicaReadAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    private record RoutingFixture(CountingDataSource primary,
                                  CountingDataSource replica,
                                  RecordingTransactionManager transactions,
                                  ReplicaReadAspect aspect,
                                  RoutedReader reader) {
    }

    private record ReadObservation(ReplicaRoute route, boolean transactionActive, boolean readOnly) {
    }

    private record TransactionBegin(Object transaction, boolean readOnly, int propagation) {
    }

    static class RoutedReader {
        private final DataSource dataSource;

        RoutedReader(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @ReplicaRead
        public ReadObservation read(RuntimeException failure) {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                connection.getMetaData();
            } catch (SQLException error) {
                throw new CannotGetJdbcConnectionException("connection failed", error);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
            if (failure != null) throw failure;
            return new ReadObservation(
                    ReplicaRouteContext.current(),
                    TransactionSynchronizationManager.isActualTransactionActive(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        }
    }

    static class RecordingTransactionManager extends DataSourceTransactionManager {
        private final List<TransactionBegin> begins = new ArrayList<>();

        RecordingTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            begins.add(new TransactionBegin(
                    transaction, definition.isReadOnly(), definition.getPropagationBehavior()));
        }
    }

    static class CountingDataSource extends AbstractDataSource {
        private int failures;
        private int connections;

        void reset(int failures) {
            this.failures = failures;
            this.connections = 0;
        }

        @Override
        public Connection getConnection() throws SQLException {
            connections++;
            if (failures-- > 0) throw new SQLException("connection unavailable");
            return connection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private static Connection connection() {
            return mock(Connection.class);
        }
    }
}
