---
phase: 01-product-leg-hardening
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - product-service/src/main/resources/db/migration/V5__create_cancel_restore_dlq.sql
  - product-service/src/main/java/com/example/product/application/interfaces/CancelRestoreDlqRepository.java
  - product-service/src/main/java/com/example/product/application/interfaces/OperationAlertPort.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqJpaEntity.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqJpaRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqRepositoryImpl.java
  - product-service/src/main/java/com/example/product/infrastructure/adapter/LogOperationAlertAdapter.java
  - product-service/src/main/java/com/example/product/application/service/CancelRestoreRedriveService.java
  - product-service/src/main/java/com/example/product/infrastructure/scheduler/CancelRestoreRedriveScheduler.java
  - product-service/src/main/java/com/example/product/infrastructure/messaging/RetryRouter.java
  - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockConsumer.java
  - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockRetryConsumer.java
  - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
  - product-service/src/main/java/com/example/product/infrastructure/config/KafkaConsumerConfig.java
  - product-service/src/main/resources/application.yml
  - product-service/src/test/java/com/example/product/integration/CancelRestoreConvergenceIntegrationTest.java
  - product-service/src/test/java/com/example/product/integration/CancelRestoreLossIntegrationTest.java
  - product-service/src/test/java/com/example/product/integration/CancelRestoreDeadEscalationIntegrationTest.java
autonomous: true
requirements: [LOSS-01, DLQ-01, DLQ-02, REDRIVE-01, REDRIVE-02, INV-01]

must_haves:
  truths:
    - "재발행 send 실패를 주입하면 product 컨슈머가 원본을 ack하지 않아 Kafka가 재전달하고 이벤트 손실이 0이다 (LOSS-01)"
    - "핸들러 3회 실패 또는 NonRetryable 시 cancel_restore_dlq에 leg=STOCK PENDING 행이 cancel_request_id UK로 멱등 적재되고 OperationAlertPort.alert가 호출된다 (DLQ-01, DLQ-02)"
    - "스케줄러가 PENDING 행을 재구동해 성공 시 RESOLVED, 이미 처리분은 processed_cancel_event 멱등으로 no-op이다 (REDRIVE-01)"
    - "attempt_count가 임계(5) 초과 시 status=DEAD로 전이하고 에스컬레이션 알림을 발송한다 (REDRIVE-02)"
    - "payment 모듈 git diff(merge-base)=0이고 재고 release 도메인 로직 불변, 기존 재고 복원 IT가 무회귀 통과한다 (INV-01)"
  artifacts:
    - "V5__create_cancel_restore_dlq.sql (product_db 신규 테이블)"
    - "CancelRestoreDlqRepository 포트 + JpaEntity/JpaRepository/RepositoryImpl"
    - "product OperationAlertPort + LogOperationAlertAdapter"
    - "CancelRestoreRedriveService + CancelRestoreRedriveScheduler (Redisson 락 @Scheduled)"
    - "수렴/손실/DEAD 3개 Testcontainers 통합테스트"
  key_links:
    - "RetryRouter.publishToDlq → CancelRestoreDlqRepository.upsertPending + OperationAlertPort.alert (durable 진실 = 테이블)"
    - "두 컨슈머 catch → route()가 예외 없이 반환할 때만 ack, 예외면 전파(미ack) → 재전달"
    - "CancelRestoreRedriveScheduler → RedriveService → ProcessCancelledStockUseCase.execute (processed_cancel_event 멱등 게이트)"
    - "RetryRouter send 확인: publishToRetry는 .get(5s) 실패 시 rethrow(재전달), publishToDlq는 durable 테이블 기록 후 토픽 send best-effort(미소비 토픽 → rethrow 시 파티션 stall이라 삼킴)"
---

<objective>
product-service 재고 복원 컨슈머 레그를 하드닝해 (1) 재발행 실패 시 이벤트 증발을 없애고, (2) 모든 복원 실패를 durable 테이블 + 알림으로 가시화하며, (3) 일시 장애를 사람 개입 없이 자동 재구동으로 수렴시킨다. Phase 2에서 order 레그에 그대로 복제할 **검증된 참조 구현**을 만든다.

Purpose: 지금은 한 레그만 성공하고 다른 레그가 실패하면 그 실패가 조용히 사라져 불일치(주문=CANCELLED인데 재고 미복원)가 영구화된다. 이 플랜은 조용한 실패를 제거하고 레그별 최종 수렴을 보장한다.
Output: cancel_restore_dlq 테이블(V5) + 리포지토리, product OperationAlertPort + 로그 어댑터, 자동 재구동 스케줄러/서비스, 동기 send 확인 RetryRouter, 수렴/손실/DEAD 통합테스트. payment 취소 코어는 불변(INV-01).

권위 설계: docs/superpowers/specs/2026-08-01-cancel-restore-consistency-design.md (수정1/2/3, 갭①~④, §8 열린 질문).

**§8 열린 질문 확정값 (이 플랜에서 결정)**:
- 재구동 스케줄러 주기: **30s 고정 폴**(fixedDelay). 백오프: PENDING 중 `updated_at <= now-30s` 행만 재구동 후보 → 캡처/시도가 updated_at을 갱신하므로 자연 30~60s 재시도 간격.
- DEAD 전이 attempt_count 임계: **5** (`attempt_count+1 >= 5` → DEAD).
- send 동기 확인 timeout: **5s** (`.get(5, TimeUnit.SECONDS)`).
- cancel_restore_dlq 스키마: order/product 복제(공용 추상화 안 함) — `leg` 컬럼으로 구분(product='STOCK').
- payment.cancelled.retry/.DLQ 토픽 공유 유지(현행) — 레그별 분리는 범위 밖.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@CLAUDE.md
@docs/superpowers/specs/2026-08-01-cancel-restore-consistency-design.md
@.planning/workstreams/cancel-restore/ROADMAP.md
@.planning/workstreams/cancel-restore/REQUIREMENTS.md

# 수정 대상 (product 레그)
@product-service/src/main/java/com/example/product/infrastructure/messaging/RetryRouter.java
@product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockConsumer.java
@product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockRetryConsumer.java
@product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledPayload.java
@product-service/src/main/java/com/example/product/infrastructure/messaging/DlqMessage.java
@product-service/src/main/java/com/example/product/application/service/ProcessCancelledStockService.java
@product-service/src/main/java/com/example/product/application/usecase/ProcessCancelledStockUseCase.java
@product-service/src/main/java/com/example/product/infrastructure/config/KafkaConsumerConfig.java
@product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java

# 미러 대상 패턴 (payment — 수정 금지, 참조만)
@payment-service/src/main/java/com/example/payment/application/interfaces/OperationAlertPort.java
@payment-service/src/main/java/com/example/payment/infrastructure/adapter/LogOperationAlertAdapter.java
@payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java

# 미러 대상 패턴 (product 기존)
@product-service/src/main/java/com/example/product/infrastructure/scheduler/OrphanReservationRecoveryScheduler.java
@product-service/src/main/java/com/example/product/application/service/OrphanReservationRecoveryService.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProcessedCancelEventRepositoryImpl.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProcessedCancelEventJpaEntity.java
@product-service/src/main/resources/db/migration/V2__create_processed_cancel_event.sql
@product-service/src/test/java/com/example/product/integration/CancelRestoreTracerIntegrationTest.java
@product-service/src/main/resources/application.yml
</context>

<tasks>

<task type="tracer">
  <name>Task 1: 수렴 트레이서 — 재고 복원 실패 → durable DLQ(PENDING)+알림 → 재구동 → 복원 → RESOLVED</name>
  <files>
    product-service/src/main/resources/db/migration/V5__create_cancel_restore_dlq.sql (신규),
    product-service/src/main/java/com/example/product/application/interfaces/CancelRestoreDlqRepository.java (신규),
    product-service/src/main/java/com/example/product/application/interfaces/OperationAlertPort.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqJpaEntity.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqJpaRepository.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/persistence/CancelRestoreDlqRepositoryImpl.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/adapter/LogOperationAlertAdapter.java (신규),
    product-service/src/main/java/com/example/product/application/service/CancelRestoreRedriveService.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/scheduler/CancelRestoreRedriveScheduler.java (신규),
    product-service/src/main/java/com/example/product/infrastructure/messaging/RetryRouter.java (수정),
    product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java (수정),
    product-service/src/main/resources/application.yml (수정),
    product-service/src/test/java/com/example/product/integration/CancelRestoreConvergenceIntegrationTest.java (신규)
  </files>
  <read_first>
    V2__create_processed_cancel_event.sql (DDL 관행), ProcessedCancelEventJpaEntity.java + ProcessedCancelEventRepositoryImpl.java (persistence 3종 배선), PersistenceConfig.java (@Bean 팩토리 배선), payment OperationAlertPort.java + LogOperationAlertAdapter.java (알림 포트 형태 — product는 alert(String)만 필요, PG 전용 메서드 복제 금지), OrphanReservationRecoveryScheduler.java + OrphanReservationRecoveryService.java (Redisson tryLock(0,55,SECONDS)+@Scheduled 스케줄러/서비스 분리), CancelRestoreTracerIntegrationTest.java (Testcontainers Kafka+MySQL + publish + awaitility 패턴).
  </read_first>
  <action>
    한 취소 이벤트가 핸들러 실패로 죽어도 durable 테이블에 잡히고 스케줄러 재구동으로 최종 복원되는 end-to-end 슬라이스를 관통시킨다. DLQ-01·DLQ-02·REDRIVE-01 (수정2 + 수정3의 RESOLVED 경로).

    1. **V5 마이그레이션**: `cancel_restore_dlq` 테이블 생성. 컬럼: id(PK, BIGINT AUTO_INCREMENT), cancel_request_id VARCHAR(64) NOT NULL, leg VARCHAR(16) NOT NULL, payload TEXT NOT NULL, retry_count INT NOT NULL DEFAULT 0, attempt_count INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', first_failed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), last_error VARCHAR(512) NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3). UK `uk_cancel_restore_dlq_cancel_request_id (cancel_request_id)`. 인덱스 `idx_cancel_restore_dlq_status_updated (status, updated_at)` (재구동 스캔용). ENGINE=InnoDB DEFAULT CHARSET=utf8mb4. 헤더 주석: 적용 후 불변, 변경은 새 버전으로만 (V2 관행 미러). status 값 도메인은 PENDING/RESOLVED/DEAD 세 가지임을 주석으로 명시.

    2. **CancelRestoreDlqRepository 포트** (application/interfaces): 메서드 — `void upsertPending(String cancelRequestId, String leg, String payload, int retryCount, String lastError)` (cancel_request_id UK로 멱등: 신규는 PENDING INSERT, 기존은 retry_count/last_error/updated_at만 UPDATE하고 **status는 건드리지 않음** → RESOLVED/DEAD 행 부활 방지); `java.util.List<Redrivable> findRedrivable(java.time.Instant updatedBefore, int limit)` (status='PENDING' AND updated_at<=updatedBefore, updated_at ASC); `void markResolved(String cancelRequestId)`; `void bumpAttempt(String cancelRequestId, String lastError)` (attempt_count=attempt_count+1, last_error, updated_at=now); `void markDead(String cancelRequestId, String lastError)`. 중첩 record `Redrivable(String cancelRequestId, String payload, int attemptCount)`.

    3. **persistence 3종**: CancelRestoreDlqJpaEntity(@Entity @Table name=cancel_restore_dlq, ProcessedCancelEventJpaEntity 스타일 — 필드 매핑만, 도메인 로직 없음), CancelRestoreDlqJpaRepository(extends JpaRepository), CancelRestoreDlqRepositoryImpl(포트 구현). upsertPending/markResolved/bumpAttempt/markDead는 JPQL/@Modifying 또는 native `INSERT ... ON DUPLICATE KEY UPDATE`로 구현 — MySQL upsert가 가장 단순. findRedrivable은 파생 쿼리 또는 @Query. (persistence 계층이라 native SQL 허용.)

    4. **PersistenceConfig 배선**: `@Bean CancelRestoreDlqRepository cancelRestoreDlqRepository(CancelRestoreDlqJpaRepository jpa)` → new CancelRestoreDlqRepositoryImpl(jpa). 기존 ProcessedCancelEventRepository 빈 배선과 동형.

    5. **product OperationAlertPort** (application/interfaces): `void alert(String message)` 단일 메서드(payment의 PG 전용 메서드 복제 금지 — YAGNI). **LogOperationAlertAdapter** (infrastructure/adapter, @Component @Slf4j): alert(msg) → `log.error("[ALERT] {}", message)`. payment LogOperationAlertAdapter 미러.

    6. **RetryRouter.publishToDlq 수정**: DLQ 발행 시 **먼저** durable 테이블에 적재 + 알림한다(테이블이 진실). record.value()를 objectMapper로 PaymentCancelledPayload 역직렬화해 cancelRequestId 추출 → `cancelRestoreDlqRepository.upsertPending(cancelRequestId, "STOCK", record.value(), retryCount, DlqMessage.truncate(e.getMessage(),512))` → `operationAlertPort.alert("[cancel-restore][STOCK] DLQ 적재 cancelRequestId=" + cancelRequestId + " err=" + e.getMessage())`. 그 다음 기존 payment.cancelled.DLQ 토픽 발행은 전송로로 **유지하되 best-effort**: `payment.cancelled.DLQ` 토픽에는 **소비자가 0개**(설계 §2 갭②)이고 durable 진실은 방금 쓴 테이블이므로, 토픽 send 실패를 rethrow하면 안 됨(→ 원본 미ack → 무한 재전달 → 파티션 stall, 이미 durable 기록됐는데도). 따라서 upsert+alert **성공 후** DLQ 토픽 send는 실패해도 로그/alert만 남기고 삼킨다(예외 전파 금지) → 컨슈머는 정상 ack. 재구동 스케줄러가 테이블에서 복구. RetryRouter는 현재 @Value 자기배선 @Component이므로 생성자에 CancelRestoreDlqRepository + OperationAlertPort 주입 추가(스프링 빈이라 자동 주입). leg 문자열 "STOCK"은 상수로. ponytail: durable 테이블이 이미 진실이라 미소비 DLQ 토픽 send는 사실상 잉여지만 설계 §수정2가 전송로 유지를 명시하므로 남긴다(제거도 무방 — 남길 경우 best-effort 필수).

    7. **CancelRestoreRedriveService** (application/service, 수정3 RESOLVED 경로만 — DEAD는 Task 3): 스케줄러가 넘긴 (cancelRequestId, attemptCount, Command)로 `processUseCase.execute(command)` 호출 → 성공 시 `markResolved`. 실패(예외) 시 `bumpAttempt(cancelRequestId, err)` (이 Task에서는 임계 판정 없이 bump만 — Task 3에서 DEAD 추가). ProcessCancelledStockUseCase는 processed_cancel_event 멱등 게이트가 있어 이미 처리분은 no-op이므로 재구동이 재고를 과다 복원하지 않음.

    8. **CancelRestoreRedriveScheduler** (infrastructure/scheduler, OrphanReservationRecoveryScheduler 미러): @Scheduled(fixedDelay=30_000) + Redisson RLock tryLock(0,55,SECONDS) 단일 실행. 락 획득 후 `cancelRestoreDlqRepository.findRedrivable(Instant.now().minusSeconds(30), 배치limit(예:100))` → 각 Redrivable의 payload를 objectMapper로 PaymentCancelledPayload 역직렬화 → 컨슈머와 동일 매핑(skuId!=null 필터 → Command.Item)으로 Command 구성 → `redriveService.redriveOne(cancelRequestId, attemptCount, command)`. 파싱(인프라 관심사)은 스케줄러에서, 상태전이(RESOLVED/bumpAttempt)는 서비스에서 — OrphanRecovery 스케줄러/서비스 분리 관행. 락키 프로퍼티 `scheduler.lock.cancel-restore-redrive`.

    9. **application.yml**: `scheduler.lock.cancel-restore-redrive: lock:scheduler:cancel-restore-redrive` 추가. (주기/임계/timeout은 상수 또는 @Value 기본값으로 코드에 두되 §8 확정값 사용: 30s/5/5s.)

    트레이서 IT `CancelRestoreConvergenceIntegrationTest` (`OperationAlertPort`를 `@MockitoBean`으로 주입해 alert 캡처): seed(재고5)→reserve3(available=2)→ 핸들러가 **실패하도록 결함 주입**(예: 존재하지 않는 skuId를 포함하거나, StockService.release가 던지도록 하는 payload/조건 — 실패가 **count≥3 재시도 소진**으로 DLQ 경로에 도달하게)→ payment.cancelled 발행 → awaitility로 (a) cancel_restore_dlq에 leg='STOCK' status='PENDING' 행이 cancel_request_id UK로 1건 적재됨 + (b) **`OperationAlertPort.alert`가 count≥3 DLQ 경로에서도 호출됨**(DLQ-02를 이 경로에서 직접 단언 — Task 3의 NonRetryable 경로와 별개)을 검증 → 그 뒤 결함 제거(정상화)한 상태에서 스케줄러 재구동이 재고를 5로 복원하고 status='RESOLVED'로 전이함을 검증. **손실 0**. 결함 주입 방식은 executor 재량(가장 단순한 관측 가능 방법).
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreConvergenceIntegrationTest'</automated>
  </verify>
  <done>cancel_restore_dlq(V5) 생성됨; 핸들러 실패 → leg=STOCK PENDING 행 멱등 적재 → 스케줄러 재구동 → 재고 복원 + RESOLVED 전이가 Testcontainers e2e로 손실 0 통과. RetryRouter가 DLQ 시 테이블 적재+알림. 재고 release 도메인 로직(StockService) 무변경.</done>
</task>

<task type="auto">
  <name>Task 2: LOSS-01 — 재발행 send 동기 확인 + route 성공 시에만 ack + 무한 재전달 에러핸들러</name>
  <files>
    product-service/src/main/java/com/example/product/infrastructure/messaging/RetryRouter.java (수정),
    product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockConsumer.java (수정),
    product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockRetryConsumer.java (수정),
    product-service/src/main/java/com/example/product/infrastructure/config/KafkaConsumerConfig.java (수정),
    product-service/src/test/java/com/example/product/integration/CancelRestoreLossIntegrationTest.java (신규)
  </files>
  <read_first>
    RetryRouter.java (publishToRetry ~L76 send, publishToDlq ~L84 send — 두 사이트), PaymentCancelledStockConsumer.java + PaymentCancelledStockRetryConsumer.java (catch에서 route() 직후 무조건 ack), KafkaConsumerConfig.java (MANUAL_IMMEDIATE ack, DefaultErrorHandler 미설정 = 기본 FixedBackOff(0,9) → 10회 후 커밋=손실 가능), CancelEventOutboxPublisher.java (send().get(timeout) 동기 확인 패턴).
  </read_first>
  <action>
    갭① 증발 버그 제거(수정1). 재발행 send가 브로커에서 확인될 때까지 원본을 ack하지 않는다.

    1. **두 send 사이트의 실패 정책은 서로 다르다 (핵심)** — durable 사본 유무로 갈린다:
       - **publishToRetry** (`kafkaTemplate.send(retryRecord)`): `.get(5, TimeUnit.SECONDS)`로 브로커 ack 대기, 실패 시 **예외 전파**(rethrow). 이 시점엔 아직 durable 사본이 없다(retry 토픽만이 in-flight 재시도의 전송로) → 잃으면 안 되므로 원본 미ack → 재전달이 정답.
       - **publishToDlq** (`kafkaTemplate.send(dlqRecord)`): Task 1에서 **테이블 upsert+alert를 send 이전에** 이미 수행해 durable 진실이 확보됨. 이 토픽은 **소비자 0개**(설계 §2 갭②)이므로 여기서 rethrow하면 이미 durable 기록됐는데도 원본 미ack → 무한 재전달 → 파티션 stall. 따라서 DLQ 토픽 send는 `.get(5s)`로 확인하되 **실패해도 예외 전파 금지** — 로그/alert만 남기고 삼켜서 컨슈머가 정상 ack하게 한다(재구동 스케줄러가 테이블에서 복구).
       → LOSS-01 무손실 근거: **어느 경로든 손실 0**. retry 경로는 확인 후 실패 시 재전달로, DLQ 경로는 durable 테이블을 토픽 send **이전**에 쓰므로. route() 시그니처는 유지하되 retry 경로에서 예외를 던질 수 있음을 명시(unchecked 전파). publishToDlq는 예외를 던지지 않음.

    2. **두 컨슈머 catch 수정**: 현재 `catch(Exception e){ log; retryRouter.route(record,e); ack.acknowledge(); }`에서 **무조건 ack 제거**. route()가 예외 없이 반환하면 ack; route()가 예외를 던지면 로그 후 **예외를 전파(재던짐)** 하여 컨테이너 에러핸들러가 seek→재전달하게 한다(미ack). DataIntegrityViolationException(중복 UK) 멱등 ack 분기는 유지. 두 컨슈머(main/retry) 동형 적용.

    3. **KafkaConsumerConfig 에러핸들러**: 두 컨테이너 팩토리(kafkaListenerContainerFactory, retryKafkaListenerContainerFactory)에 `DefaultErrorHandler`를 무한(또는 매우 큰) 재시도 백오프로 설정(예: FixedBackOff(2000L, 무제한))하여 전파 예외가 기본 10회 후 커밋되어 손실되지 않고 회복까지 재전달을 반복하게 한다. `factory.setCommonErrorHandler(...)`. 이제 **전파되는 유일 경로는 publishToRetry(재시도 토픽 send 확인 실패)** — DLQ 경로는 durable 테이블 기록 후 best-effort라 던지지 않고, NonRetryableException은 route()가 DLQ 테이블 경로로 성공 반환한다. 즉 재전달 루프는 **retry-토픽 브로커 일시장애**에 한정. ponytail 천장: retry-토픽 브로커 영구 장애 시에만 그 파티션이 재전달 루프로 멈춤(손실보다 나음) — 완전 견고화(consumer-side outbox)는 YAGNI.

    IT `CancelRestoreLossIntegrationTest` — **결정적 회복형 결함 주입**(auto-create-topics ON이면 토픽 미생성으로는 안정적으로 실패 안 함): 스파이/모의 `KafkaTemplate`(또는 `ProducerInterceptor`)을 `@MockitoBean`/래퍼로 주입해 **retry 토픽으로의 첫 N회 send가 예외를 던지고 그 뒤 실제 위임**하도록 한다. 시나리오: 핸들러가 일시 실패(재시도 경로 진입)하도록 유도 → RetryRouter.publishToRetry의 send가 첫 N회 실패(`.get(5s)` 예외 전파) → 컨슈머 **원본 미ack** → 에러핸들러 재전달 검증 → N회 후 send 정상화 → 결국 재고 복원(또는 count≥3 시 cancel_restore_dlq 적재)로 **손실 0** 검증. 핵심 단언: send 실패 구간 동안 원본 오프셋이 커밋되지 않음(재전달 발생) + 회복 후 최종 처리 완료.
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreLossIntegrationTest'</automated>
  </verify>
  <done>publishToRetry는 .get(5s) 실패 시 예외 전파(내구 사본 없음 → 재전달이 유일 안전망); publishToDlq는 durable upsertPending+alert 후 토픽 send를 best-effort(.get(5s) 실패 삼킴, no rethrow — 미소비 토픽 장애로 partition stall 없음); 두 컨슈머가 route() 정상 반환 시에만 ack하고 예외 시 재던짐; 에러핸들러가 재전달을 반복해 커밋-후-손실을 막음. 재발행 실패 주입 시 이벤트 손실 0이 IT로 증명됨.</done>
</task>

<task type="auto">
  <name>Task 3: REDRIVE-02 — attempt_count 임계(5) 초과 시 DEAD + 에스컬레이션 알림 + NonRetryable 즉시 DLQ</name>
  <files>
    product-service/src/main/java/com/example/product/application/service/CancelRestoreRedriveService.java (수정),
    product-service/src/test/java/com/example/product/integration/CancelRestoreDeadEscalationIntegrationTest.java (신규)
  </files>
  <read_first>
    CancelEventOutboxPublisher.java (L117-124: retryCount+1>=maxRetries → markDead + operationAlertPort.alert("...DEAD...") 형태 — 정확히 이 모양 미러), Task 1의 CancelRestoreRedriveService(redriveOne 실패 분기 bumpAttempt), RetryRouter.route()의 isDataError(NonRetryableException) 분기(이미 즉시 DLQ 경로 — 확인만).
  </read_first>
  <action>
    수정3의 DEAD 전이 + 에스컬레이션 완성(REDRIVE-02). Task 1은 실패 시 bumpAttempt만 했으므로 임계 판정을 추가한다.

    1. **CancelRestoreRedriveService.redriveOne 실패 분기 수정**: execute 실패(예외) 시 `attemptCount + 1 >= 5`이면 `cancelRestoreDlqRepository.markDead(cancelRequestId, err)` + `operationAlertPort.alert("[cancel-restore][STOCK] 재구동 영구 실패(DEAD) cancelRequestId=" + cancelRequestId + " attempts=" + (attemptCount+1) + " err=" + err)` (CancelEventOutboxPublisher markDead+alert 미러); 아니면 기존 `bumpAttempt`. 임계 5는 상수 또는 @Value 기본값(`${cancel-restore.redrive.dead-threshold:5}`).

    2. NonRetryable 즉시 DLQ는 RetryRouter.route()의 기존 isDataError 분기로 이미 성립(NonRetryableException → retryCount 무관 publishToDlq → Task 1의 테이블 적재). 여기서는 IT로 커버만 — 신규 코드 불필요.

    IT `CancelRestoreDeadEscalationIntegrationTest` (@MockitoBean으로 OperationAlertPort 주입해 alert 호출 캡처 권장):
    - (a) NonRetryable 실패 → 재시도 없이 cancel_restore_dlq PENDING 적재 + alert 1회.
    - (b) 재구동이 계속 실패하도록 결함 유지 → 스케줄러 반복 재구동으로 attempt_count가 5에 도달 → status='DEAD' 전이 + 에스컬레이션 alert 호출 검증. (테스트 가속을 위해 dead-threshold를 @DynamicPropertySource로 낮추거나 재구동을 직접 반복 호출 — executor 재량.)
    - (c) 이미 처리된 취소(processed_cancel_event 존재)를 재구동하면 release 재호출 없이 no-op으로 RESOLVED 전이(멱등) 검증.
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreDeadEscalationIntegrationTest'</automated>
  </verify>
  <done>재구동 attempt_count가 임계(5) 초과 시 status=DEAD + OperationAlertPort.alert 에스컬레이션; NonRetryable은 즉시 DLQ 테이블 적재; 이미 처리분은 멱등 no-op으로 RESOLVED. IT로 3경로 모두 증명.</done>
</task>

<task type="auto">
  <name>Task 4: INV-01 게이트 — payment diff 0 + 마이그레이션 V5만 추가 + 전체 무회귀</name>
  <files>
    (검증 전용 — 소스 수정 없음. 게이트 실패 시 해당 Task로 회귀)
  </files>
  <action>
    CANCEL-01/INV-01 불변식 게이트. 이 Task는 새 코드를 만들지 않고 앞 3개 Task가 취소 코어·도메인을 침범하지 않았음을 증명한다.

    1. **payment 모듈 diff 0**: merge-base 대비 payment-service 변경이 없어야 한다. `git diff --stat $(git merge-base HEAD main)...HEAD -- payment-service` 및 워킹트리 `git status --porcelain payment-service` 모두 비어 있음.
    2. **마이그레이션 디렉터리**: product db/migration에 V5만 신규 추가되고 V1~V4는 무변경(내용/파일명 불변). `git diff $(git merge-base HEAD main)...HEAD --name-status -- product-service/src/main/resources/db/migration/`가 `A ...V5__create_cancel_restore_dlq.sql` 단일 추가만 보여야 함.
    3. **도메인 불변**: StockService(재고 release/reserve 원자 전이) 및 stock 도메인 엔티티 변경 없음 — 신뢰성/메시징 계층 + 신규 dlq 테이블/스케줄러만 추가. `git diff --stat`로 StockService 미변경 확인.
    4. **전체 무회귀**: `:product-service:test` 전체 통과(기존 재고 복원/멱등/동시성/orphan IT 포함).
  </action>
  <verify>
    <automated>bash -c 'set -e; BASE=$(git merge-base HEAD main); test -z "$(git status --porcelain payment-service)" || { echo PAYMENT_DIRTY; exit 1; }; test -z "$(git diff $BASE...HEAD --name-only -- payment-service)" || { echo PAYMENT_DIFF; exit 1; }; MIG=$(git diff $BASE...HEAD --name-status -- product-service/src/main/resources/db/migration/ | grep -vE "^A[[:space:]]+.*V5__create_cancel_restore_dlq\.sql$" || true); test -z "$MIG" || { echo "MIGRATION_GATE_FAIL: $MIG"; exit 1; }; test -z "$(git diff $BASE...HEAD --name-only -- product-service/src/main/java/com/example/product/application/service/StockService.java)" || { echo STOCKSERVICE_CHANGED; exit 1; }'; find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test</automated>
  </verify>
  <done>payment-service diff(merge-base+워킹트리)=0; product 마이그레이션은 V5 단일 신규 추가만; StockService 미변경; :product-service:test 전체 무회귀 통과. 게이트 실패 시 원인 Task로 회귀.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Kafka broker → product consumer | payment.cancelled/retry 이벤트 payload는 신뢰 경계를 넘어 들어옴(악의는 아니나 malformed/중복 가능) |
| consumer/scheduler → product_db | 재고 release + dlq 상태전이 쓰기 |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-01-01 | Repudiation | 복원 실패 silent 소실 | high | mitigate | durable cancel_restore_dlq 적재 + OperationAlertPort.alert (DLQ-01/02) — 모든 실패 가시화 |
| T-01-02 | Denial of Service | 무한 재전달로 파티션 블록 | medium | mitigate | NonRetryable은 DLQ 테이블 경로로 격리; **DLQ 토픽 send는 durable 기록 후 best-effort(rethrow 안 함)라 미소비 토픽 장애로 stall 없음**; 재전달 루프는 retry-토픽 일시장애에 한정(회복 시 해소). ponytail 천장 명시 |
| T-01-03 | Tampering | 중복/재전달 이벤트로 재고 과다 복원 | high | mitigate | processed_cancel_event 멱등 게이트 + release 원자 조건부 전이(이중 안전) — 재구동 no-op |
| T-01-04 | Elevation of Privilege | 신규 dlq 테이블/스케줄러가 취소 코어·도메인 침범 | high | mitigate | INV-01 게이트(Task 4): payment diff 0 + StockService 불변 + V5 단일 추가 |

신규 의존성 없음(spring-kafka·redisson·testcontainers·awaitility 기존) → 패키지 적법성 게이트 불필요.
</threat_model>

<verification>
- Task별 IT 통과: CancelRestoreConvergenceIntegrationTest(수렴 e2e, 손실0), CancelRestoreLossIntegrationTest(send실패→미ack→재전달), CancelRestoreDeadEscalationIntegrationTest(DEAD+에스컬레이션·NonRetryable즉시DLQ·멱등no-op).
- INV-01 게이트: payment diff 0 + 마이그레이션 V5 단일 + StockService 불변 + `:product-service:test` 전체 무회귀.
- 클라우드 싱크 위험: 모든 테스트 실행 전 `find . -path '*/build/*' -name '* [0-9].sql' -delete`로 Flyway 중복 버전 방지.
</verification>

<success_criteria>
1. 재발행 send 실패 주입 시 원본 미ack → Kafka 재전달 → 이벤트 손실 0 (LOSS-01).
2. 핸들러 3회 실패/NonRetryable → cancel_restore_dlq leg=STOCK PENDING 멱등 적재 + alert (DLQ-01, DLQ-02).
3. 스케줄러 재구동 성공 → RESOLVED, 이미 처리분 멱등 no-op, attempt_count 5초과 → DEAD + 에스컬레이션 (REDRIVE-01, REDRIVE-02).
4. product 레그만 DLQ로 빠진 뒤 재구동으로 재고 최종 복원되는 수렴 e2e가 손실 0으로 통과 (Testcontainers).
5. payment 모듈 git diff(merge-base)=0, StockService 불변, 기존 재고 복원 IT 무회귀 (INV-01).
</success_criteria>

<output>
Create `.planning/workstreams/cancel-restore/phases/01-product-leg-hardening/01-01-SUMMARY.md` when done
</output>