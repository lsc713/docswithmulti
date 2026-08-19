---
phase: 02-payment-product
plan: 01
subsystem: payments
tags: [spring-boot, resilience4j, circuit-breaker, resttemplate, flyway, testcontainers, mockrestserviceserver, fail-closed]

# Dependency graph
requires:
  - phase: 01-product-foundation
    provides: product-service POST /v1/stock/reserve|release (원자 재고 예약/해제, 멱등)
provides:
  - "payment-service 생성 경로에 product 동기 재고 예약 배선 (paymentKey→reserve→persist)"
  - "ProductStockPort + ProductStockHttpClient (전용 CircuitBreaker, fail-closed)"
  - "PaymentCreateTxWriter (@Transactional persist 전담 빈, CancelTxWriter 동형)"
  - "sku_id/quantity 관통 배선 (요청~커맨드~엔티티~V16 DDL)"
affects: [02-02, phase-3-cancel-stock-release, compensation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "비-TX 오케스트레이터 + 별도 @Transactional TxWriter 빈 (자기호출 프록시 우회, CancelTxWriter 동형)"
    - "TX-밖-HTTP 예약(fail-closed): reserve 성공 후에만 payment TX 커밋"
    - "HTTP 클라이언트 catch 계층 복제: 409→도메인예외, 5xx/CB OPEN→서비스예외, Error→전파, Throwable→래핑"
    - "도메인 엔티티 하위호환 팩토리 오버로드로 blast radius 최소화 (취소 코어 테스트 무변경)"

key-files:
  created:
    - payment-service/src/main/resources/db/migration/V16__add_sku_and_quantity_to_payment_item.sql
    - payment-service/src/main/java/com/example/payment/application/interfaces/ProductStockPort.java
    - payment-service/src/main/java/com/example/payment/infrastructure/http/ProductStockHttpClient.java
    - payment-service/src/main/java/com/example/payment/infrastructure/exception/ProductServiceException.java
    - payment-service/src/main/java/com/example/payment/infrastructure/exception/StockInsufficientException.java
    - payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
    - payment-service/src/test/java/com/example/payment/infrastructure/http/ProductStockHttpClientTest.java
    - payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
  modified:
    - payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
    - payment-service/src/main/java/com/example/payment/domain/entity/PaymentItem.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentItemJpaEntity.java
    - payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentItemRequest.java
    - payment-service/src/main/java/com/example/payment/application/service/CreatePaymentCommand.java
    - payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
    - payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java
    - payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java
    - payment-service/src/main/resources/application.yml
    - payment-service/src/test/resources/application.yml

key-decisions:
  - "reserve/release 클라이언트는 RiskManagementHttpClient(RestTemplate+Resilience4j+Port/Adapter)를 복제 — 신규 의존성 0"
  - "재고 부족(409)·product 장애·CB OPEN 모두 fail-closed(결제 거부) — 오버셀 방지가 가용성보다 우선"
  - "PaymentItem of/reconstruct 하위호환 오버로드(6/8인자 보존)로 취소 코어 테스트 ~15곳 무변경"
  - "CB OPEN 증명은 minimumNumberOfCalls 때문에 스텁 실패로 비현실적 → transitionToOpenState() 직접 전이"

patterns-established:
  - "생성 경로: paymentKey(TX 밖) → productStockPort.reserve(HTTP) → PaymentCreateTxWriter.persist(@Transactional)"
  - "통합테스트: Testcontainers MySQL + MockRestServiceServer(공유 RestTemplate 빈)로 HTTP 홉 스텁"

requirements-completed: [RSV-01, RSV-02]

coverage:
  - id: D1
    description: "결제 생성이 product reserve(TX 앞) 성공 후에만 커밋되고, 재고 부족(409)이면 거부·payment 미생성"
    requirement: "RSV-01"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java#reserveSuccess_persists"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java#reserveConflict_rejected"
        status: pass
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java#shouldNotPersistWhenReserveFails"
        status: pass
    human_judgment: false
  - id: D2
    description: "product 장애(CircuitBreaker OPEN)면 503으로 결제 거부되고 payment 행이 남지 않는다 (fail-closed)"
    requirement: "RSV-01"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java#circuitBreakerOpen_rejected"
        status: pass
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/http/ProductStockHttpClientTest.java#reserve_circuitBreakerOpen_throwsProductServiceException"
        status: pass
    human_judgment: false
  - id: D3
    description: "sku_id/quantity가 요청·payment_item(V16)에 영속되고 신규 생성 시 필수 검증(@NotNull/@Positive)"
    requirement: "RSV-02"
    verification:
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java#reserveSuccess_persists (jdbc sku_id/quantity assert)"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/infrastructure/persistence/PaymentItemRepositoryImplTest.java"
        status: pass
    human_judgment: false
  - id: D4
    description: "취소 코어(CancelPaymentService/CancelTxWriter/CancelDomainService/scheduler/messaging/outbox) 무변경"
    verification:
      - kind: other
        ref: "git diff --name-only $(git merge-base HEAD origin/main)..HEAD | grep -E 'Cancel(PaymentService|TxWriter|DomainService|EventOutboxPublisher)|/scheduler/|/messaging/|cancel_event_outbox' → empty"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CancelFlowIntegrationTest.java (full suite green)"
        status: pass
    human_judgment: false

# Metrics
duration: 22 min
completed: 2026-07-30
status: complete
---

# Phase 02 Plan 01: 결제 생성 경로 product 재고 예약 배선 Summary

**결제 생성을 paymentKey→product.reserve(HTTP, TX 밖)→payment/payment_item persist(@Transactional)로 재구조화하고, 재고 부족·product 장애·CB OPEN을 fail-closed로 거부(오버셀 방지)하며 sku_id/quantity를 요청~V16 DDL까지 관통 배선**

## Performance

- **Duration:** ~22 min
- **Completed:** 2026-07-30
- **Tasks:** 3 (1 auto, 1 tdd, 1 tracer+tdd)
- **Files modified/created:** 20 (신규 8 · 수정 12)

## Accomplishments
- 생성 경로 아키텍처를 tracer로 end-to-end 검증: reserve 200→저장, 409→409 거부·미저장, CB OPEN→503 거부·미저장이 실제 MySQL(Testcontainers)로 그린
- ProductStockPort/HttpClient 신규(RiskManagementHttpClient 복제, 전용 productServiceCircuitBreaker) — 신규 의존성 0
- CreatePaymentService를 비-TX 오케스트레이터로 전환 + PaymentCreateTxWriter(@Transactional persist 전담 빈)로 TX 경계 분리
- sku_id(BIGINT NULL)/quantity(INT NOT NULL DEFAULT 1) V16 + 요청 필수검증(@NotNull/@Positive) 관통 배선
- 취소 코어 완전 무변경(core-unchanged gate CLEAN) — PaymentItem 하위호환 오버로드로 취소 코어 테스트 무변경

## Task Commits

1. **Task 1: V16 + sku_id/quantity 배선** - `033b451` (feat)
2. **Task 2: ProductStockHttpClient (RED→GREEN)** - `c3f612f` (test) → `3e53eb4` (feat)
3. **Task 3: CreatePaymentService 재구조화 + e2e** - `4cb6343` (feat, tracer)

_플랜 메타데이터(.planning/)는 실행 제약(`.planning/ 커밋 금지`)에 따라 커밋하지 않음._

## Files Created/Modified
- `V16__add_sku_and_quantity_to_payment_item.sql` - payment_item에 sku_id/quantity 추가 (Phase 3 취소 payload sku 계약)
- `ProductStockPort.java` / `ProductStockHttpClient.java` - product 동기 reserve/release, 전용 CircuitBreaker, fail-closed
- `ProductServiceException.java`(503) / `StockInsufficientException.java`(409) - fail-closed 거부 예외
- `PaymentCreateTxWriter.java` - @Transactional persist 전담 빈(CancelTxWriter 동형)
- `CreatePaymentService.java` - 비-TX 오케스트레이터(paymentKey→reserve→persist)
- `PaymentItem.java` / `PaymentItemJpaEntity.java` - skuId/quantity 필드 + 하위호환 오버로드
- `CreatePaymentItemRequest.java` / `CreatePaymentCommand.java` / `PaymentController.java` - sku_id/quantity 관통
- `ErrorCode.java` - STOCK_INSUFFICIENT(409), PRODUCT_SERVICE_UNAVAILABLE(503)
- `ResilienceConfig.java` - productServiceCircuitBreaker 빈
- `application.yml`(main/test) - external.product-service.url(8084)

## Decisions Made
- reserve/release 클라이언트는 RiskManagementHttpClient 패턴 복제(신규 의존성 없음, ponytail). VALIDATION seed의 "WireMock"은 내장 MockRestServiceServer로 대체.
- fail-closed 일관 적용: 재고 부족·장애·CB OPEN 모두 결제 거부 — 오버셀(재무 리스크)이 가용성보다 우선.
- CB OPEN 통합 증명은 DEFAULT_CONFIG minimumNumberOfCalls=100 때문에 스텁 실패로는 flaky → productServiceCircuitBreaker 빈에 transitionToOpenState() 직접 전이 후 검증, transitionToClosedState()로 원복.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] 스트레이 중복 소스파일 7개(`* 2.java`) 삭제**
- **Found during:** Task 1 (verify 실행 시 전체 컴파일)
- **Issue:** payment-service/src에 클라우드 싱크 충돌 산물로 보이는 untracked 중복 파일 7개(`AuthenticatedUser 2.java` 등, "public class ... should be declared in a file named ..." 컴파일 에러)가 모든 컴파일을 차단
- **Fix:** 각 파일의 정본(추적됨)이 존재함을 확인 후 `rm`으로 제거(git clean 미사용, untracked만)
- **Files modified:** (삭제) payment-service/src/**/`* 2.java` 7개
- **Verification:** `./gradlew :payment-service:test` 컴파일 통과, `find src -name "* 2.java"` = 0
- **Committed in:** N/A (untracked 파일 삭제 — 커밋 대상 아님)

**2. [Rule 3 - Blocking] 통합테스트용 test application.yml에 external.* 더미 URL 추가**
- **Found during:** Task 3 (CreatePaymentReserveIntegrationTest + 기존 통합테스트 회귀)
- **Issue:** 신규 ProductStockHttpClient가 모든 @SpringBootTest 컨텍스트에서 실 빈으로 생성되며 `${external.product-service.url}`을 요구 → test/resources/application.yml에 external 블록이 전무해 PlaceholderResolutionException(기존 취소 통합테스트까지 전부 파손). 기존 테스트는 risk/pg 포트를 @MockitoBean으로 대체해 @Value가 평가되지 않아 지금까지 무증상이었음.
- **Fix:** test/resources/application.yml에 external.{product-service,risk-management,pg}.url 더미 URL 추가(공용 fixture, HTTP는 mock/MockRestServiceServer로 가로챔)
- **Files modified:** payment-service/src/test/resources/application.yml
- **Verification:** `./gradlew :payment-service:test` 전체 그린(취소 통합테스트 포함)
- **Committed in:** `4cb6343` (Task 3 commit)

**3. [Rule 3 - Blocking] 통합테스트에 cancel.publish.mode=INLINE 고정 + ObjectMapper 로컬 인스턴스화**
- **Found during:** Task 3 (CreatePaymentReserveIntegrationTest 컨텍스트 로드)
- **Issue:** (a) 기본 OUTBOX 모드에서 CancelEventOutboxPublisher @PostConstruct가 mocked RedissonClient.getTopic()의 null에 addListener → NPE. (b) 이 슬라이스에 ObjectMapper 빈 부재.
- **Fix:** (a) CancelFlowIntegrationTest 동일 관행으로 `@SpringBootTest(properties="cancel.publish.mode=INLINE")` (생성 경로는 cancel 이벤트 미발행, 취소 코어 무변경). (b) 테스트 로컬 `new ObjectMapper()`.
- **Files modified:** payment-service/src/test/java/.../CreatePaymentReserveIntegrationTest.java (테스트 전용)
- **Verification:** 통합테스트 3개 시나리오 그린
- **Committed in:** `4cb6343` (Task 3 commit)

---

**Total deviations:** 3 auto-fixed (3 blocking). **Impact on plan:** 전부 테스트 환경 배선/사전존재 오염 해소로 correctness에 필수. 프로덕션 로직·취소 코어 스코프 크립 없음. 산출물/계약은 플랜과 동일.

## Issues Encountered
- 사전 존재 `* 2.java` 중복 파일이 빌드를 차단 → untracked 확인 후 제거(위 Deviation 1).
- 신규 실 빈(ProductStockHttpClient)의 @Value가 기존 통합테스트 컨텍스트를 파손 → 공용 test yaml에 external 더미 URL 추가로 회귀 해소(위 Deviation 2).

## Known Stubs
None — 모든 배선이 실 데이터 경로(reserve HTTP → jdbc 왕복)로 통합테스트 증명됨.

## User Setup Required
None - product-service(8084)는 로컬/컨테이너에서 기동되며 external.product-service.url로 배선됨. 신규 외부 서비스 설정 없음.

## Next Phase Readiness
- Plan 02: persist 실패 후 release 보상·재시도 스케줄러 확장 준비 완료(본 플랜은 reserve 실패 거부만 다룸).
- 취소 코어 불변(core-unchanged gate CLEAN) — Phase 3 취소 시 sku 재고 해제 계약(payment_item.sku_id/quantity)이 영속됨.

## Self-Check: PASSED
- 신규 파일 8개 디스크 존재 확인.
- 커밋 033b451 / c3f612f / 3e53eb4 / 4cb6343 존재 확인.
- `./gradlew :payment-service:test` 전체 그린. core-unchanged gate CLEAN.

---
*Phase: 02-payment-product*
*Completed: 2026-07-30*
