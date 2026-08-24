# Product MySQL EC2 Read Replica Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EC2 Docker MySQL read replica에 상품 상세의 제한된 읽기를 보내고 정상·지연·연결 장애에서 성능, lag, fallback, 재고 정합성을 재현 가능하게 측정한다.

**Architecture:** `product-replica` 부하 테스트 profile은 기존 Product 4대 topology에 같은 사양의 MySQL 8 replica 한 대를 추가하고 GTID 비동기 복제를 사용한다. Product 서비스는 기본 primary routing을 유지하며 `@ReplicaRead`가 붙은 상세 본문 loader와 재고 snapshot cache loader만 replica read-only transaction으로 실행하고, 연결 계열 오류만 primary에서 한 번 재시도한다. 기존 혼합 부하 runner가 marker lag probe와 SQL applier/컨테이너 장애를 함께 구동해 A/B 결과 번들을 만든다.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, Spring AOP, HikariCP, MySQL 8.0 GTID replication, Docker Compose, Terraform/AWS EC2/SSM, Bash, k6, Prometheus.

**Spec:** `docs/superpowers/specs/2026-08-24-product-mysql-read-replica-design.md`

## Global Constraints

- `reserve`, `release`, 멱등·잠금 조회, `refreshAfterCommit`, Flyway와 scheduler/consumer는 항상 primary를 사용한다.
- replica 애플리케이션 계정은 `SELECT`만 허용하고 replica는 `read_only=ON`, `super_read_only=ON`을 유지한다.
- lag은 관측만 하며 lag 임계치 기반 fallback은 만들지 않는다.
- 연결 거부·connection timeout·통신 단절만 primary에서 한 번 재시도한다. SQL·매핑·도메인 오류는 재시도하지 않는다.
- `product-replica` profile을 명시한 경우에만 추가 EC2를 생성한다. 기존 `full`, `product`, `product-scaleout` topology는 바꾸지 않는다.
- 첫 실험에는 ProxySQL, replica 승격, 자동 failover, 다중 replica, cross-AZ, 운영 heartbeat scheduler를 추가하지 않는다.
- 애플리케이션 변경은 TDD로 진행하고, shell/Terraform 변경은 기존 정적 검사에 가장 작은 회귀 검사를 추가한다.
- 기존 작업트리의 사용자 변경을 보존하고 각 task에서 명시한 파일만 stage한다.

---

### Task 1: Primary/Replica DataSource Routing 기반

**Files:**
- Modify: `product-service/build.gradle`
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRoute.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRouteContext.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRoutingDataSource.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ProductDataSourceConfig.java`
- Create: `product-service/src/test/java/com/example/product/infrastructure/config/ReplicaRoutingDataSourceTest.java`
- Modify: `product-service/src/main/resources/application.yml`

**Interfaces:**
- Produces: `ReplicaRoute.PRIMARY`, `ReplicaRoute.REPLICA`.
- Produces: `ReplicaRouteContext.current(): ReplicaRoute`, `ReplicaRouteContext.call(ReplicaRoute, Supplier<T>): T` with nested-call restoration in `finally`.
- Produces: primary bean `primaryDataSource`, optional bean `replicaDataSource`, and `@Primary` lazy routing bean `dataSource`.
- Produces properties `product.datasource.replica.enabled`, `.url`, `.username`, `.password`, `.hikari.connection-timeout`.

- [ ] **Step 1: Add a failing routing test**

Create a test-only subclass exposing `determineCurrentLookupKey()` and assert default, replica scope, and restoration:

```java
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
        Object key() { return determineCurrentLookupKey(); }
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :product-service:test --tests '*ReplicaRoutingDataSourceTest'
```

Expected: compilation fails because the routing types do not exist.

- [ ] **Step 3: Implement the route context and routing DataSource**

Use one `ThreadLocal`; do not introduce a general tenant/context abstraction:

```java
public enum ReplicaRoute { PRIMARY, REPLICA }

final class ReplicaRouteContext {
    private static final ThreadLocal<ReplicaRoute> CURRENT = new ThreadLocal<>();

    static ReplicaRoute current() {
        return CURRENT.get() == null ? ReplicaRoute.PRIMARY : CURRENT.get();
    }

    static <T> T call(ReplicaRoute route, Supplier<T> action) {
        ReplicaRoute previous = CURRENT.get();
        CURRENT.set(route);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}

public final class ReplicaRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ReplicaRouteContext.current();
    }
}
```

- [ ] **Step 4: Configure two pools without changing Boot's public primary properties**

Add `spring-boot-starter-aop` to `product-service/build.gradle`. In `ProductDataSourceConfig`, bind the existing `spring.datasource` properties to `primaryDataSource`, bind `product.datasource.replica` only when enabled, map both into `ReplicaRoutingDataSource`, set primary as its default, call `afterPropertiesSet()`, and expose it through `LazyConnectionDataSourceProxy`. The replica pool uses a 500 ms connection timeout; primary keeps the existing 30 s timeout.

Add these safe defaults to `application.yml`:

```yaml
product:
  datasource:
    replica:
      enabled: ${PRODUCT_DATASOURCE_REPLICA_ENABLED:false}
      url: ${PRODUCT_DATASOURCE_REPLICA_URL:}
      username: ${PRODUCT_DATASOURCE_REPLICA_USERNAME:product_reader}
      password: ${PRODUCT_DATASOURCE_REPLICA_PASSWORD:product_reader}
      hikari:
        connection-timeout: ${PRODUCT_DATASOURCE_REPLICA_CONNECTION_TIMEOUT_MS:500}
```

When `enabled=false`, map `ReplicaRoute.REPLICA` to the primary pool rather than creating a second pool. This preserves local/test startup and the primary-only A/B mode.

- [ ] **Step 5: Run the focused and existing transaction-boundary tests**

Run:

```bash
./gradlew :product-service:test \
  --tests '*ReplicaRoutingDataSourceTest' \
  --tests '*ProductQueryTransactionBoundaryTest'
```

Expected: both test classes pass; the existing detail cache-hit test still records no physical DB transaction.

- [ ] **Step 6: Commit the routing foundation**

```bash
git add product-service/build.gradle \
  product-service/src/main/resources/application.yml \
  product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRoute.java \
  product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRouteContext.java \
  product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRoutingDataSource.java \
  product-service/src/main/java/com/example/product/infrastructure/config/ProductDataSourceConfig.java \
  product-service/src/test/java/com/example/product/infrastructure/config/ReplicaRoutingDataSourceTest.java
git commit -m "feat(product): add primary replica datasource routing"
```

### Task 2: 제한된 `@ReplicaRead`와 연결 장애 Fallback

**Files:**
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRead.java`
- Create: `product-service/src/main/java/com/example/product/infrastructure/config/ReplicaReadAspect.java`
- Create: `product-service/src/test/java/com/example/product/infrastructure/config/ReplicaReadAspectTest.java`
- Modify: `product-service/src/main/java/com/example/product/application/service/ProductDetailLoader.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheService.java`
- Modify: `product-service/src/test/java/com/example/product/application/service/ProductQueryTransactionBoundaryTest.java`
- Modify: `product-service/src/test/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheServiceTest.java`

**Interfaces:**
- Consumes: `ReplicaRouteContext.call(ReplicaRoute, Supplier<T>)` from Task 1.
- Produces: method annotation `@ReplicaRead`.
- Produces metric `product.datasource.route{target="primary|replica",outcome="success|fallback"}`.
- Produces: one replica read-only transaction; on a connection-class failure only, one fresh primary read-only transaction.

- [ ] **Step 1: Write failing aspect tests**

Test the interceptor directly with a recording transaction manager, `SimpleMeterRegistry`, and mocked `ProceedingJoinPoint`. Cover all four contracts:

```java
@Test void enabled_call_uses_replica_route() { /* proceed() observes REPLICA once */ }
@Test void disabled_call_uses_primary_route() { /* proceed() observes PRIMARY once */ }
@Test void connection_failure_retries_once_on_primary() {
    when(joinPoint.proceed())
        .thenThrow(new CannotCreateTransactionException("replica down"))
        .thenAnswer(ignored -> ReplicaRouteContext.current());
    assertThat(aspect.read(joinPoint)).isEqualTo(ReplicaRoute.PRIMARY);
    verify(joinPoint, times(2)).proceed();
}
@Test void domain_or_sql_error_is_not_retried() {
    when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("bad row"));
    assertThatThrownBy(() -> aspect.read(joinPoint)).isInstanceOf(IllegalArgumentException.class);
    verify(joinPoint).proceed();
}
```

Assert the fallback counter is exactly 1 only in the connection-failure test.

- [ ] **Step 2: Run the aspect test and verify RED**

```bash
./gradlew :product-service:test --tests '*ReplicaReadAspectTest'
```

Expected: compilation fails because `ReplicaRead` and `ReplicaReadAspect` do not exist.

- [ ] **Step 3: Implement the smallest annotation interceptor**

`@ReplicaRead` targets methods and is retained at runtime. `ReplicaReadAspect` has highest precedence, creates a read-only `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, and invokes the join point inside the selected route. Walk the cause chain and permit fallback only for `CannotCreateTransactionException`, `CannotGetJdbcConnectionException`, and Hibernate `JDBCConnectionException`; do not classify generic `DataAccessException` or `DataAccessResourceFailureException` as a connection failure.

The control flow is exactly:

```java
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
```

Always restore the ThreadLocal in `finally`; do not add retries beyond the one primary execution.

- [ ] **Step 4: Mark only the two approved read boundaries**

- Replace `@Transactional(readOnly = true)` on `ProductDetailLoader.load` with `@ReplicaRead`.
- Add `@ReplicaRead` to public `ProductStockSnapshotCacheService.getOrLoad`.
- Do not annotate `refreshAfterCommit`, `refresh`, `StockService`, category, attribute, scheduler, or consumer code.
- Keep `LazyConnectionDataSourceProxy`, so a Redis/detail cache hit does not acquire a physical DB connection.

- [ ] **Step 5: Update service tests to prove cache hits and refresh remain correct**

Wire `ReplicaReadAspect`, `SimpleMeterRegistry`, and `@EnableAspectJAutoProxy` into `ProductQueryTransactionBoundaryTest.Config`. Assert detail cache miss opens one read-only transaction and the route counter reports replica when enabled; cache hit plus mocked stock cache remains transaction-free.

In `ProductStockSnapshotCacheServiceTest`, keep existing cache tests and add a reflection/annotation assertion that only `getOrLoad` carries `@ReplicaRead`, while `refreshAfterCommit` does not:

```java
assertThat(ProductStockSnapshotCacheService.class.getMethod("getOrLoad", Long.class)
        .isAnnotationPresent(ReplicaRead.class)).isTrue();
assertThat(ProductStockSnapshotCacheService.class
        .getMethod("refreshAfterCommit", ProductStockChangedEvent.class)
        .isAnnotationPresent(ReplicaRead.class)).isFalse();
```

- [ ] **Step 6: Run focused tests, then the full Product suite**

```bash
./gradlew :product-service:test \
  --tests '*ReplicaReadAspectTest' \
  --tests '*ProductQueryTransactionBoundaryTest' \
  --tests '*ProductStockSnapshotCacheServiceTest'
./gradlew :product-service:test
```

Expected: all selected tests and the full Product suite pass with zero failures.

- [ ] **Step 7: Commit explicit read routing**

```bash
git add product-service/src/main/java/com/example/product/infrastructure/config/ReplicaRead.java \
  product-service/src/main/java/com/example/product/infrastructure/config/ReplicaReadAspect.java \
  product-service/src/main/java/com/example/product/application/service/ProductDetailLoader.java \
  product-service/src/main/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheService.java \
  product-service/src/test/java/com/example/product/infrastructure/config/ReplicaReadAspectTest.java \
  product-service/src/test/java/com/example/product/application/service/ProductQueryTransactionBoundaryTest.java \
  product-service/src/test/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheServiceTest.java
git commit -m "feat(product): route detail cache misses to replica"
```

### Task 3: Replica 전용 Terraform Profile

**Files:**
- Modify: `infra/load-test/variables.tf`
- Modify: `infra/load-test/instances.tf`
- Modify: `infra/load-test/network.tf`
- Modify: `infra/load-test/product-nlb.tf`
- Modify: `infra/load-test/outputs.tf`
- Create: `infra/load-test/product-replica-static-test.sh`

**Interfaces:**
- Produces: `load_test_profile=product-replica`.
- Produces roles/IPs: source `mysql-product=10.0.1.33`, replica `mysql-product-replica=10.0.1.34`.
- Preserves: Product a/b/c/d NLB and existing scaleout roles.

- [ ] **Step 1: Add a failing profile static test**

The script must assert all of these exact contracts with `rg -q`: accepted profile value, replica role/IP, NLB enabled for both scaleout profiles, NAT-instance egress, and gp3 values applied to both MySQL roles.

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
rg -q 'product-replica' "$ROOT/infra/load-test/variables.tf"
rg -q 'mysql-product-replica.*10.0.1.34' "$ROOT/infra/load-test/instances.tf"
rg -q 'contains(\["product-scaleout", "product-replica"\]' "$ROOT/infra/load-test/product-nlb.tf"
rg -q 'contains(\["mysql-product", "mysql-product-replica"\], each.key)' "$ROOT/infra/load-test/instances.tf"
```

- [ ] **Step 2: Run the static test and verify RED**

```bash
bash infra/load-test/product-replica-static-test.sh
```

Expected: the first missing `product-replica` contract fails.

- [ ] **Step 3: Add `product-replica` as an explicit copy of scaleout plus one DB**

Add `local.product_replica_instances` with k6, Product a/b/c/d, source MySQL, replica MySQL, Redis, and obs. Use `m7g.large`, 50 GiB gp3, `10.0.1.34` for the replica. Extend only profile-selection conditions in network, NLB, and output files; do not change existing profile instance maps.

Apply `mysql_gp3_iops` and `mysql_gp3_throughput` when `each.key` is either MySQL role:

```hcl
iops = contains(["mysql-product", "mysql-product-replica"], each.key) ? var.mysql_gp3_iops : null
```

- [ ] **Step 4: Format and validate the Terraform shape**

```bash
terraform -chdir=infra/load-test fmt -check
bash infra/load-test/product-only-static-test.sh
bash infra/load-test/product-replica-static-test.sh
terraform -chdir=infra/load-test validate
```

Expected: formatting, both static tests, and Terraform validation pass. Do not run `apply` in this task.

- [ ] **Step 5: Commit the isolated profile**

```bash
git add infra/load-test/variables.tf infra/load-test/instances.tf \
  infra/load-test/network.tf infra/load-test/product-nlb.tf infra/load-test/outputs.tf \
  infra/load-test/product-replica-static-test.sh
git commit -m "feat(load): add product replica terraform profile"
```

### Task 4: MySQL GTID Source/Replica Deployment and Smoke

**Files:**
- Modify: `infra/load-test/deploy/mysql-product.compose.yml`
- Create: `infra/load-test/deploy/mysql-product-init/01-replication-users.sql`
- Create: `infra/load-test/deploy/mysql-product-replica.compose.yml`
- Create: `infra/load-test/deploy/mysql-product-replica-smoke.sh`
- Create: `infra/load-test/deploy/mysql-product-replica-static-test.sh`
- Modify: `infra/load-test/deploy/ssm-deploy.sh`
- Modify: `infra/load-test/deploy/README.md`

**Interfaces:**
- Consumes: roles and IPs from Task 3.
- Produces: replication user `product_replicator`, read-only app user `product_reader`.
- Produces: healthy `Replica_IO_Running=Yes`, `Replica_SQL_Running=Yes`, GTID auto-position.

- [ ] **Step 1: Write the failing compose/deploy static test**

Assert source flags, replica flags, role mapping, and deployment order:

```bash
rg -q -- '--server-id=1' mysql-product.compose.yml
rg -q -- '--gtid-mode=ON' mysql-product.compose.yml
rg -q -- '--server-id=2' mysql-product-replica.compose.yml
rg -q -- '--super-read-only=ON' mysql-product-replica.compose.yml
rg -q 'SOURCE_AUTO_POSITION=1' ssm-deploy.sh
rg -q 'mysql-product mysql-product-replica redis-product product-a' ssm-deploy.sh
```

- [ ] **Step 2: Run the static test and verify RED**

```bash
bash infra/load-test/deploy/mysql-product-replica-static-test.sh
```

Expected: missing replica compose/deployment contracts fail.

- [ ] **Step 3: Enable GTID on the disposable source**

Keep the existing buffer pool and connection flags and add:

```yaml
command:
  - --server-id=1
  - --log-bin=mysql-bin
  - --binlog-format=ROW
  - --gtid-mode=ON
  - --enforce-gtid-consistency=ON
```

Mount `mysql-product-init/01-replication-users.sql` read-only into `/docker-entrypoint-initdb.d/`. The SQL creates `product_replicator` with `REPLICATION SLAVE, REPLICATION CLIENT` and `product_reader` with `SELECT` on `product_db.*`. Use the existing disposable test credentials and document that they are non-production.

- [ ] **Step 4: Add the blank read-only replica compose**

Do not set `MYSQL_DATABASE` or `MYSQL_USER` on the replica; source DDL/users arrive through replication. Configure:

```yaml
command:
  - --server-id=2
  - --relay-log=mysql-relay-bin
  - --gtid-mode=ON
  - --enforce-gtid-consistency=ON
  - --read-only=ON
  - --super-read-only=ON
```

Add a healthcheck using `mysqladmin ping`, a named volume, and port 3306 on the replica host.

- [ ] **Step 5: Extend SSM deployment in source → replica → app order**

Accept `product-replica`, map `mysql-product-replica` to its compose, reuse the Product scaleout override, and set:

```text
ORDER="mysql-product mysql-product-replica redis-product product-a product-b product-c product-d"
```

After the replica container is healthy, execute once:

```sql
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='10.0.1.33',
  SOURCE_USER='product_replicator',
  SOURCE_PASSWORD='product_replicator',
  SOURCE_AUTO_POSITION=1;
START REPLICA;
```

Make reruns idempotent by checking `SHOW REPLICA STATUS` first; if the configured source host is already `10.0.1.33`, only start stopped threads.

- [ ] **Step 6: Add a runnable replication smoke**

`mysql-product-replica-smoke.sh` runs on the replica host and:

1. Asserts both replication threads are `Yes`.
2. Creates a unique `loadtest_replication_smoke` row on source using the run key.
3. Polls replica for at most 30 seconds until the row appears.
4. Verifies `product_reader` can SELECT it.
5. Verifies `product_reader` INSERT fails.
6. Deletes the row on source and waits for its replicated removal.

The table is explicitly load-test-only and is created only in the disposable profile.

- [ ] **Step 7: Run local static checks**

```bash
bash infra/load-test/deploy/mysql-product-replica-static-test.sh
bash -n infra/load-test/deploy/mysql-product-replica-smoke.sh
bash -n infra/load-test/deploy/ssm-deploy.sh
```

Expected: all commands exit 0.

- [ ] **Step 8: Commit replication deployment**

```bash
git add infra/load-test/deploy/mysql-product.compose.yml \
  infra/load-test/deploy/mysql-product-init/01-replication-users.sql \
  infra/load-test/deploy/mysql-product-replica.compose.yml \
  infra/load-test/deploy/mysql-product-replica-smoke.sh \
  infra/load-test/deploy/mysql-product-replica-static-test.sh \
  infra/load-test/deploy/ssm-deploy.sh infra/load-test/deploy/README.md
git commit -m "feat(load): deploy mysql product read replica"
```

### Task 5: Product Replica Wiring and Observability

**Files:**
- Modify: `infra/load-test/deploy/product.compose.yml`
- Modify: `infra/load-test/deploy/product-scaleout.compose.yml`
- Modify: `infra/load-test/deploy/ssm-deploy.sh`
- Modify: `infra/load-test/observability/product-only.compose.yml`
- Modify: `infra/load-test/observability/product-only-prometheus.yml`
- Modify: `k6/run-product-stock-mix-aws.sh`
- Modify: `k6/product-stock-mix-runner-test.sh`

**Interfaces:**
- Consumes: Product replica properties from Task 1 and `10.0.1.34` from Task 3.
- Produces: profile-controlled `PRODUCT_DATASOURCE_REPLICA_*` environment variables.
- Produces Prometheus host `mysql-product-replica` and DB label `product-replica`.

- [ ] **Step 1: Extend the runner test first**

Assert printed queries include `product.datasource.route`, and assert the host regex contains `mysql-product-replica`. Also assert `product.compose.yml` exposes all four replica variables and does not replace the primary URL.

- [ ] **Step 2: Run the runner test and verify RED**

```bash
bash k6/product-stock-mix-runner-test.sh
```

Expected: route metric/replica host assertions fail.

- [ ] **Step 3: Wire replica environment only for the replica profile**

Keep the existing primary variables unchanged. Add to Product compose:

```yaml
PRODUCT_DATASOURCE_REPLICA_ENABLED: "${PRODUCT_DATASOURCE_REPLICA_ENABLED:-false}"
PRODUCT_DATASOURCE_REPLICA_URL: "jdbc:mysql://10.0.1.34:3306/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
PRODUCT_DATASOURCE_REPLICA_USERNAME: product_reader
PRODUCT_DATASOURCE_REPLICA_PASSWORD: product_reader
```

In `ssm-deploy.sh`, default `PRODUCT_DATASOURCE_REPLICA_ENABLED` to `true` only when
`LOAD_TEST_PROFILE=product-replica`, but preserve an explicitly supplied `true` or `false` so the same
topology can run the primary-only A/B control. All other profiles force the value to `false`.

- [ ] **Step 4: Add replica host and exporter observation**

Add replica node-exporter target `10.0.1.34:9100`. Add a second mysqld-exporter pointed at
`10.0.1.34:3306` and scrape it with `db: product-replica`; keep the source exporter label
`db: product`. Extend runner CPU/memory host regex and add:

```bash
DATASOURCE_ROUTE_QUERY='product_datasource_route_total{host=~"product-a|product-b|product-c|product-d"}'
```

Store route counter query results beside each stage's cache metrics.

- [ ] **Step 5: Run static checks**

```bash
bash k6/product-stock-mix-runner-test.sh
bash -n infra/load-test/deploy/ssm-deploy.sh
IMAGE_NS=test docker compose -f infra/load-test/deploy/product.compose.yml \
  -f infra/load-test/deploy/product-scaleout.compose.yml config >/dev/null
docker compose -f infra/load-test/observability/product-only.compose.yml config >/dev/null
```

Expected: all checks exit 0.

- [ ] **Step 6: Commit deployment wiring**

```bash
git add infra/load-test/deploy/product.compose.yml \
  infra/load-test/deploy/product-scaleout.compose.yml \
  infra/load-test/deploy/ssm-deploy.sh \
  infra/load-test/observability/product-only.compose.yml \
  infra/load-test/observability/product-only-prometheus.yml \
  k6/run-product-stock-mix-aws.sh k6/product-stock-mix-runner-test.sh
git commit -m "feat(load): observe product replica routing"
```

### Task 6: Marker Lag Probe and Fault Injection Runner

**Files:**
- Create: `k6/product-replica-probe.sh`
- Create: `k6/product-replica-probe-test.sh`
- Modify: `k6/run-product-stock-mix-aws.sh`
- Modify: `k6/product-stock-mix-runner-test.sh`

**Interfaces:**
- Produces modes `REPLICA_EXPERIMENT=baseline|steady|lag|outage`.
- Produces artifacts `<run>.replica-lag.tsv`, `<run>.replica-status.tsv`, `<run>.replica-faults.tsv`, `<run>.replica-stale.tsv`.
- `lag` pauses SQL apply for 5, 30, and 60 seconds while source I/O remains running.
- `outage` stops the replica container for 60 seconds and starts it again before collection ends.

- [ ] **Step 1: Write a deterministic probe self-test**

Make the probe accept injected commands `SOURCE_MYSQL`, `REPLICA_MYSQL`, `MONOTONIC_MS`, and
`SLEEP` so the test can use shell fakes. The test feeds source sequences `1,2,3`, delayed replica
sequences `0,1,3`, then asserts TSV header, monotonic non-negative lag, final sequence 3, and cleanup.
Also test invalid mode rejection in `product-stock-mix-runner-test.sh`.

- [ ] **Step 2: Run probe/runner tests and verify RED**

```bash
bash k6/product-replica-probe-test.sh
bash k6/product-stock-mix-runner-test.sh
```

Expected: the missing probe and mode contracts fail.

- [ ] **Step 3: Implement the disposable marker probe**

The probe runs on `mysql-product-replica` EC2 and uses the local MySQL container client for both
connections. It creates `loadtest_replication_heartbeat(run_key, sequence, sent_at)` on source,
writes a new sequence once per second, polls the replica every 100 ms, and records elapsed time from
the replica host's `/proc/uptime` monotonic clock. It also samples `SHOW REPLICA STATUS` once per
second. Quote `run_key` as data, validate it against `^[A-Za-z0-9._-]+$`, and never interpolate an
unvalidated identifier into SQL.

The lag TSV columns are:

```text
run_key sequence sent_monotonic_ms observed_monotonic_ms lag_ms observed_utc
```

The status TSV columns are:

```text
observed_utc replica_io_running replica_sql_running seconds_behind_source retrieved_gtid_set executed_gtid_set
```

Reserve the first seed pair for the stale-read probe and send only the remaining pairs to k6, so normal
mixed traffic cannot mutate the probe SKU. Pass `PROBE_PRODUCT_ID`, `PROBE_SKU_ID`, and `PRODUCT_URL`
to the remote script. The stale TSV records pause length, replica-visible quantity, primary reserve HTTP
status, convergence quantity, and final restored quantity.

- [ ] **Step 4: Add bounded fault schedules**

For `lag`, record start/end UTC in the faults TSV and run:

```text
warm 60s → STOP REPLICA SQL_THREAD 5s → recover 60s
→ STOP 30s → recover 90s → STOP 60s → recover 120s
```

For `outage`, warm 60s, `docker stop mysql-product-replica`, wait 60s, then
`docker start mysql-product-replica` and wait until both threads report `Yes`. A trap must restart
the SQL thread/container on script exit so a failed run does not leave the reusable stack broken.

During the 30-second SQL pause, prove user-visible consistency behavior with the isolated probe SKU:

1. Reserve its full seeded quantity (`qty=100`) through `POST /v1/stock/reserve` on the Product NLB.
2. Wait six seconds so the primary-populated five-second stock cache entry expires.
3. Read `GET /v1/products/{probeProductId}` and record the replica's stale `availableQty=100`.
4. Attempt a second `qty=1` reserve with a new payment key and require HTTP 409 from primary.
5. Restart SQL apply, wait for marker convergence and another cache expiry, then require displayed
   `availableQty=0`.
6. Release the original `qty=100`, wait for replication, and require source, replica, and displayed
   quantity to return to 100.

The trap releases the probe reservation when it exists, even if a later assertion fails.

- [ ] **Step 5: Orchestrate the probe beside the existing k6 command**

When mode is not `baseline`, resolve the `mysql-product-replica` SSM instance, start the probe/fault
command asynchronously, then start k6. Wait for both commands, fetch the four TSV files through the
existing chunked artifact mechanism, and include all four TSV files in the tar bundle. `baseline` must not require
a replica instance. `steady` runs the probe with no fault. Preserve the existing summary checksum and
stage validation.

- [ ] **Step 6: Add artifact validation**

For `steady|lag`, fail the run when the lag/status TSV is absent, final observed marker does not equal
the source final marker, or either replication thread is not `Yes` after recovery. For `outage`, require
route `outcome=fallback` to increase and server error rate to remain zero. Do not assert that RPS or CPU
must improve. For `lag`, also require stale TSV values `100 → primary 409 → 0 → restored 100` in that
order.

- [ ] **Step 7: Run all local runner checks**

```bash
bash k6/product-replica-probe-test.sh
bash k6/product-stock-mix-runner-test.sh
bash -n k6/product-replica-probe.sh
bash -n k6/run-product-stock-mix-aws.sh
```

Expected: every command exits 0.

- [ ] **Step 8: Commit the experiment runner**

```bash
git add k6/product-replica-probe.sh k6/product-replica-probe-test.sh \
  k6/run-product-stock-mix-aws.sh k6/product-stock-mix-runner-test.sh
git commit -m "feat(load): measure and inject mysql replica lag"
```

### Task 7: Full Verification and AWS A/B Experiment

**Files:**
- Create: `docs/load-test/product-read-replica-results-2026-08-24.md`
- Modify only if a verified defect is found: files from Tasks 1–6

**Interfaces:**
- Consumes: deployable image/ref from Tasks 1–6 and all result artifacts.
- Produces: primary-only, steady replica, lag injection, and outage evidence with a final adoption recommendation.

- [ ] **Step 1: Run the complete local verification gate**

```bash
./gradlew :product-service:test
bash infra/load-test/product-only-static-test.sh
bash infra/load-test/product-replica-static-test.sh
bash infra/load-test/deploy/mysql-product-replica-static-test.sh
bash k6/product-replica-probe-test.sh
bash k6/product-stock-mix-runner-test.sh
terraform -chdir=infra/load-test fmt -check
terraform -chdir=infra/load-test validate
git diff --check
```

Expected: every command exits 0 and Product tests report zero failures.

- [ ] **Step 2: Commit any verification-only correction separately**

If Step 1 exposes a defect, add only the directly affected files, rerun Step 1, and commit with a
message naming the defect. If Step 1 is already green, make no empty commit.

- [ ] **Step 3: Push the exact implementation ref and publish its immutable Product image**

```bash
export IMAGE_NS=camelia9999
export REPO_REF=$(git rev-parse HEAD)
git push origin main
test "$REPO_REF" = "$(git rev-parse origin/main)"
docker buildx build --platform linux/arm64 \
  -f infra/load-test/deploy/Dockerfile \
  --build-arg MODULE=product-service \
  -t "$IMAGE_NS/cancel-loadtest:product-$REPO_REF" \
  --push .
```

Expected: the pushed image digest is printed. Use the same `REPO_REF` for SSM checkout and image tag.

- [ ] **Step 4: Create only the replica test topology**

```bash
terraform -chdir=infra/load-test apply -auto-approve \
  -var='load_test_profile=product-replica'
terraform -chdir=infra/load-test output private_ips
```

Expected: output contains Product a/b/c/d, `mysql-product=10.0.1.33`,
`mysql-product-replica=10.0.1.34`, Redis, k6, and obs. This step starts AWS billing and requires the
normal execution-time approval before running.

- [ ] **Step 5: Deploy source, replica, Product nodes, and observability**

```bash
LOAD_TEST_PROFILE=product-replica \
IMAGE_NS="$IMAGE_NS" IMAGE_TAG="$REPO_REF" REPO_REF="$REPO_REF" \
./infra/load-test/deploy/ssm-deploy.sh
```

Expected: source and replica become healthy before Product starts; replication smoke reports both
threads `Yes`, marker arrival, reader SELECT success, and reader write rejection.

- [ ] **Step 6: Seed once through the source**

Open the source DB SSM tunnel in terminal 1:

```bash
LOCAL_PORT=13306 ./infra/load-test/deploy/port-forward.sh product-db
```

Seed in terminal 2:

```bash
MYSQL_PORT=13306 SEED_COUNT=1000 ./k6/seed/product-detail-seed.sh
jq -e 'length == 1000' k6/seed/productIds.json >/dev/null
counts=$(mysql -h127.0.0.1 -P13306 -uproduct -pproduct -N product_db \
  -e 'SELECT (SELECT COUNT(*) FROM product), (SELECT COUNT(*) FROM product_sku)')
test "$counts" = $'1000\t9000'
```

Close the tunnel only after the count assertion passes; reuse `k6/seed/productIds.json` for all four runs.

- [ ] **Step 7: Run A primary-only and B steady replica**

For A, redeploy only Product a/b/c/d with replica routing explicitly disabled:

```bash
LOAD_TEST_PROFILE=product-replica PRODUCT_DATASOURCE_REPLICA_ENABLED=false \
IMAGE_NS="$IMAGE_NS" IMAGE_TAG="$REPO_REF" REPO_REF="$REPO_REF" \
ROLES="product-a product-b product-c product-d" \
./infra/load-test/deploy/ssm-deploy.sh
```

Then run:

```bash
REPLICA_EXPERIMENT=baseline REPO_REF="$REPO_REF" \
PRODUCT_URL="http://$(terraform -chdir=infra/load-test output -raw product_load_balancer_dns):8084" \
./k6/run-product-stock-mix-aws.sh
```

For B, redeploy those four nodes with replica enabled and run steady mode:

```bash
LOAD_TEST_PROFILE=product-replica PRODUCT_DATASOURCE_REPLICA_ENABLED=true \
IMAGE_NS="$IMAGE_NS" IMAGE_TAG="$REPO_REF" REPO_REF="$REPO_REF" \
ROLES="product-a product-b product-c product-d" \
./infra/load-test/deploy/ssm-deploy.sh

REPLICA_EXPERIMENT=steady REPO_REF="$REPO_REF" \
PRODUCT_URL="http://$(terraform -chdir=infra/load-test output -raw product_load_balancer_dns):8084" \
./k6/run-product-stock-mix-aws.sh
```

Do not reseed or restart MySQL between A and B.

- [ ] **Step 8: Run C lag injection and D outage**

```bash
REPLICA_EXPERIMENT=lag REPO_REF="$REPO_REF" \
PRODUCT_URL="http://$(terraform -chdir=infra/load-test output -raw product_load_balancer_dns):8084" \
./k6/run-product-stock-mix-aws.sh

REPLICA_EXPERIMENT=outage REPO_REF="$REPO_REF" \
PRODUCT_URL="http://$(terraform -chdir=infra/load-test output -raw product_load_balancer_dns):8084" \
./k6/run-product-stock-mix-aws.sh
```

Expected: C records all three pause windows and final convergence; D records fallback increments,
zero server errors, and a healthy restarted replica.

- [ ] **Step 9: Cross-check correctness before interpreting performance**

Collect source/replica Performance Schema digests and verify:

- detail/stock cache miss SELECT appears on replica in B/C/D;
- reservation upsert, stock UPDATE/restore, COMMIT, and refresh SELECT remain on source;
- server and unexpected 4xx errors are zero in A/B/C;
- D fallback success is 100%;
- no RESERVED rows remain and 9,000 SKU quantities/sum equal the seed expectation;
- final marker sequence and query results converge after C/D recovery.

Stop analysis and fix the runner/routing if any provenance or correctness check fails; do not publish
performance numbers from an invalid run.

- [ ] **Step 10: Write the source-backed result report**

Create `docs/load-test/product-read-replica-results-2026-08-24.md` with exact run keys and tables for:

- A vs B read/write RPS, p95/p99, errors, Product/source/replica/Redis/k6 CPU;
- normal lag p50/p95/p99/max;
- 5/30/60-second pause observed stale window and catch-up time;
- outage fallback count and p95/p99 delta;
- SQL digest routing proof and final stock/reservation checks;
- limitations, including sequential A/B order and current read-path source CPU of 13.33%;
- adopt/defer recommendation based on measured benefit and consistency cost.

- [ ] **Step 11: Destroy all AWS resources and prove empty state**

```bash
terraform -chdir=infra/load-test destroy -auto-approve \
  -var='load_test_profile=product-replica'
test -z "$(terraform -chdir=infra/load-test state list)"
```

Expected: destroy completes and the state-list assertion exits 0.

- [ ] **Step 12: Verify and commit the report**

```bash
test -s docs/load-test/product-read-replica-results-2026-08-24.md
rg -n 'A |B |lag|fallback|oversell|terraform destroy' \
  docs/load-test/product-read-replica-results-2026-08-24.md
git diff --check
git add docs/load-test/product-read-replica-results-2026-08-24.md
git commit -m "docs(load-test): record product replica experiment"
```

Expected: the report is non-empty, contains the required evidence, has no whitespace errors, and the
commit contains only the report and retained result artifacts intentionally selected for version control.
