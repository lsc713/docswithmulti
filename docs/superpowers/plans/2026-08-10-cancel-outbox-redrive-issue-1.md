# Cancel Outbox Redrive Issue 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 내부 운영자가 DEAD 취소 outbox 하나를 조회해 결제·주문·재고의 실제 적용 상태와 재발행 필요 여부를 읽기 전용으로 판정할 수 있게 한다.

**Architecture:** payment-service가 검사 유스케이스를 소유하고 원본 outbox·취소 요청·결제의 안전 조건을 먼저 검사한다. 안전 조건을 통과한 경우에만 `OrderCancelStatusPort`와 `StockRestoreStatusPort`를 호출하며, order-service와 product-service는 각각 자신의 DB만 조회하는 내부 `:inspect` API를 제공한다. 모든 외부 경계는 포트로 분리해 향후 Operations Reconciler가 동일 계약을 재사용할 수 있게 한다.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring MVC, Spring Data JPA, RestTemplate, Resilience4j, MySQL 8, JUnit 5, AssertJ, Mockito, Testcontainers

## Global Constraints

- 원본 `cancel_event_outbox` 행과 downstream 도메인 데이터는 검사 과정에서 변경하지 않는다.
- `DEAD`, `CancelStatus.COMPLETED`, `PaymentStatus.CANCELLED|PARTIAL_CANCELLED`를 모두 만족한 뒤에만 downstream을 호출한다.
- downstream 의미 상태는 `APPLIED`, `NOT_APPLIED`, `INCONSISTENT`, `UNKNOWN` 네 값으로 고정한다.
- timeout, 5xx, CircuitBreaker open은 예외를 외부에 노출하지 않고 해당 레그 `UNKNOWN`, 전체 `UNKNOWN`으로 fail-closed 처리한다.
- 내부 운영자 호출은 `X-User-Role=ADMIN` 및 비어 있지 않은 `X-User-Id`를 요구한다.
- 모듈 간 DB 직접 접근과 기존 Flyway 파일 수정은 금지한다.
- 각 production 변경 전 실패하는 테스트를 실행하고 기대한 이유로 실패했음을 확인한다.

---

### Task 1: order-service가 주문 취소 적용 상태를 읽기 전용으로 판정한다

**Files:**
- Create: `order-service/src/main/java/com/example/order/application/model/CancelRestoreLegStatus.java`
- Create: `order-service/src/main/java/com/example/order/application/usecase/InspectCancelRestoreUseCase.java`
- Create: `order-service/src/main/java/com/example/order/application/service/InspectCancelRestoreService.java`
- Create: `order-service/src/main/java/com/example/order/presentation/dto/InspectCancelRestoreRequest.java`
- Create: `order-service/src/main/java/com/example/order/presentation/dto/InspectCancelRestoreResponse.java`
- Create: `order-service/src/main/java/com/example/order/presentation/controller/InternalCancelRestoreController.java`
- Modify: `order-service/src/main/java/com/example/order/application/interfaces/OrderItemRepository.java`
- Modify: `order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java`
- Test: `order-service/src/test/java/com/example/order/application/service/InspectCancelRestoreServiceTest.java`
- Test: `order-service/src/test/java/com/example/order/presentation/controller/InternalCancelRestoreControllerTest.java`

**Interfaces:**
- Consumes: `ProcessedCancelEventRepository.existsByCancelRequestId(String)`, `OrderItemRepository.findAllByIdIn(List<Long>)`, `OrderRepository.findById(long)`.
- Produces: `InspectCancelRestoreUseCase.inspect(Command)` returning `Result(status, evidence)`, and `POST /internal/cancel-restores/{cancelRequestId}:inspect` with `{orderItemIds:[...]}`.

- [ ] **Step 1: Write failing application tests for the four observable states**

```java
@Test
void processedMarkerAndCancelledTargetsAreApplied() {
    when(processed.existsByCancelRequestId("27")).thenReturn(true);
    when(items.findAllByIdIn(List.of(10L, 11L))).thenReturn(List.of(
        item(10L, 100L, OrderItemStatus.CANCELLED),
        item(11L, 100L, OrderItemStatus.CANCELLED)));
    when(orders.findById(100L)).thenReturn(Optional.of(order(100L, OrderStatus.CANCELLED)));

    var result = service.inspect(new Command("27", List.of(10L, 11L)));

    assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.APPLIED);
    assertThat(result.evidence()).isEmpty();
}

@Test
void noMarkerAndActiveTargetsAreNotApplied() {
    when(processed.existsByCancelRequestId("27")).thenReturn(false);
    when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
        item(10L, 100L, OrderItemStatus.ACTIVE)));

    assertThat(service.inspect(new Command("27", List.of(10L))).status())
        .isEqualTo(CancelRestoreLegStatus.NOT_APPLIED);
}

@Test
void markerDomainMismatchIsInconsistentWithItemEvidence() {
    when(processed.existsByCancelRequestId("27")).thenReturn(true);
    when(items.findAllByIdIn(List.of(10L))).thenReturn(List.of(
        item(10L, 100L, OrderItemStatus.ACTIVE)));

    var result = service.inspect(new Command("27", List.of(10L)));

    assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
    assertThat(result.evidence()).containsExactly(new Evidence(10L, "ACTIVE"));
}

@Test
void missingTargetIsInconsistentInsteadOfApplied() {
    when(processed.existsByCancelRequestId("27")).thenReturn(true);
    when(items.findAllByIdIn(List.of(10L, 99L))).thenReturn(List.of(
        item(10L, 100L, OrderItemStatus.CANCELLED)));

    var result = service.inspect(new Command("27", List.of(10L, 99L)));

    assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
    assertThat(result.evidence()).contains(new Evidence(99L, "MISSING"));
}
```

- [ ] **Step 2: Run the application test and verify RED**

Run: `./gradlew :order-service:test --tests '*InspectCancelRestoreServiceTest'`

Expected: compilation fails because `InspectCancelRestoreService`, `Command`, `Evidence`, and `CancelRestoreLegStatus` do not exist.

- [ ] **Step 3: Implement the minimal order inspection contract and service**

```java
public enum CancelRestoreLegStatus { APPLIED, NOT_APPLIED, INCONSISTENT, UNKNOWN }

public interface InspectCancelRestoreUseCase {
    Result inspect(Command command);
    record Command(String cancelRequestId, List<Long> orderItemIds) {}
    record Evidence(long targetId, String currentStatus) {}
    record Result(CancelRestoreLegStatus status, List<Evidence> evidence) {}
}
```

`InspectCancelRestoreService` must load the marker and requested items once, return `INCONSISTENT` for missing targets, multiple orders, or marker/domain contradiction, return `APPLIED` only when the marker exists, all targets are `CANCELLED`, and the aggregate order is `CANCELLED|PARTIAL_CANCELLED`; otherwise return `NOT_APPLIED` only for the clean no-marker/not-cancelled case. It must use non-locking repository methods and have no save call.

- [ ] **Step 4: Run the application test and verify GREEN**

Run: `./gradlew :order-service:test --tests '*InspectCancelRestoreServiceTest'`

Expected: all order inspection service tests pass.

- [ ] **Step 5: Write the failing controller contract test**

```java
mockMvc.perform(post("/internal/cancel-restores/27:inspect")
        .contentType(APPLICATION_JSON)
        .content("{\"orderItemIds\":[10,11]}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("APPLIED"))
    .andExpect(jsonPath("$.evidence").isArray());
```

Also cover empty `orderItemIds` as HTTP 400.

- [ ] **Step 6: Run the controller test and verify RED**

Run: `./gradlew :order-service:test --tests '*InternalCancelRestoreControllerTest'`

Expected: 404 because the internal controller route is not registered.

- [ ] **Step 7: Implement DTO, controller, and bean wiring**

```java
public record InspectCancelRestoreRequest(
    @NotEmpty List<@NotNull Long> orderItemIds
) {}

@PostMapping("/internal/cancel-restores/{cancelRequestId}:inspect")
InspectCancelRestoreResponse inspect(
    @PathVariable String cancelRequestId,
    @Valid @RequestBody InspectCancelRestoreRequest request) {
    return InspectCancelRestoreResponse.from(useCase.inspect(
        new Command(cancelRequestId, request.orderItemIds())));
}
```

Register `InspectCancelRestoreUseCase` in `PersistenceConfig` using the existing repositories. Do not add authentication to downstream service-to-service endpoints in Issue 1; network isolation remains the current repository trust boundary.

- [ ] **Step 8: Run order-service tests and commit the slice**

Run: `./gradlew :order-service:test`

Expected: all order-service tests pass.

Commit: `git commit -m "feat(order): expose cancel restore inspection"`

---

### Task 2: product-service가 재고 복원 적용 상태를 읽기 전용으로 판정한다

**Files:**
- Create: `product-service/src/main/java/com/example/product/application/model/CancelRestoreLegStatus.java`
- Create: `product-service/src/main/java/com/example/product/application/usecase/InspectCancelRestoreUseCase.java`
- Create: `product-service/src/main/java/com/example/product/application/service/InspectCancelRestoreService.java`
- Create: `product-service/src/main/java/com/example/product/presentation/dto/InspectCancelRestoreRequest.java`
- Create: `product-service/src/main/java/com/example/product/presentation/dto/InspectCancelRestoreResponse.java`
- Create: `product-service/src/main/java/com/example/product/presentation/controller/InternalCancelRestoreController.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java`
- Test: `product-service/src/test/java/com/example/product/application/service/InspectCancelRestoreServiceTest.java`
- Test: `product-service/src/test/java/com/example/product/presentation/controller/InternalCancelRestoreControllerTest.java`

**Interfaces:**
- Consumes: `ProcessedCancelEventRepository.existsByCancelRequestId(String)` and `StockReservationRepository.findByPaymentKeyAndSkuId(String,long)`.
- Produces: `POST /internal/cancel-restores/{cancelRequestId}:inspect` with `{paymentKey, items:[{skuId,quantity}]}` and the same four status meanings as order-service.

- [ ] **Step 1: Write failing application tests**

```java
@Test
void markerAndReleasedReservationsAreApplied() {
    when(processed.existsByCancelRequestId("27")).thenReturn(true);
    when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
        .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RELEASED)));

    var result = service.inspect(command("27", "pay_1", item(8L, 2)));

    assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.APPLIED);
}

@Test
void noMarkerAndReservedReservationsAreNotApplied() {
    when(processed.existsByCancelRequestId("27")).thenReturn(false);
    when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
        .thenReturn(Optional.of(reservation(8L, 2, ReservationStatus.RESERVED)));

    assertThat(service.inspect(command("27", "pay_1", item(8L, 2))).status())
        .isEqualTo(CancelRestoreLegStatus.NOT_APPLIED);
}

@Test
void quantityOrMarkerMismatchIsInconsistent() {
    when(processed.existsByCancelRequestId("27")).thenReturn(true);
    when(reservations.findByPaymentKeyAndSkuId("pay_1", 8L))
        .thenReturn(Optional.of(reservation(8L, 1, ReservationStatus.RESERVED)));

    var result = service.inspect(command("27", "pay_1", item(8L, 2)));

    assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
    assertThat(result.evidence()).containsExactly(
        new Evidence(8L, "RESERVED", 1, 2));
}
```

Add cases for a missing reservation and duplicate `skuId` in the request; both must be `INCONSISTENT`, never `APPLIED`.

- [ ] **Step 2: Run product application tests and verify RED**

Run: `./gradlew :product-service:test --tests '*InspectCancelRestoreServiceTest'`

Expected: compilation fails because the inspection types do not exist.

- [ ] **Step 3: Implement the minimal service**

```java
public interface InspectCancelRestoreUseCase {
    Result inspect(Command command);
    record Command(String cancelRequestId, String paymentKey, List<Item> items) {}
    record Item(long skuId, int quantity) {}
    record Evidence(long skuId, String currentStatus, Integer actualQuantity, int expectedQuantity) {}
    record Result(CancelRestoreLegStatus status, List<Evidence> evidence) {}
}
```

`APPLIED` requires a processed marker and every requested `(paymentKey, skuId)` reservation in `RELEASED` with equal quantity. `NOT_APPLIED` requires no marker and every reservation in `RESERVED` with equal quantity. Missing, duplicate SKU, quantity mismatch, or marker/domain contradiction is `INCONSISTENT`. The service must not call `save`, `releaseIfReserved`, or stock restore operations.

- [ ] **Step 4: Verify GREEN and add the controller contract RED test**

Run: `./gradlew :product-service:test --tests '*InspectCancelRestoreServiceTest'`

Then write:

```java
mockMvc.perform(post("/internal/cancel-restores/27:inspect")
        .contentType(APPLICATION_JSON)
        .content("{\"paymentKey\":\"pay_1\",\"items\":[{\"skuId\":8,\"quantity\":2}]}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("APPLIED"));
```

Run: `./gradlew :product-service:test --tests '*InternalCancelRestoreControllerTest'`

Expected: 404 before the controller exists.

- [ ] **Step 5: Implement validated DTO/controller and wire the use case**

Use `@NotBlank paymentKey`, `@NotEmpty items`, positive `skuId`, and `@Positive quantity`. Map the response without exposing JPA entities.

- [ ] **Step 6: Run product-service tests and commit the slice**

Run: `./gradlew :product-service:test`

Expected: all product-service tests pass.

Commit: `git commit -m "feat(product): expose stock restore inspection"`

---

### Task 3: payment-service가 DEAD 원본과 취소 안전 조건을 판정한다

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/model/CancelOutboxDecision.java`
- Create: `payment-service/src/main/java/com/example/payment/application/model/CancelOutboxReasonCode.java`
- Create: `payment-service/src/main/java/com/example/payment/application/model/CancelEventPayload.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxSourcePort.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventPayloadParser.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/JacksonCancelEventPayloadParser.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxSourceAdapterIT.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/JacksonCancelEventPayloadParserTest.java`

**Interfaces:**
- Consumes: payment DB only.
- Produces: `CancelOutboxSourcePort.findById(long)` returning an immutable `SourceSnapshot`; `CancelEventPayloadParser.parse(String)` returning normalized IDs and quantities.

- [ ] **Step 1: Write failing source adapter integration tests**

```java
@Test
void loadsDeadOutboxWithCancelAndPaymentSnapshotWithoutMutation() {
    long id = seedDeadOutboxForCompletedCancelledPayment();

    var source = adapter.findById(id).orElseThrow();

    assertThat(source.status()).isEqualTo("DEAD");
    assertThat(source.cancelStatus()).isEqualTo(CancelStatus.COMPLETED);
    assertThat(source.paymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
    assertThat(source.payload()).contains("\"cancelRequestId\"");
    assertThat(jdbc.queryForObject(
        "select status from cancel_event_outbox where id=?", String.class, id))
        .isEqualTo("DEAD");
}
```

Also assert `Optional.empty()` for an unknown ID.

- [ ] **Step 2: Run the adapter test and verify RED**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxSourceAdapterIT'`

Expected: compilation fails because `CancelOutboxSourcePort` and its adapter contract do not exist.

- [ ] **Step 3: Extend the existing outbox adapter as a read-only source port**

```java
public interface CancelOutboxSourcePort {
    Optional<SourceSnapshot> findById(long outboxId);
    record SourceSnapshot(long outboxId, long cancelRequestId, String payload,
        String status, CancelStatus cancelStatus, PaymentStatus paymentStatus) {}
}
```

Use one read-only JDBC query joining `cancel_event_outbox`, `cancel_request`, and `payment`. Do not add any method that changes a DEAD row.

- [ ] **Step 4: Write parser RED tests and implement strict parsing**

```java
@Test
void parsesCanonicalCancelPayload() {
    var payload = parser.parse("""
        {"cancelRequestId":27,"paymentKey":"pay_1","merchantId":1,
         "cancelledItems":[{"paymentItemId":1,"orderItemId":10,
         "itemAmount":1000,"skuId":8,"quantity":2}],
         "cancelledAt":"2026-08-10T00:00:00Z"}
        """);

    assertThat(payload.cancelRequestId()).isEqualTo(27L);
    assertThat(payload.paymentKey()).isEqualTo("pay_1");
    assertThat(payload.items()).containsExactly(new Item(10L, 8L, 2));
}
```

Malformed JSON, missing `orderItemId`, null/non-positive `skuId`, non-positive quantity, empty items, and duplicate IDs must throw a dedicated `InvalidCancelEventPayloadException` mapped later to `INVALID_PAYLOAD`.

Run RED: `./gradlew :payment-service:test --tests '*JacksonCancelEventPayloadParserTest'`

Implement with the application-owned parser port and a Jackson infrastructure adapter; application code must not depend on `JsonNode`.

- [ ] **Step 5: Verify source and parser GREEN, then commit**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxSourceAdapterIT' --tests '*JacksonCancelEventPayloadParserTest'`

Expected: all selected tests pass.

Commit: `git commit -m "feat(payment): read cancel outbox inspection source"`

---

### Task 4: payment-service가 downstream 포트를 통해 전체 판정을 합성한다

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/model/CancelRestoreLegStatus.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/OrderCancelStatusPort.java`
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/StockRestoreStatusPort.java`
- Create: `payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxInspectionUseCase.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxInspectionService.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClient.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClient.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/ResilienceConfig.java`
- Test: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxInspectionServiceTest.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClientTest.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClientTest.java`

**Interfaces:**
- Consumes: Task 3 source/parser and Task 1/2 HTTP contracts.
- Produces: `CancelOutboxInspectionUseCase.inspect(long)` with `REDRIVE_REQUIRED`, `ALREADY_APPLIED`, `NOT_ELIGIBLE`, or `UNKNOWN`.

- [ ] **Step 1: Write the service decision-table RED tests**

```java
@Test
void oneNotAppliedLegRequiresRedrive() {
    givenEligibleDeadSource();
    when(order.inspect(any())).thenReturn(leg(APPLIED));
    when(stock.inspect(any())).thenReturn(leg(NOT_APPLIED));

    var result = service.inspect(6L);

    assertThat(result.decision()).isEqualTo(REDRIVE_REQUIRED);
    assertThat(result.order().status()).isEqualTo(APPLIED);
    assertThat(result.stock().status()).isEqualTo(NOT_APPLIED);
}

@Test
void bothAppliedMeansAlreadyApplied() {
    givenEligibleDeadSource();
    when(order.inspect(any())).thenReturn(leg(APPLIED));
    when(stock.inspect(any())).thenReturn(leg(APPLIED));

    assertThat(service.inspect(6L).decision()).isEqualTo(ALREADY_APPLIED);
}

@Test
void unknownLegMakesWholeDecisionUnknown() {
    givenEligibleDeadSource();
    when(order.inspect(any())).thenReturn(leg(UNKNOWN));
    when(stock.inspect(any())).thenReturn(leg(APPLIED));

    assertThat(service.inspect(6L).decision()).isEqualTo(UNKNOWN);
}
```

Add short-circuit tests for missing outbox, non-DEAD, non-COMPLETED cancel request, non-cancelled payment, and invalid payload. Verify the downstream ports are never called on every ineligible path.

- [ ] **Step 2: Run service tests and verify RED**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxInspectionServiceTest'`

Expected: compilation fails because the inspection use case and ports do not exist.

- [ ] **Step 3: Implement the decision table**

```java
if (!source.status().equals("DEAD")) return notEligible(OUTBOX_NOT_DEAD);
if (source.cancelStatus() != COMPLETED) return notEligible(CANCEL_NOT_COMPLETED);
if (!Set.of(CANCELLED, PARTIAL_CANCELLED).contains(source.paymentStatus()))
    return notEligible(PAYMENT_NOT_CANCELLED);

var orderLeg = orderPort.inspect(orderCommand(payload));
var stockLeg = stockPort.inspect(stockCommand(payload));
if (orderLeg.status() == UNKNOWN || stockLeg.status() == UNKNOWN) return unknown(...);
if (orderLeg.status() == INCONSISTENT || stockLeg.status() == INCONSISTENT)
    return notEligible(INCONSISTENT_DOWNSTREAM_STATE, ...);
if (orderLeg.status() == APPLIED && stockLeg.status() == APPLIED)
    return alreadyApplied(...);
return redriveRequired(...);
```

The result must preserve both leg snapshots for later redrive audit use. The application service catches only downstream-unavailable exceptions and maps them to `UNKNOWN`; programming errors remain errors.

- [ ] **Step 4: Write HTTP adapter RED tests**

For each adapter verify the exact route/body, complete response mapping, 5xx, timeout, missing body, and CircuitBreaker OPEN. The observable assertion is the returned `UNKNOWN` leg, not mock call count, except where call suppression by an open breaker is the contract.

Run: `./gradlew :payment-service:test --tests '*OrderCancelStatusHttpClientTest' --tests '*StockRestoreStatusHttpClientTest'`

Expected: compilation fails before the adapters exist.

- [ ] **Step 5: Implement adapters and dedicated read CircuitBreakers**

Use `RestTemplate.postForEntity` with Task 1/2 bodies and map response `status/evidence`. Add `orderCancelStatusCircuitBreaker` and `stockRestoreStatusCircuitBreaker`; do not reuse create-flow breakers because inspection failures must not open transactional call breakers.

- [ ] **Step 6: Run the selected tests and commit**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxInspectionServiceTest' --tests '*OrderCancelStatusHttpClientTest' --tests '*StockRestoreStatusHttpClientTest'`

Expected: all selected tests pass.

Commit: `git commit -m "feat(payment): aggregate cancel outbox inspection"`

---

### Task 5: 운영자 inspection API와 안정적인 오류 계약을 제공한다

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java`
- Create: `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxInspectionResponse.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxNotFoundException.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxForbiddenException.java`
- Modify: `payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`
- Modify: `docs/error-catalog.md`
- Modify: `docs/api-spec.md`
- Test: `payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java`

**Interfaces:**
- Consumes: `CancelOutboxInspectionUseCase` from Task 4.
- Produces: `GET /internal/cancel-outbox/{outboxId}` for authenticated ADMIN operators.

- [ ] **Step 1: Write controller RED tests for success and auth boundaries**

```java
mockMvc.perform(get("/internal/cancel-outbox/6")
        .header("X-User-Role", "ADMIN")
        .header("X-User-Id", "operator-1"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.outboxId").value(6))
    .andExpect(jsonPath("$.decision").value("REDRIVE_REQUIRED"))
    .andExpect(jsonPath("$.order.status").value("APPLIED"))
    .andExpect(jsonPath("$.stock.status").value("NOT_APPLIED"));
```

Add: missing role → 401, missing operator ID → 403, non-ADMIN → 403, missing outbox → 404 `CANCEL_OUTBOX_NOT_FOUND`. Verify the use case is not invoked on authentication/authorization failure.

- [ ] **Step 2: Run controller tests and verify RED**

Run: `./gradlew :payment-service:test --tests '*InternalCancelOutboxControllerTest'`

Expected: 404 because the route is not registered.

- [ ] **Step 3: Implement controller, response mapping, and errors**

```java
@GetMapping("/internal/cancel-outbox/{outboxId}")
CancelOutboxInspectionResponse inspect(
    @PathVariable long outboxId,
    @RequestHeader(value = "X-User-Role", required = false) String role,
    @RequestHeader(value = "X-User-Id", required = false) String operatorId) {
    operatorAccess.requireAdmin(role, operatorId);
    return CancelOutboxInspectionResponse.from(useCase.inspect(outboxId));
}
```

Use stable `ErrorCode` entries for `CANCEL_OUTBOX_NOT_FOUND` and the internal access failures. Do not return raw payload, payment key, or exception text in the API response.

- [ ] **Step 4: Update API and error documentation**

Document request headers, the four decisions, the four leg states, example 200 responses, and 401/403/404 responses. State explicitly that inspection is read-only and may return `UNKNOWN` on dependency failure.

- [ ] **Step 5: Run all three service modules and verify no regression**

Run: `./gradlew :order-service:test :product-service:test :payment-service:test`

Expected: all tests pass with no compilation warnings introduced by this feature.

- [ ] **Step 6: Run mutation-oriented checks and commit Issue 1**

Manually verify that changing either `APPLIED` branch to `NOT_APPLIED`, removing the marker check, accepting a non-DEAD outbox, or permitting missing operator identity causes at least one test to fail. Restore the correct implementation and rerun the three module suites.

Commit: `git commit -m "feat(payment): expose controlled redrive inspection API"`

---

## Issue 1 Completion Check

- [ ] `GET /internal/cancel-outbox/{id}` returns the documented decision and evidence.
- [ ] order/product inspection endpoints perform zero writes.
- [ ] every unsafe source path short-circuits before downstream calls.
- [ ] downstream dependency failures produce `UNKNOWN`, never `ALREADY_APPLIED`.
- [ ] `./gradlew :order-service:test :product-service:test :payment-service:test` passes.
- [ ] `docs/features/cancel-outbox-redrive/issues.md` Issue 1 ACs can each be mapped to an automated test.
