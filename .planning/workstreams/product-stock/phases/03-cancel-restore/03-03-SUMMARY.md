---
phase: 03-cancel-restore
plan: 03
subsystem: infra
tags: [kafka, retry, dlq, spring-kafka, product-service, event-driven, resilience, at-least-once]

# Dependency graph
requires:
  - phase: 03-cancel-restore (03-01)
    provides: "product-service Kafka consumer(PaymentCancelledStockConsumer, KafkaConsumerConfig main factory), ProcessCancelledStockUseCase, PaymentCancelledPayload"
provides:
  - "product consumer 실패 라우팅: 일시적 오류 → payment.cancelled.retry, 데이터오류/3회초과 → payment.cancelled.DLQ (RST-02 운영 견고성)"
  - "product Kafka producer 인프라(KafkaProducerConfig: 멱등 프로듀서 + KafkaTemplate)"
  - "PaymentCancelledStockRetryConsumer — retry 토픽 재소비(retry-group), 재실패 시 재라우팅"
  - "수동 offset 커밋으로 at-least-once 보장(성공/retry/DLQ 이동 후에만 ack)"
affects: [product-service, operations-runbook, dlq-monitoring]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "order-service retry/DLQ 스택 복제(RetryRouter + DlqMessage + NonRetryableException 마커 + retry consumer/factory)"
    - "product RetryRouter는 @Component + 생성자 @Value 토픽 자기배선(order의 @Bean 배선 대신 별도 config 불필요)"

key-files:
  created:
    - product-service/src/main/java/com/example/product/application/exception/NonRetryableException.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/DlqMessage.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/RetryRouter.java
    - product-service/src/main/java/com/example/product/infrastructure/config/KafkaProducerConfig.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockRetryConsumer.java
    - product-service/src/test/java/com/example/product/messaging/RetryRouterTest.java
  modified:
    - product-service/src/main/java/com/example/product/infrastructure/config/KafkaConsumerConfig.java
    - product-service/src/main/java/com/example/product/infrastructure/messaging/PaymentCancelledStockConsumer.java
    - product-service/src/main/resources/application.yml

key-decisions:
  - "RetryRouter를 @Component + 생성자 @Value(retry/dlq 토픽 주입)로 자기배선 — order의 KafkaProducerConfig @Bean 배선 대신 별도 bean config 불필요(03-03 계획 지침)"
  - "MAX_RETRY_COUNT=3, retryDelay 1/5/10분, isDataError=NonRetryableException — order RetryRouter 로직 그대로 복제"
  - "retry consumer factory는 read_uncommitted + MAX_POLL_RECORDS=50 (비트랜잭션 프로듀서 발행 의도 명시, order 동형)"
  - "RetryRouterTest는 브로커 불필요 — KafkaTemplate mock + 테스트 로컬 DataError(NonRetryableException 구현)로 DLQ 라우팅 검증"

patterns-established:
  - "consumer 실패 라우팅 계약: try{처리→ack} catch(DataIntegrityViolationException){멱등 ack} catch(Exception){route→ack} — ack는 항상 마지막(유실 없음)"
  - "retry/DLQ 단위테스트: KafkaTemplate mock + ArgumentCaptor<ProducerRecord>로 토픽·retry-count 헤더 단언(order 관행 정렬)"

requirements-completed: [RST-02]

coverage:
  - id: D1
    description: "일시적 오류 + retry-count<3 → payment.cancelled.retry 발행(retry-count 증가 헤더, original-topic/first-failed-at/last-error 세팅)"
    requirement: RST-02
    verification:
      - kind: unit
        ref: "product-service/src/test/java/com/example/product/messaging/RetryRouterTest.java#should_publish_to_retry_when_transient_error_and_retry_count_below_3"
        status: pass
    human_judgment: false
  - id: D2
    description: "retry-count>=3 (일시적 오류) → payment.cancelled.DLQ 격리(무한 재처리 방지, T-03-05)"
    requirement: RST-02
    verification:
      - kind: unit
        ref: "product-service/src/test/java/com/example/product/messaging/RetryRouterTest.java#should_publish_to_dlq_when_transient_error_and_retry_count_reaches_3"
        status: pass
    human_judgment: false
  - id: D3
    description: "데이터 오류(NonRetryableException) → 즉시 DLQ 격리(재시도 없이)"
    requirement: RST-02
    verification:
      - kind: unit
        ref: "product-service/src/test/java/com/example/product/messaging/RetryRouterTest.java#should_publish_to_dlq_immediately_when_data_error"
        status: pass
    human_judgment: false
  - id: D4
    description: "consumer 실패 시 RetryRouter.route 후 offset ack — retry consumer + main consumer 무회귀(retry-group container 정상 기동), 전체 product 통합테스트 그린"
    requirement: RST-02
    verification:
      - kind: integration
        ref: ":product-service:test — 전체 스위트 BUILD SUCCESSFUL (retry container factory 포함 Spring 컨텍스트 기동 + 기존 통합테스트 무회귀)"
        status: pass
    human_judgment: false

# Metrics
duration: 9min
completed: 2026-07-31
status: complete
---

# Phase 03 Plan 03: product 재고 복원 consumer retry/DLQ 라우팅 (RST-02) Summary

**product-service consumer 실패를 order-service 패턴으로 라우팅 — 일시적 오류는 payment.cancelled.retry(최대 3회, 1/5/10분 지수 지연)로 재시도, 데이터오류·3회초과는 payment.cancelled.DLQ로 격리하고, 수동 offset 커밋(성공/retry/DLQ 이동 후 ack)으로 at-least-once 메시지 유실 방지**

## Performance

- **Duration:** ~9 min
- **Started:** 2026-07-31T03:09:31Z
- **Completed:** 2026-07-31T03:18:31Z
- **Tasks:** 2
- **Files modified:** 9 (6 created, 3 modified)

## Accomplishments
- RST-02: product retry/DLQ 인프라 구축(order 복제) — RetryRouter(route: 일시적 오류<3 → retry, 데이터오류/3회초과 → DLQ), DlqMessage(원본+실패메타), NonRetryableException 마커
- product 최초 Kafka producer 인프라(KafkaProducerConfig: acks=all + enable.idempotence 멱등 프로듀서 + KafkaTemplate)
- retry consumer 스택: retryConsumerFactory(retry-group, read_uncommitted, MAX_POLL=50) + retryKafkaListenerContainerFactory(MANUAL_IMMEDIATE) + PaymentCancelledStockRetryConsumer
- PaymentCancelledStockConsumer에 catch(Exception)→route→ack 추가 — DataIntegrityViolationException 멱등 ack와 공존, ack는 항상 마지막(유실 없음)
- RetryRouterTest 3케이스(retry 발행/3회 DLQ/데이터오류 DLQ) 그린 + 전체 product 통합테스트 무회귀

## Task Commits

Each task was committed atomically:

1. **Task 1: RetryRouter + producer/retry consumer config (RST-02)** - `a55a26b` (feat)
2. **Task 2: consumer 오류 라우팅 + retry consumer + 라우팅 테스트 (RST-02)** - `dc133a3` (feat)

_Plan metadata(SUMMARY/STATE)는 실행환경 제약(`.planning/` gitignore·커밋 금지)으로 커밋하지 않음._

## Files Created/Modified
- `product-service/.../application/exception/NonRetryableException.java` - 데이터 오류 마커 인터페이스(order 복제)
- `product-service/.../messaging/DlqMessage.java` - DLQ 격리 메시지 record(원본+실패메타, truncate)
- `product-service/.../messaging/RetryRouter.java` - @Component, @Value 토픽 자기배선. route/publishToRetry/publishToDlq(order 로직 복제)
- `product-service/.../config/KafkaProducerConfig.java` - 멱등 ProducerFactory + KafkaTemplate<String,String>
- `product-service/.../messaging/PaymentCancelledStockRetryConsumer.java` - retry 토픽 구독(retry-group, retryKafkaListenerContainerFactory), 동일 useCase + 재라우팅
- `product-service/.../messaging/PaymentCancelledStockConsumer.java` - (수정) RetryRouter 주입 + catch(Exception)→route→ack
- `product-service/.../config/KafkaConsumerConfig.java` - (수정) retry-group-id @Value + retryConsumerFactory + retryKafkaListenerContainerFactory
- `product-service/.../application.yml` - (수정) retry/dlq 토픽, retry-group-id, producer(acks/serializer) 설정
- `product-service/src/test/.../messaging/RetryRouterTest.java` - KafkaTemplate mock 라우팅 단위테스트 3케이스

## Decisions Made
- RetryRouter를 @Component + 생성자 @Value(토픽) 자기배선으로 구성 — order는 KafkaProducerConfig에서 @Bean으로 배선하지만 product는 별도 bean config 없이 자기완결(03-03 계획 지침). KafkaProducerConfig는 ProducerFactory/KafkaTemplate만 노출.
- MAX_RETRY_COUNT=3, retryDelay 1/5/10분, NonRetryableException 즉시 DLQ — order RetryRouter 로직 무변경 복제로 두 서비스 라우팅 동형성 확보.
- retry consumer factory는 read_uncommitted + MAX_POLL_RECORDS=50 (order 동형; retry 토픽은 비트랜잭션 프로듀서 발행이라 read_committed와 실질 동일하나 의도 명시).
- RetryRouterTest에서 데이터오류는 테스트 로컬 `DataError extends RuntimeException implements NonRetryableException`로 트리거 — product 도메인에 아직 NonRetryable 예외가 없어 마커 계약만 검증(브로커 불필요).

## Deviations from Plan

None - plan executed exactly as written.

- Task1은 지정된 6개 산출물(NonRetryableException/DlqMessage/RetryRouter/KafkaProducerConfig/KafkaConsumerConfig retry factory/application.yml)을 order 복제 지침대로 생성.
- Task2는 consumer catch(Exception)→route→ack + retry consumer + 라우팅 테스트 3케이스를 계획대로 구현.
- 취소 코어·payment·order 파일은 한 줄도 변경하지 않음(product-service 한정).

**Total deviations:** 0
**Impact on plan:** 없음 — 계획대로 실행. retry/DLQ 라우팅은 order와 동형, 멱등(03-02 processed_cancel_event)과 호환(재시도 중복도 조건부 전이·UK 멱등으로 no-op).

## Issues Encountered
None. RetryRouterTest 3케이스 그린. 전체 `:product-service:test` 스위트가 retry container factory 포함 Spring 컨텍스트 기동(로그에 `product-service-retry` consumer group join 확인) + 기존 통합/멱등/tracer 테스트 무회귀로 BUILD SUCCESSFUL.

## User Setup Required
None - no external service configuration required. retry/DLQ 토픽은 브로커 auto-create(또는 운영 시 kafka-design 규약대로 사전 생성). DLQ 모니터링/알림은 운영 런북 범위.

## Next Phase Readiness
- RST-02 운영 견고성(retry/DLQ) 완성 — consumer 실패가 재고 미복원으로 유실되지 않고 at-least-once 재시도/격리됨.
- DLQ 소비/재처리 도구, DLQ 적재 알림(OperationAlertPort 유형)은 후속 운영 범위(현재 격리까지 보장).
- 브로커 통합 레벨 retry→재소비→DLQ e2e(Testcontainers)는 단위 라우팅+컨텍스트 기동으로 대체 검증 — 필요 시 후속 통합테스트로 승격 가능.

## Verification Results
- `./gradlew :product-service:compileJava :product-service:compileTestJava` → BUILD SUCCESSFUL (Task1)
- `./gradlew :product-service:test --tests "com.example.product.messaging.RetryRouterTest"` → BUILD SUCCESSFUL, 3/3 그린 (Task2)
- `./gradlew :product-service:test` (전체) → BUILD SUCCESSFUL — RetryRouterTest + 기존 통합테스트(tracer/idempotency/concurrency/release) 무회귀, retry container factory 정상 기동

## Self-Check: PASSED
- 생성 파일 6개 전부 디스크 존재 확인(NonRetryableException/DlqMessage/RetryRouter/KafkaProducerConfig/PaymentCancelledStockRetryConsumer/RetryRouterTest)
- 커밋 2개(a55a26b, dc133a3) HEAD 라인 존재 확인
- 모든 task verify + 플랜 verification(전체 스위트) 그린

---
*Phase: 03-cancel-restore*
*Completed: 2026-07-31*
