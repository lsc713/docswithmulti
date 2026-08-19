---
phase: 02-link
plan: 01
subsystem: payment-service
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [PLINK-01, PLINK-02, PLINK-03, TRUST-01, CANCEL-01]
tags: [order-link, order-verify, fail-closed, trust-header, flyway, cancel-core-gate]
files_modified:
  - payment-service/src/main/resources/db/migration/V18__add_order_id_to_payment.sql
  - payment-service/src/main/java/com/example/payment/application/interfaces/OrderVerifyPort.java
  - payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java
  - payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyUnavailableException.java
  - payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyRejectedException.java
  - payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java
  - payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java
  - payment-service/src/main/java/com/example/payment/domain/entity/Payment.java
  - payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaEntity.java
  - payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
  - payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
  - payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
  - payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentRequest.java
  - payment-service/src/main/resources/application.yml
  - docs/error-catalog.md
  - payment-service/src/test/java/com/example/payment/infrastructure/http/OrderVerifyHttpClientTest.java
  - payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java
  - payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
  - payment-service/src/test/java/com/example/payment/presentation/controller/PaymentControllerCreateIT.java

must_haves:
  truths:
    - "결제 생성이 order-service items:verify를 흐름 최전방(product reserve 전)에 호출하고 X-User-Id(요청자)를 헤더로 포워딩한다 (PLINK-03, TRUST-01)."
    - "order-service 장애/타임아웃/비200 시 결제가 거부되고 재고 예약·persist가 발생하지 않는다 (PLINK-01 fail-closed, PLINK-03)."
    - "성공한 결제의 payment 행에 검증된 order_id(NOT NULL)가 저장된다 (PLINK-02)."
    - "결제 생성이 body userId 없이 X-User-Id 신뢰헤더로 소유자를 정한다 — CreatePaymentRequest.userId 제거 (TRUST-01)."
    - "취소 코어 파일 변경 0, 기존 취소 통합테스트 전부 green (CANCEL-01)."
  artifacts:
    - payment-service/src/main/java/com/example/payment/application/interfaces/OrderVerifyPort.java
    - payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java
    - payment-service/src/main/resources/db/migration/V18__add_order_id_to_payment.sql
  key_links:
    - "CreatePaymentService → OrderVerifyPort.verify(userId, orderItemIds) BEFORE ProductStockPort.reserve (부작용 전 검증)."
    - "PaymentCreateTxWriter.persist(..., orderId) → payment.order_id NOT NULL 저장."
    - "PaymentController @RequestHeader X-User-Id → CreatePaymentCommand.userId → OrderVerifyHttpClient X-User-Id 포워딩."
---

<objective>
결제 생성 경로가 상류 주문을 fail-closed로 검증하고 payment.order_id(NOT NULL)로 강하게 링크하며, 결제 신원을 X-User-Id 신뢰헤더에서 취득하도록 payment-service를 변경한다. 취소 코어(멱등·TX1/2/3·스케줄러 3종·outbox)는 한 줄도 바꾸지 않는다.

Purpose: 지금 payment는 orderItemId를 검증 없이 신뢰하고 payment↔order 링크가 없다. Phase 1이 세운 order-service `POST /v1/orders/items:verify`(내부)를 소비해, 결제를 상류 주문에 신뢰 가능하게 연결한다.
Output: OrderVerifyPort/OrderVerifyHttpClient(신규, fail-closed), Flyway V18(payment.order_id), CreatePaymentService 검증 최전방 삽입, PaymentController X-User-Id 전환. CANCEL-01 게이트 통과 증명.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/workstreams/order-link/phases/02-link/02-CONTEXT.md
@.planning/workstreams/order-link/REQUIREMENTS.md
@.planning/workstreams/order-link/phases/01-api/01-SUMMARY.md
@docs/superpowers/specs/2026-07-31-order-link-design.md
@CLAUDE.md

# 미러링 대상 (fail-closed HTTP 포트/클라이언트 + catch 계층 규율)
@payment-service/src/main/java/com/example/payment/application/interfaces/ProductStockPort.java
@payment-service/src/main/java/com/example/payment/infrastructure/http/ProductStockHttpClient.java
@payment-service/src/test/java/com/example/payment/infrastructure/http/ProductStockHttpClientTest.java

# 결제 생성 현 흐름 + persist + 컨트롤러 + DTO + 스키마 매핑
@payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
@payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java
@payment-service/src/main/java/com/example/payment/application/service/CreatePaymentCommand.java
@payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java
@payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentRequest.java
@payment-service/src/main/java/com/example/payment/domain/entity/Payment.java
@payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaEntity.java
@payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java
@payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java
@payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: 결제 생성 해피패스 end-to-end 슬라이스 — verify→reserve→persist(order_id) + X-User-Id</name>
  <files>
    payment-service/src/main/resources/db/migration/V18__add_order_id_to_payment.sql,
    payment-service/src/main/java/com/example/payment/application/interfaces/OrderVerifyPort.java,
    payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java,
    payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java,
    payment-service/src/main/resources/application.yml,
    payment-service/src/main/java/com/example/payment/domain/entity/Payment.java,
    payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaEntity.java,
    payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java,
    payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java,
    payment-service/src/main/java/com/example/payment/presentation/controller/PaymentController.java,
    payment-service/src/main/java/com/example/payment/presentation/dto/CreatePaymentRequest.java,
    payment-service/src/test/java/com/example/payment/integration/CreatePaymentReserveIntegrationTest.java
  </files>
  <behavior>
    - 통합(e2e): POST /v1/payments 에 X-User-Id 헤더 + body(merchantId, pgType, cancelPeriodDays, items[orderItemId..]) → order verify(mock 200 {orderId}) → reserve(mock 200) → persist → 200 응답, payment 행의 order_id 컬럼 = 검증된 orderId.
    - 통합: order verify 호출이 reserve 호출보다 먼저 발생하고, verify 요청에 X-User-Id 헤더(요청자)와 {orderItemIds} 바디가 전달된다.
    - 통합: order_id 는 NOT NULL 컬럼으로 저장되고 앱이 V18 적용 후 정상 기동한다.
  </behavior>
  <action>
    Phase 2의 가장 얇은 세로 슬라이스를 모든 레이어에 관통시켜 배선한다(해피패스 1경로만; 실패 분기는 Task 2). 미러 원본은 ProductStockPort/ProductStockHttpClient.

    (1) Flyway V18 (PLINK-02) — 신규 파일 V18__add_order_id_to_payment.sql. `ALTER TABLE payment ADD COLUMN order_id BIGINT NOT NULL, ADD INDEX idx_payment_order_id (order_id)`. 적용된 V1~V17은 절대 수정 금지.
    결정(직행 NOT NULL, spec §7 option a): 검증 표면은 Testcontainers(테스트마다 신규 MySQL, V1~V18 empty 테이블 적용)이고 dev/포트폴리오 DB에 보존할 프로덕션 결제 행이 없다(과거 결제는 order 미상 → backfill 불가). 따라서 nullable→backfill→NOT NULL 2단계 불필요. 로컬 dev MySQL에 기존 payment 행이 있으면 dev 스키마를 재생성(프로덕션 데이터 아님) — SUMMARY에 명시.

    (2) 도메인 Payment order_id — CANCEL-01 안전 규율(핵심): 기존 7-인자 `Payment.of(...)`와 `ofPending(...)`, createdAt 오버로드는 시그니처를 바꾸지 말고 내부에서 orderId=0L 센티널로 위임한다(order 미링크 = 레거시/취소 테스트 seed 행; 취소 코어는 order_id를 읽지 않음). 신규 8-인자 create 팩토리 `Payment.of(paymentKey, merchantId, userId, pgType, totalAmount, currency, cancelPeriodDays, orderId)`를 추가해 생성 경로에서만 실제 orderId를 싣는다. `reconstruct(...)`에는 orderId 파라미터를 추가한다(유일 호출자는 PaymentJpaEntity.toDomain — 테스트 호출자 없음). `long orderId` 필드 + getOrderId() 추가. 이 규율로 CancelFlowIntegrationTest/CancelRaceIdempotencyIT/ProcessingRecoveryConcurrencyIT/PaymentFixture 등 기존 7-인자 of() 호출부는 무편집으로 컴파일되고 seed INSERT는 order_id=0 으로 NOT NULL을 만족한다.

    (3) PaymentJpaEntity — order_id 컬럼 매핑(nullable=false) + @Index(name="idx_payment_order_id", columnList="order_id"). from(Payment)은 payment.getOrderId()를 싣고, toDomain()은 orderId를 reconstruct에 전달.

    (4) OrderVerifyPort(application/interfaces) — 미러 ProductStockPort. 시그니처 `long verify(long userId, java.util.List<Long> orderItemIds)` (검증된 orderId 반환). Javadoc에 fail-closed·X-User-Id 포워딩 계약 명시.

    (5) OrderVerifyHttpClient(infrastructure/http) — 미러 ProductStockHttpClient 구조. 공유 RestTemplate 빈 주입(HttpClientConfig), 신규 CircuitBreaker `orderServiceCircuitBreaker`(ResilienceConfig에 registry.circuitBreaker("order-service") 빈 추가), baseUrl `@Value("${external.order-service.url}")`. POST `${baseUrl}/v1/orders/items:verify`, 헤더 `X-User-Id=userId`, 바디 `{orderItemIds:[...]}`. 이 Task에서는 200 응답 바디에서 orderId 파싱해 반환하는 해피패스만 완성(비200/4xx 분기는 Task 2에서 catch 계층 완성). application.yml `external.order-service.url` 추가(로컬 http://localhost:8081, 컨테이너 대안 주석 — product-service 항목과 동형).

    (6) CreatePaymentService 흐름 최전방 검증(PLINK-03) — 순서를 `order 검증 → paymentKey → reserve → persist(orderId)`로 재배선. 생성자에 OrderVerifyPort 주입. command.items()에서 orderItemIds 추출 → `long orderId = orderVerifyPort.verify(command.userId(), orderItemIds)` 를 paymentKey 발급·reserve보다 먼저 호출. reserve/persist 로직·보상(compensateReserve) 경로는 기존 유지. persist 호출에 orderId 전달.

    (7) PaymentCreateTxWriter.persist(command, paymentKey, totalAmount, orderId) — 신규 8-인자 Payment.of로 orderId를 실어 저장.

    (8) PaymentController X-User-Id(TRUST-01) — create 핸들러가 `@RequestHeader("X-User-Id") long userId`를 읽어 CreatePaymentCommand.userId로 전달. CreatePaymentRequest에서 userId 필드(@Positive long userId) 제거. merchant_id는 body 유지(스코프 밖).

    (9) 통합 테스트 확장 — CreatePaymentReserveIntegrationTest에 order verify를 MockRestServiceServer로 스텁(공유 RestTemplate, 기존 reserve 스텁 관행과 동일)하고, X-User-Id 헤더로 POST → 200 → JdbcTemplate로 payment.order_id = 검증 orderId 확인. verify 요청이 X-User-Id·{orderItemIds}로 나갔는지(MockRestRequestMatchers) 검증. cancel.publish.mode=INLINE 고정 관행 유지(취소 코어 무변경).
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :payment-service:test --tests '*CreatePaymentReserveIntegrationTest'</automated>
  </verify>
  <done>X-User-Id 헤더로 POST /v1/payments 하면 order verify(200 {orderId})가 reserve보다 먼저 X-User-Id·{orderItemIds}로 호출되고, 결제 성공 시 payment 행에 order_id(NOT NULL)가 저장된다. CreatePaymentRequest.userId 제거됨. V18 적용 후 앱 정상 기동. 커밋 완료.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: OrderVerify fail-closed + verify 4xx 매핑 + PLINK-03 부작용 순서 증명</name>
  <files>
    payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyUnavailableException.java,
    payment-service/src/main/java/com/example/payment/infrastructure/exception/OrderVerifyRejectedException.java,
    payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java,
    payment-service/src/main/java/com/example/payment/infrastructure/http/OrderVerifyHttpClient.java,
    docs/error-catalog.md,
    payment-service/src/test/java/com/example/payment/infrastructure/http/OrderVerifyHttpClientTest.java,
    payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java
  </files>
  <behavior>
    - verify 200 {orderId} → orderId 반환(예외 없음).
    - verify 404/409/403 → 각각 매핑된 BusinessException(ORDER_ITEM_NOT_FOUND 404 / ORDER_ITEMS_MULTIPLE_ORDERS 409 / ORDER_OWNERSHIP_MISMATCH 403) → 결제 거부.
    - verify 5xx / 비-2xx / 타임아웃 / CircuitBreaker OPEN → OrderVerifyUnavailableException(503) → 결제 거부(fail-closed).
    - 서비스: verify가 예외를 던지면 ProductStockPort.reserve 미호출 + PaymentCreateTxWriter.persist 미호출(부작용 전 차단).
  </behavior>
  <action>
    미러 ProductStockHttpClient의 catch 계층 규율과 ProductStockHttpClientTest 케이스 구조를 그대로 따른다.

    (1) ErrorCode 추가(common/exception/ErrorCode) — `ORDER_VERIFY_UNAVAILABLE`(503, fail-closed), `ORDER_ITEM_NOT_FOUND`(404), `ORDER_ITEMS_MULTIPLE_ORDERS`(409), `ORDER_OWNERSHIP_MISMATCH`(403). error-catalog.md의 코드·메시지와 일치(spec §6 표).

    (2) 예외 클래스 — OrderVerifyUnavailableException(BusinessException, ORDER_VERIFY_UNAVAILABLE) = order-service 장애 fail-closed. OrderVerifyRejectedException(BusinessException, ErrorCode를 생성자로 받아 404/409/403 중 하나로 매핑) = order-service 검증 거부. ProductServiceException/StockInsufficientException 패턴과 동형(BusinessException 상속 → GlobalExceptionHandler가 ErrorCode.httpStatus로 응답).

    (3) OrderVerifyHttpClient catch 계층 완성 — circuitBreaker.executeCheckedSupplier 래핑. 명시적 2xx 가드(비200 → OrderVerifyUnavailableException). HttpClientErrorException는 상태코드별로 404/409/403 → OrderVerifyRejectedException(해당 ErrorCode)로, 그 외 4xx는 요청 결함 → 장애로 취급해 거부. Error는 재던짐. 그 외 Throwable(5xx/타임아웃/CallNotPermittedException=CB OPEN) → OrderVerifyUnavailableException(fail-closed). order-service 4xx 응답 바디의 code(ORDER_ITEM_NOT_FOUND 등)로도 매핑 가능하나, 1차 판정은 HTTP status로 한다.

    (4) OrderVerifyHttpClientTest — ProductStockHttpClientTest 미러: 성공(200 orderId 반환), 404→Rejected(404), 409→Rejected(409), 403→Rejected(403), 5xx/비2xx→Unavailable, CB OPEN(2건 실패 후 3번째 차단·restTemplate 미실행)→Unavailable. RestTemplate mock + 테스트용 slidingWindowSize=2 CircuitBreaker.

    (5) CreatePaymentServiceTest — verify 실패 시 부작용 전 차단 증명(PLINK-03/PLINK-01): OrderVerifyPort mock이 OrderVerifyUnavailableException(및 별도 케이스 Rejected) 던지면 create()가 예외 전파하고 `verifyNoInteractions(productStockPort)` + `verifyNoInteractions(paymentCreateTxWriter)`. 기존 해피패스 테스트는 OrderVerifyPort mock이 orderId 반환하도록 setUp 갱신(생성자에 OrderVerifyPort 추가됨) 후 verify가 reserve보다 먼저 호출되는 순서(InOrder)도 확인.
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :payment-service:test --tests '*OrderVerifyHttpClientTest' --tests '*CreatePaymentServiceTest'</automated>
  </verify>
  <done>OrderVerifyHttpClient가 5xx/타임아웃/CB OPEN에 fail-closed(503)로 거부하고 404/409/403을 매핑 거부한다. verify 실패 시 reserve·persist가 호출되지 않음이 테스트로 증명된다. error-catalog.md에 order-verify 코드 4종 반영. 커밋 완료.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: TRUST-01 회귀 + CANCEL-01 게이트 + 전 모듈 무회귀</name>
  <files>
    payment-service/src/test/java/com/example/payment/presentation/controller/PaymentControllerCreateIT.java
  </files>
  <behavior>
    - 컨트롤러: 소유자는 X-User-Id 헤더에서만 온다 — CreatePaymentCommand.userId == 헤더값(ArgumentCaptor). body에 userId 필드가 있어도(미지 필드) 무시된다.
    - X-User-Id 누락 요청은 결제 생성이 진행되지 않는다(헤더 바인딩 실패 → 400/누락 처리).
    - CANCEL-01: merge-base..HEAD payment-service diff에 취소 코어 파일이 하나도 없다.
    - 기존 취소 통합테스트(CancelFlowIntegrationTest, CancelRaceIdempotencyIT, ProcessingRecoveryConcurrencyIT) 전부 green.
  </behavior>
  <action>
    (1) TRUST-01 회귀 테스트 — Phase 1 OrderControllerIT 패턴 미러(standalone MockMvc, mock usecase, ArgumentCaptor). PaymentControllerCreateIT: (a) X-User-Id 헤더 값이 CreatePaymentCommand.userId로 전달되는지 캡처 검증, (b) 요청 body에 userId를 넣어도(미지 필드) 무시되고 헤더 소유자가 이긴다는 회귀, (c) X-User-Id 누락 시 생성 유스케이스 미호출. createPaymentUseCase는 mock — 이 IT는 헤더 매핑만 검증(검증/재고 로직 아님).

    (2) CANCEL-01 게이트 — 취소 코어 파일이 diff에 없음을 자동 검증(verify의 automated 명령이 게이트). 취소 코어 = CancelPaymentService, CancelTxWriter, CancelAuthorizationService, CancelHistoryRecorder, CancelPaymentCommand, CompensationRetryService, PendingRecoveryService, ProcessingRecoveryService, CancelEventOutbox*, 멱등(cancel_request/dedup) 관련 (02-CONTEXT.md Locked Decision 6 목록). 이 목록은 결과 확인용 참조일 뿐 — 실제 게이트는 verify 명령의 diff 검사다. 만약 diff에 나타나면 STOP하고 원인 제거(설계상 이 Phase는 생성 경로만 건드리므로 나타나면 안 됨).

    (3) 전 모듈 무회귀 — payment-service 전체 테스트 green(생성 경로 변경이 취소 통합테스트를 깨지 않음: order_id NOT NULL은 7-인자 Payment.of 센티널 0으로 흡수, Task 1 규율).
  </action>
  <verify>
    <automated>cd /Users/juho/Documents/docswithmulti-order && ./gradlew :payment-service:test --tests '*PaymentControllerCreateIT' --tests '*CancelFlowIntegrationTest' --tests '*CancelRaceIdempotencyIT' --tests '*ProcessingRecoveryConcurrencyIT' && CANCEL_HITS=$(git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/ | grep -E 'CancelPaymentService|CancelTxWriter|CancelAuthorizationService|CancelHistoryRecorder|CancelPaymentCommand|CompensationRetryService|PendingRecoveryService|ProcessingRecoveryService|CancelEventOutbox' | grep -v '/test/' | wc -l | tr -d ' ') && echo "cancel-core source hits: $CANCEL_HITS" && [ "$CANCEL_HITS" = "0" ]</automated>
  </verify>
  <done>X-User-Id 헤더가 결제 소유자를 정하고 body userId는 무시됨이 회귀 테스트로 증명된다. CANCEL-01 게이트 통과(취소 코어 source diff 0) + 기존 취소 통합테스트 green. 커밋 완료.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| gateway → payment (:8080) | 게이트웨이가 검증한 X-User-Id를 payment가 신뢰헤더로 수신(재검증 없음). 내부망 격리(NetworkPolicy) 전제. |
| payment → order (:8081) items:verify | payment가 검증된 X-User-Id를 포워딩; order-service는 내부 호출의 X-User-Id를 신뢰. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-02-01 | Spoofing | X-User-Id (client-forged) | high | transfer | 게이트웨이 JwtTrustHeaderFilter가 클라 X-User-Id strip 후 JWT에서 재주입(Phase 1 GW). payment는 헤더만 신뢰 — 배포 게이트 NetworkPolicy(payment/order ingress 게이트웨이 파드 한정, spec §5)로 우회 차단. Phase 2 코드 밖(배포 게이트)이라 transfer. |
| T-02-02 | Elevation of Privilege | order 소유 검증 우회 | high | mitigate | OrderVerifyHttpClient가 X-User-Id를 order verify에 포워딩 → order-service가 order.user_id==X-User-Id 판정(403 ORDER_OWNERSHIP_MISMATCH). 검증을 흐름 최전방(reserve 전)에 배치해 미검증 결제 차단. |
| T-02-03 | Denial of Service | order-service 장애/타임아웃 | medium | mitigate | fail-closed(Task 2): 5xx/타임아웃/CB OPEN → OrderVerifyUnavailableException(503) → 결제 거부. product reserve와 동일 CircuitBreaker 규율(order 장애가 무한 대기/오검증 유발 안 함). |
| T-02-04 | Tampering | order_id 링크 무결성 | medium | mitigate | order_id는 order-service가 반환한 검증값만 저장(클라 입력 아님) + payment.order_id NOT NULL. 미검증 경로로 payment 생성 불가(검증 최전방). |
| T-02-SC | Tampering | npm/pip/cargo installs | n/a | accept | 신규 외부 의존성 없음(기존 RestTemplate/Resilience4j 재사용) → 패키지 legitimacy 게이트 불필요. |
</threat_model>

<verification>
- Task 1: `./gradlew :payment-service:test --tests '*CreatePaymentReserveIntegrationTest'` — verify→reserve 순서 + order_id 저장 e2e green.
- Task 2: `./gradlew :payment-service:test --tests '*OrderVerifyHttpClientTest' --tests '*CreatePaymentServiceTest'` — fail-closed·4xx 매핑·부작용 전 차단 green.
- Task 3: `./gradlew :payment-service:test --tests '*PaymentControllerCreateIT' --tests '*Cancel*' ...` + CANCEL-01 diff 게이트(cancel-core source hits == 0).
- 전체: `./gradlew :payment-service:test` — 무회귀 green.
- 스키마: `./gradlew :payment-service:flywayInfo` 또는 Testcontainers 부팅으로 V18 적용·기동 확인.
</verification>

<success_criteria>
Phase 2 ROADMAP Success Criteria 매핑:
1. CreatePaymentService가 최전방 verify(X-User-Id 포워딩), 실패 시 reserve·persist 미발생 → Task 1(순서)+Task 2(실패 차단). (PLINK-03)
2. OrderVerifyHttpClient fail-closed(장애/타임아웃/비200 → 거부) → Task 2. (PLINK-01)
3. 성공 시 payment.order_id(NOT NULL) 저장 → Task 1 통합테스트. (PLINK-02/PLINK-03)
4. body userId 없이 X-User-Id로 소유자 결정(CreatePaymentRequest.userId 제거) → Task 1(변경)+Task 3(회귀). (TRUST-01)
5. Flyway V18 order_id NOT NULL + 인덱스, 앱 정상 기동(직행 NOT NULL, 데이터 유무 확정) → Task 1. (PLINK-02)
6. 취소 코어 변경 0 — merge-base diff + 취소 통합테스트 green → Task 3 게이트. (CANCEL-01)
</success_criteria>

<output>
Create `.planning/workstreams/order-link/phases/02-link/02-01-SUMMARY.md` when done
</output>