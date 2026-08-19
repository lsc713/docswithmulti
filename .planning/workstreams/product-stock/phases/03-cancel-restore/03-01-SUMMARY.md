---
phase: 03-cancel-restore
plan: 01
subsystem: infra
tags: [kafka, testcontainers, spring-kafka, stock-restore, event-driven, product-service, payment-service]

# Dependency graph
requires:
  - phase: 02-payment-stock-reserve
    provides: "PaymentItem.skuId/quantity (V16), StockService.reserve/release 원자 상태전이, product reserve/release 엔드포인트"
  - phase: 01-product-stock-core
    provides: "StockService.release(releaseIfReserved 조건부 전이), product_stock/stock_reservation 스키마"
provides:
  - "payment.cancelled payload cancelledItems[]에 skuId/quantity (RST-01, 하위호환 필드추가)"
  - "product-service 최초 Kafka consumer(groupId=product-service) — 취소 이벤트로 SKU 재고 복원 (RST-02 happy path)"
  - "reserve→cancel-event→release 재고 왕복 e2e (Testcontainers MySQL+Kafka)"
affects: [03-02-idempotency-partial-cancel, 03-03-retry-dlq, product-service, payment-service]

# Tech tracking
tech-stack:
  added: ["org.springframework.kafka:spring-kafka (product-service 최초)", "org.testcontainers:kafka:1.19.7 (product-service 최초 Kafka 통합테스트)"]
  patterns: ["order-service Kafka consumer 스택 복제(KafkaConsumerConfig MANUAL_IMMEDIATE + @KafkaListener)", "취소 코어 불변 게이트: buildPayload 필드추가만 + 기존 통합테스트 무회귀 그린으로 로직 불변 증명"]

key-files:
  created:
    - product-service/src/main/java/com/example/product/infrastructure/config/KafkaConsumerConfig.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledPayload.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockConsumer.java
    - product-service/src/main/java/com/example/product/application/usecase/ProcessCancelledStockUseCase.java
    - product-service/src/main/java/com/example/product/application/service/ProcessCancelledStockService.java
    - product-service/src/test/java/com/example/product/integration/CancelRestoreTracerIntegrationTest.java
    - payment-service/src/test/java/com/example/payment/application/service/CancelTxWriterPayloadTest.java
  modified:
    - payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java
    - product-service/build.gradle
    - product-service/src/main/resources/application.yml

key-decisions:
  - "buildPayload 변경을 cancelledItems JSON에 skuId/quantity 두 필드추가로만 한정 — TX1/2/3·findAllByPaymentIdForUpdate·publish·상태전이 무변경 (취소 코어 불변)"
  - "skuId null(하위호환 데이터)은 payload에 JSON null로 직렬화하고 consumer에서 release 대상 제외"
  - "product consumer는 order 스택 복제, retry factory는 제외(retry/DLQ는 03-03) — main consumer factory만"
  - "ProcessCancelledStockService는 Phase 1 StockService.release를 재사용해 위임(재구현 없음)"
  - "DataIntegrityViolationException 멱등 ack만 미리 포함, cancelRequestId 멱등 테이블은 03-02"

patterns-established:
  - "취소 코어 불변 증명: 코어 파일 변경 시 git diff를 buildPayload 내부로 한정 + CancelFlow/CancelRace/ProcessingRecovery/OutboxPublisher 무회귀 그린을 행동 증거로 사용"
  - "product-service Kafka 통합테스트: Testcontainers MySQL+KafkaContainer + raw KafkaProducer 발행 + Awaitility 폴링"

requirements-completed: [RST-01, RST-02]

coverage:
  - id: D1
    description: "payment.cancelled payload cancelledItems[]에 skuId/quantity가 실린다 (하위호환: null skuId는 JSON null)"
    requirement: RST-01
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/application/service/CancelTxWriterPayloadTest.java#payloadCarriesSkuIdAndQuantity"
        status: pass
    human_judgment: false
  - id: D2
    description: "취소 코어 TX/멱등/스케줄러/outbox 로직 불변 (payload 필드추가가 취소 플로우에 영향 없음)"
    requirement: RST-01
    verification:
      - kind: integration
        ref: "CancelFlowIntegrationTest + CancelRaceIdempotencyIT + ProcessingRecoveryConcurrencyIT + CancelEventOutboxPublisherIT (무회귀 그린)"
        status: pass
      - kind: other
        ref: "git diff CancelTxWriter.java — buildPayload 내부에만 국한 (core-invariance gate)"
        status: pass
    human_judgment: false
  - id: D3
    description: "공유 payment.cancelled 계약 무회귀 — order-service consumer가 증강 payload에도 그린"
    requirement: RST-01
    verification:
      - kind: integration
        ref: ":order-service:test (BUILD SUCCESSFUL)"
        status: pass
    human_judgment: false
  - id: D4
    description: "product consumer가 payment.cancelled로 취소된 SKU 재고를 release로 복원; reserve→cancel 왕복이 available_qty를 원복"
    requirement: RST-02
    verification:
      - kind: e2e
        ref: "product-service/src/test/java/com/example/product/integration/CancelRestoreTracerIntegrationTest.java#reserveThenCancelEventRestoresStock"
        status: pass
    human_judgment: false

# Metrics
duration: 15min
completed: 2026-07-31
status: complete
---

# Phase 03 Plan 01: 취소 복원 tracer (payload skuId/quantity + product Kafka consumer) Summary

**payment.cancelled payload에 skuId/quantity를 실어(취소 코어 불변) product-service 최초 Kafka consumer가 이를 구독해 취소된 SKU 재고를 StockService.release로 복원하는 최소 e2e를 Testcontainers MySQL+Kafka로 그린화**

## Performance

- **Duration:** ~15 min
- **Started:** 2026-07-31T11:15:00Z
- **Completed:** 2026-07-31T11:28:00Z
- **Tasks:** 3
- **Files modified:** 10 (7 created, 3 modified)

## Accomplishments
- RST-01: `CancelTxWriter.buildPayload`의 cancelledItems JSON에 skuId(nullable)/quantity 필드추가 — 취소 코어(TX1/2/3·멱등·스케줄러·outbox) 무변경, 기존 취소 통합/멱등/복구/outbox 테스트 무회귀 그린으로 로직 불변 증명
- RST-02: product-service 최초 Kafka consumer(groupId=product-service) 구축(order 스택 복제) — payment.cancelled 구독 → 취소된 SKU만큼 StockService.release로 재고 복원(Phase 1 원자 상태전이 재사용)
- reserve→cancel-event→release 재고 왕복 e2e(Testcontainers MySQL+실 Kafka)로 available_qty 원복 검증
- 공유 payment.cancelled 계약 무회귀: order consumer가 증강 payload(신규 필드)를 무시하고 정상 동작

## Task Commits

Each task was committed atomically:

1. **Task 1: payment.cancelled payload에 skuId/quantity 추가 (RST-01, tracer)** - `e432a1c` (feat)
2. **Task 2: CancelTxWriter payload 단언 테스트 (RST-01 무회귀 게이트)** - `39e11d9` (test)
3. **Task 3: product 최초 Kafka consumer + release-on-cancel e2e (RST-02)** - `e33f452` (feat)

_Plan metadata(SUMMARY/STATE)는 실행환경 제약(`.planning/` 커밋 금지)으로 커밋하지 않음._

## Files Created/Modified
- `payment-service/.../CancelTxWriter.java` - buildPayload cancelledItems에 skuId/quantity 필드추가(유일 변경)
- `payment-service/.../CancelTxWriterPayloadTest.java` - INLINE 모드 + MockitoBean KafkaTemplate payload 캡처 단언(신규)
- `product-service/build.gradle` - spring-kafka + testcontainers:kafka 의존성 추가
- `product-service/.../application.yml` - spring.kafka(bootstrap-servers, group-id=product-service) + kafka.topic.payment-cancelled
- `product-service/.../KafkaConsumerConfig.java` - order 복제(consumerFactory + MANUAL_IMMEDIATE 컨테이너 팩토리)
- `product-service/.../PaymentCancelledPayload.java` - 이벤트 payload record(CancelledItem에 skuId/quantity)
- `product-service/.../PaymentCancelledStockConsumer.java` - @KafkaListener → skuId!=null만 release 위임 + UK충돌 멱등 ack
- `product-service/.../ProcessCancelledStockUseCase.java` - 유스케이스 인터페이스 + Command(Item(skuId,qty))
- `product-service/.../ProcessCancelledStockService.java` - @Service, StockService.release 위임
- `product-service/.../CancelRestoreTracerIntegrationTest.java` - MySQL+Kafka e2e(reserve→cancel event→재고 원복)

## Decisions Made
- buildPayload 변경을 skuId/quantity 두 필드추가로만 한정(취소 코어 불변) — String.format의 %s에 `null` 리터럴로 skuId null 하위호환 직렬화
- product consumer는 happy path만: 멱등 테이블/부분취소 하드닝은 03-02, retry/DLQ는 03-03으로 분리. DataIntegrityViolationException 멱등 ack만 미리 포함(order 패턴, 03-02 UK 대비)
- KafkaConsumerConfig는 order의 retry factory를 제외한 main consumer factory만 복제(consume-only 범위)
- 재고 복원은 재구현 없이 Phase 1 StockService.release(releaseIfReserved 조건부 전이)에 위임 — over-release 불가·멱등 성질 그대로 재사용

## Deviations from Plan

None - plan executed exactly as written. buildPayload는 지정된 필드추가로만 한정됐고, product consumer 스택은 order 복제 지침대로 구축됨. 취소 코어는 한 줄도 변경되지 않음(git diff 확인).

**Total deviations:** 0
**Impact on plan:** 없음 — 계획대로 실행, 취소 코어 불변 유지.

## Issues Encountered
None. Testcontainers MySQL+Kafka 모두 정상 기동, consumer가 auto-offset-reset=earliest로 발행 이벤트를 수신해 재고 복원 확인.

## User Setup Required
None - no external service configuration required (로컬 Testcontainers로 검증).

## Next Phase Readiness
- RST-02 happy path 슬라이스 그린 — 03-02(멱등 테이블 processed_cancel_event + 부분취소 인지)와 03-03(retry/DLQ 라우팅)의 기반 마련
- product consumer는 현재 멱등 테이블 없이 StockService.release의 조건부 전이 멱등성에만 의존 — 중복 이벤트 시 release는 no-op이나 명시적 cancelRequestId 멱등 게이트는 03-02에서 완성 필요
- orphan 예약 복구 스케줄러(spec §8)는 후속 plan 범위

## Verification Results
- `./gradlew :payment-service:test --tests CancelFlowIntegrationTest --tests CancelRaceIdempotencyIT --tests ProcessingRecoveryConcurrencyIT --tests CancelEventOutboxPublisherIT --tests CancelTxWriterPayloadTest` → BUILD SUCCESSFUL
- `./gradlew :order-service:test` → BUILD SUCCESSFUL (공유 계약 무회귀)
- `./gradlew :product-service:test` → BUILD SUCCESSFUL (CancelRestoreTracerIntegrationTest + 기존 StockTracerIntegrationTest 무회귀)
- `git diff` CancelTxWriter → buildPayload 내부 2줄만(core-invariance gate 통과)

## Self-Check: PASSED
- 생성 파일 7개 전부 디스크 존재 확인
- 커밋 3개(e432a1c, 39e11d9, e33f452) git log 존재 확인
- 모든 task verify + 플랜 verification 그린

---
*Phase: 03-cancel-restore*
*Completed: 2026-07-31*
