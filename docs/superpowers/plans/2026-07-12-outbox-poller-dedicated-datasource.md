# 폴러 전용 DataSource Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OUTBOX 폴러의 배수 경로(`findPendingBatch`·`markPublished`)를 소형 전용 HikariDataSource(2)+JdbcTemplate로 격리해 라이브락(공유 풀 굶음)을 없앤다. `insertPending`은 메인 풀(TX3 원자성) 유지.

**Architecture:** payment-service에 명시적 `@Primary` 메인 DataSource(기존 auto-config 대체) + OUTBOX 전용 `cancelOutboxDataSource`(소형) + `cancelOutboxJdbcTemplate`(NamedParameterJdbcTemplate)를 추가한다. `CancelEventOutboxRepositoryImpl`이 `insertPending`은 기존 JPA(메인 풀/TX3), `findPendingBatch`·`markPublished`는 전용 JdbcTemplate로 라우팅한다. 전용 DataSource·JdbcTemplate·repo 빈은 모두 OUTBOX 모드 전용.

**Tech Stack:** Java 21 · Spring Boot 3.x · HikariCP · Spring JDBC(NamedParameterJdbcTemplate) · MySQL 8 · JUnit 5 + Testcontainers

## Global Constraints

- **`insertPending`은 메인 풀 유지** — 취소 TX3(`CancelTxWriter.saveTx3`, `REQUIRES_NEW`) 안에서 호출되어 비즈니스 커밋과 원자적이어야 함(outbox 불변식). 전용 풀로 옮기면 dual-write 재발 → 금지.
- **`findPendingBatch`·`markPublished`만 전용 풀** — 배수 경로, 격리 대상.
- **send-then-mark 불변식 유지** (mark-before-send 금지 = 이벤트 유실). 폴러 로직(`CancelEventOutboxPublisher`)은 무변경.
- **전용 리소스는 OUTBOX 모드 전용** — `@ConditionalOnProperty(name="cancel.publish.mode", havingValue="OUTBOX")`. INLINE(기본/프로덕션)에선 전용 풀·JdbcTemplate·repo 빈 미생성(유휴 커넥션 0).
- **메인 DataSource 명시화 필수** — 2번째 DataSource 빈 추가 시 Boot가 메인 auto-config를 백오프하므로, 메인도 `@Primary`로 명시 정의. 바인딩은 기존 `spring.datasource`·`spring.datasource.hikari`와 동일해야 함(connection-timeout 30000, initialization-fail-timeout -1).
- 전용 풀: 같은 payment_db, `maximum-pool-size=2`, `connection-timeout=5000`(fail-fast).
- domain 레이어 Spring/JPA 금지. TDD, 잦은 커밋.

---

### Task 1: DataSource 배선 — 명시적 @Primary 메인 + OUTBOX 전용 풀/JdbcTemplate

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/config/OutboxDataSourceConfig.java`
- Modify: `payment-service/src/main/resources/application.yml`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/config/OutboxDataSourceConfigIT.java`

**Interfaces:**
- Produces:
  - `@Primary HikariDataSource dataSource` (메인, `spring.datasource`+`spring.datasource.hikari`)
  - `HikariDataSource cancelOutboxDataSource` (OUTBOX 전용, 같은 DB, pool 2) — bean name `cancelOutboxDataSource`
  - `NamedParameterJdbcTemplate cancelOutboxJdbcTemplate` (OUTBOX 전용, `cancelOutboxDataSource` 위) — Task 2가 소비

- [ ] **Step 1: 실패 테스트 작성** (OUTBOX 풀-컨텍스트에서 전용 풀 빈이 pool=2로 뜨고 메인과 격리)

`OutboxDataSourceConfigIT.java` — 풀 `@SpringBootTest` OUTBOX 모드. `ProcessingRecoveryOutboxIT`의 컨테이너·기동 스텁 패턴을 그대로 따른다(컴포넌트 스캔으로 `OutboxDataSourceConfig` 로드 → 실제 배선 검증):
```java
package com.example.payment.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=OUTBOX")
@DisplayName("OutboxDataSourceConfig: OUTBOX 전용 풀/템플릿 배선")
class OutboxDataSourceConfigIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OUTBOX 컨텍스트 기동용 스텁 (스케줄러·외부 클라이언트)
    @MockitoBean PgCancelPortStub pgStub;   // 아래 대체: 실제 필요한 MockitoBean은 컨텍스트 기동 실패 로그로 확인
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired DataSource dataSource;                                  // @Primary 메인
    @Autowired @Qualifier("cancelOutboxDataSource") HikariDataSource cancelOutboxDataSource;
    @Autowired NamedParameterJdbcTemplate cancelOutboxJdbcTemplate;

    @Test
    @DisplayName("메인 DataSource와 전용 풀(pool=2)이 각각 뜨고 격리된다")
    void beans_wired() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(cancelOutboxDataSource.getMaximumPoolSize()).isEqualTo(2);
        assertThat(cancelOutboxJdbcTemplate).isNotNull();
        assertThat(cancelOutboxDataSource).isNotSameAs(dataSource); // 격리
    }
}
```
※ `@MockitoBean` 목록은 `ProcessingRecoveryOutboxIT`와 동일하게 맞춘다: 그 파일이 OUTBOX 풀-컨텍스트 기동에 필요로 하는 스텁은 `PgCancelPort`·`RiskManagementPort`·`KafkaTemplate`·`RedissonClient`. 위 스켈레톤의 `PgCancelPortStub` 자리를 실제 `@MockitoBean com.example.payment.application.interfaces.PgCancelPort pgCancelPort;` + `@MockitoBean com.example.payment.application.interfaces.RiskManagementPort riskManagementPort;`로 교체(컨텍스트가 뜨는 최소 스텁 셋 = ProcessingRecoveryOutboxIT 그대로 복사). 목표는 "OUTBOX 풀-컨텍스트가 뜨고 전용 풀 빈이 pool=2".

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*OutboxDataSourceConfigIT"`
Expected: FAIL (`cancelOutboxDataSource`/`cancelOutboxJdbcTemplate` 빈 없음 — NoSuchBeanDefinitionException)

- [ ] **Step 3: OutboxDataSourceConfig 작성**

`OutboxDataSourceConfig.java`:
```java
package com.example.payment.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 메인 DataSource를 명시적으로 정의(Boot auto-config 대체)하고,
 * OUTBOX 모드에서만 폴러 배수 전용 소형 풀 + JdbcTemplate 을 추가한다.
 * 2번째 DataSource 빈 추가 시 Boot가 메인을 백오프하므로 메인도 여기서 @Primary 로 정의.
 */
@Configuration
public class OutboxDataSourceConfig {

    // ── 메인 DataSource (기존 auto-config 대체, 바인딩 동일) ──
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
            .type(HikariDataSource.class).build();
    }

    // ── OUTBOX 전용 폴러 풀 (같은 DB, 소형) ──
    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    @ConfigurationProperties("cancel.outbox.datasource.hikari")
    public HikariDataSource cancelOutboxDataSource(DataSourceProperties dataSourceProperties) {
        // 메인 props(url/user/pass) 재사용 → 같은 payment_db, hikari 블록만 별도
        return dataSourceProperties.initializeDataSourceBuilder()
            .type(HikariDataSource.class).build();
    }

    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    public NamedParameterJdbcTemplate cancelOutboxJdbcTemplate(HikariDataSource cancelOutboxDataSource) {
        return new NamedParameterJdbcTemplate(cancelOutboxDataSource);
    }
}
```

- [ ] **Step 4: application.yml에 전용 풀 설정 추가**

`application.yml`의 기존 `cancel:` 블록(§Task3에서 추가된 `cancel.publish`/`cancel.outbox.poll-ms` 근처)에 `datasource` 추가:
```yaml
cancel:
  outbox:
    datasource:
      hikari:
        maximum-pool-size: 2
        connection-timeout: 5000
```
(url/username/password는 명시 안 함 — `cancelOutboxDataSource`가 메인 `DataSourceProperties`에서 재사용하므로 같은 payment_db.)

- [ ] **Step 5: 테스트 통과 + 풀-컨텍스트 회귀 확인**

Run: `./gradlew :payment-service:test --tests "*OutboxDataSourceConfigIT" --tests "*CancelFlowIntegrationTest" --tests "*ProcessingRecoveryOutboxIT"`
Expected: PASS.
- `OutboxDataSourceConfigIT`(OUTBOX): 전용 풀 pool=2 + 격리 확인.
- `CancelFlowIntegrationTest`(풀 @SpringBootTest, **INLINE 기본**): 명시적 @Primary 메인 DataSource가 auto-config와 동등 + INLINE에서 전용 풀 미생성 → 컨텍스트 정상 = **회귀 가드**.
- `ProcessingRecoveryOutboxIT`(풀 @SpringBootTest, **OUTBOX**): 메인+전용 풀 공존 컨텍스트 정상 = **회귀 가드**.
- 빈 중복(`DataSource`)·백오프 문제로 컨텍스트 로딩 실패 없어야 함.
(주의: `AbstractRepositoryTest` 기반 슬라이스 IT는 `OutboxDataSourceConfig`를 스캔 안 하므로 이 회귀는 풀-컨텍스트 테스트로만 가드됨.)

- [ ] **Step 6: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/config/OutboxDataSourceConfig.java \
        payment-service/src/main/resources/application.yml \
        payment-service/src/test/java/com/example/payment/infrastructure/config/OutboxDataSourceConfigIT.java
git commit -m "feat(outbox): 명시적 @Primary 메인 DataSource + OUTBOX 전용 폴러 풀/JdbcTemplate"
```

---

### Task 2: repo 라우팅 — find+mark를 전용 JdbcTemplate로, repo 빈 OUTBOX 게이팅

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java` (죽은 메서드 제거)
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java` (repo 빈 제거)
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/OutboxDataSourceConfig.java` (repo 빈 이동·OUTBOX 게이팅)
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryIT.java` (생성자 변경 반영)

**Interfaces:**
- Consumes: `NamedParameterJdbcTemplate cancelOutboxJdbcTemplate` (Task 1), `CancelEventOutboxJpaRepository`(기존).
- Produces: `CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository, NamedParameterJdbcTemplate)` — insertPending→JPA, find+mark→JdbcTemplate. repo 빈은 OUTBOX 전용.

- [ ] **Step 1: 실패 테스트 작성** (IT를 새 생성자 + find/mark가 JdbcTemplate 경유로 동작하도록)

`CancelEventOutboxRepositoryIT.java` 수정 — `setUp`에서 전용 JdbcTemplate을 테스트 DataSource로 구성해 새 생성자에 주입(SQL 로직 검증; 풀 격리는 AWS 재측정 몫):
```java
    @Autowired
    CancelEventOutboxJpaRepository jpa;

    @Autowired
    javax.sql.DataSource dataSource;

    CancelEventOutboxRepository repo;

    @BeforeEach
    void setUp() {
        var jdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);
        repo = new CancelEventOutboxRepositoryImpl(jpa, jdbc);
    }
```
(기존 테스트 메서드 `idempotent_insert`·`mark_published_excludes`·`batch_mark_published_marks_only_given_ids`·`batch_mark_published_empty_is_noop`는 그대로 — 이제 insertPending은 JPA, find/mark는 JdbcTemplate 경유로 실행되어 동일 결과여야 함.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests "*CancelEventOutboxRepositoryIT"`
Expected: FAIL (컴파일 에러 — `CancelEventOutboxRepositoryImpl` 생성자가 아직 1-인자)

- [ ] **Step 3: impl 라우팅 구현**

`CancelEventOutboxRepositoryImpl.java` 전체 교체:
```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;          // insertPending — 메인 풀/TX3
    private final NamedParameterJdbcTemplate outboxJdbc;                 // find+mark — 전용 풀

    public CancelEventOutboxRepositoryImpl(
            CancelEventOutboxJpaRepository jpaRepository,
            NamedParameterJdbcTemplate outboxJdbc) {
        this.jpaRepository = jpaRepository;
        this.outboxJdbc = outboxJdbc;
    }

    @Override
    public void insertPending(long cancelRequestId, String payload) {
        // 취소 TX3 안 — 메인 풀 유지(비즈니스 커밋과 원자적)
        jpaRepository.insertPendingIdempotent(cancelRequestId, payload);
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        // 폴러 배수 경로 — 전용 풀
        return outboxJdbc.query(
            "SELECT id, cancel_request_id, payload FROM cancel_event_outbox "
                + "WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit",
            new MapSqlParameterSource("limit", limit),
            (rs, n) -> new PendingOutbox(
                rs.getLong("id"), rs.getLong("cancel_request_id"), rs.getString("payload")));
    }

    @Override
    public void markPublished(List<Long> outboxIds) {
        if (outboxIds.isEmpty()) {
            return; // WHERE id IN () 방지
        }
        // 폴러 배수 경로 — 전용 풀. 배치 UPDATE(커넥션 1회).
        outboxJdbc.update(
            "UPDATE cancel_event_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3) "
                + "WHERE id IN (:ids)",
            new MapSqlParameterSource("ids", outboxIds));
    }
}
```

- [ ] **Step 4: JpaRepository 죽은 메서드 제거**

`CancelEventOutboxJpaRepository.java` — `findByStatusOrderByCreatedAtAsc`와 `markPublishedBatch`는 이제 JdbcTemplate로 대체돼 미사용. 제거하고 `insertPendingIdempotent`만 남긴다:
```java
package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    /** cancel_request_id UK 충돌 시 no-op (복구 재실행 멱등). 취소 TX3 안에서 호출 = 메인 풀. */
    @Modifying
    @Query(value = """
        INSERT INTO cancel_event_outbox (cancel_request_id, payload, status, created_at)
        VALUES (:cancelRequestId, :payload, 'PENDING', CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE cancel_request_id = cancel_request_id
        """, nativeQuery = true)
    void insertPendingIdempotent(long cancelRequestId, String payload);
}
```
(unused import `Pageable`, `List` 제거됨.)

- [ ] **Step 5: repo 빈을 PersistenceConfig에서 제거하고 OutboxDataSourceConfig로 이동(OUTBOX 게이팅)**

`PersistenceConfig.java`에서 `cancelEventOutboxRepository` @Bean 메서드 + 관련 import(`CancelEventOutboxRepository`, `CancelEventOutboxRepositoryImpl`) **삭제**.

`OutboxDataSourceConfig.java`에 추가(OUTBOX 전용 — 전용 JdbcTemplate에 의존하므로 자연히 OUTBOX에서만):
```java
    @Bean
    @ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
    public com.example.payment.application.interfaces.CancelEventOutboxRepository cancelEventOutboxRepository(
            com.example.payment.infrastructure.persistence.CancelEventOutboxJpaRepository jpaRepository,
            NamedParameterJdbcTemplate cancelOutboxJdbcTemplate) {
        return new com.example.payment.infrastructure.persistence.CancelEventOutboxRepositoryImpl(
            jpaRepository, cancelOutboxJdbcTemplate);
    }
```

- [ ] **Step 6: 테스트 통과 + 전체 회귀**

Run: `./gradlew :payment-service:test --tests "*CancelEventOutboxRepositoryIT" --tests "*CancelEventOutboxPublisherIT" --tests "*ProcessingRecoveryOutbox*" --tests "*OutboxDataSourceConfigIT"`
Expected: PASS (find/mark가 JdbcTemplate 경유로 동일 동작, 스케줄러 IT 3종 그대로).
그다음 전체: `./gradlew :payment-service:test`
Expected: BUILD SUCCESSFUL (회귀 없음).

- [ ] **Step 7: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java \
        payment-service/src/main/java/com/example/payment/infrastructure/config/OutboxDataSourceConfig.java \
        payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryIT.java
git commit -m "feat(outbox): 폴러 find+mark를 전용 JdbcTemplate로 라우팅 (insertPending 메인 풀 유지)"
```

---

## 검증 (코드 아님, AWS 재측정 — 스펙 §9)

머지 후 별도 AWS 런(런북 `docs/load-test/publish-pattern-benchmark.md`): OUTBOX `poll=1s` stress, PROM 끄고 outbox DB 직접 샘플링. **판정:** PENDING 낮게 유지(naive 79.8k~101k 대비) · `order_processed`가 실제 취소수 수렴(1,000 갇힘 해소) · `producer_send`가 취소수 근접(42만 재발송 폭주 소멸) → 라이브락 해소. 남는 단일 스레드 배수율 상한 → CDC 후속.

---

## Self-Review

**1. Spec coverage:**
- §3.1 경로별 풀 분리(insertPending 메인 / find+mark 전용) → Task 2 impl 라우팅. ✅
- §4.1 전용 DataSource+JdbcTemplate(OUTBOX 전용, pool 2) → Task 1. ✅
- §4.2 repo 라우팅 + INLINE 배선(OUTBOX 게이팅) → Task 2 Step 5(repo 빈 OUTBOX-gated). ✅
- §6 불변식(send-then-mark·insertPending 원자성·JdbcTemplate auto-commit) → 폴러 무변경 + insertPending JPA 유지. ✅
- §8 테스트 → Task1 빈배선 IT + Task2 repo IT + 스케줄러 IT 회귀. ✅
- 멀티-DS 함정(메인 백오프) → Global Constraints + Task1 명시적 @Primary. ✅

**2. Placeholder scan:** 코드 스텝 전부 실제 코드. Task1 Step1의 "AbstractRepositoryTest 초기화 방식 확인"은 플레이스홀더가 아니라 기존 테스트 인프라에 맞추라는 구체 지시(초기화 클래스명이 코드베이스 의존이라 구현자가 확인). 그 외 TBD/TODO 없음.

**3. Type consistency:** `CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository, NamedParameterJdbcTemplate)` — Task2 정의, Task2 IT·OutboxDataSourceConfig 빈 일치. `cancelOutboxJdbcTemplate`(NamedParameterJdbcTemplate) — Task1 정의, Task2 소비 일치. `insertPendingIdempotent` 유지, `findByStatusOrderByCreatedAtAsc`·`markPublishedBatch` 제거(Task2 Step4) — impl이 더는 호출 안 함 일치. ✅
