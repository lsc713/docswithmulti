# Checkout Issue #115 Order Payment State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문을 `PENDING`으로 생성하고 `payment.completed`를 멱등 소비한 뒤에만 `DELIVERY_WAITING`으로 전이한다.

**Architecture:** payment-service의 기존 완료 아웃박스 payload에 하위 호환 `orderId`를 추가한다. order-service는 새 consumer와 작은 application use case로 이벤트를 처리하며, 주문 행 잠금과 상태 가드로 중복 전달을 멱등 처리한다. settlement-service는 추가 필드를 무시하고 기존 SALE 계산을 유지한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Kafka, JPA, JUnit 5, Mockito, AssertJ, Gradle

**Spec:** `docs/features/toss-payment-approval/issues.md` Issue 1 / GitHub #115

## Global Constraints

- 기존 `payment.completed` 필드는 삭제·이름 변경·의미 변경하지 않는다.
- 결제 완료 이벤트가 없는 주문은 배송 상태로 전이하지 않는다.
- 중복 이벤트는 주문 상태를 한 번만 변경한다.
- settlement-service의 `paymentKey` 기준 SALE 금액 계산을 바꾸지 않는다.
- 새 공유 DTO 모듈이나 새 메시징 프레임워크를 추가하지 않는다.

---

### Task 1: 주문 도메인에 결제 대기와 멱등 완료 전이를 추가한다

**Files:**
- Modify: `order-service/src/main/java/com/example/order/domain/entity/Order.java`
- Modify: `order-service/src/test/java/com/example/order/domain/entity/OrderTest.java`

**Interfaces:**
- Consumes: 기존 `OrderStatus.PENDING`, `OrderStatus.PAYMENT_VERIFYING`, `OrderStatus.DELIVERY_WAITING`
- Produces: `boolean Order.markPaymentCompleted()` — 상태를 실제 변경했을 때만 `true`

- [ ] **Step 1: 신규 주문과 완료 전이의 실패 테스트를 작성한다**

```java
@Test
void new_order_waits_for_payment() {
    assertThat(Order.create(100L).getStatus()).isEqualTo(OrderStatus.PENDING);
}

@Test
void payment_completed_moves_pending_order_once() {
    Order order = Order.of(1L, 100L, OrderStatus.PENDING);
    assertThat(order.markPaymentCompleted()).isTrue();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
    assertThat(order.markPaymentCompleted()).isFalse();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
}

@Test
void late_completed_event_does_not_revive_cancelled_order() {
    Order order = Order.of(1L, 100L, OrderStatus.CANCELLED);
    assertThat(order.markPaymentCompleted()).isFalse();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
}
```

- [ ] **Step 2: 도메인 테스트가 현재 코드에서 실패하는지 확인한다**

Run: `./gradlew :order-service:test --tests com.example.order.domain.entity.OrderTest`

Expected: `new_order_waits_for_payment`가 `DELIVERY_WAITING`으로 실패하고 `markPaymentCompleted()` 컴파일 오류가 발생한다.

- [ ] **Step 3: 최소 상태 전이를 구현한다**

```java
public static Order create(long userId) {
    return new Order(0, userId, OrderStatus.PENDING);
}

public boolean markPaymentCompleted() {
    if (status != OrderStatus.PENDING && status != OrderStatus.PAYMENT_VERIFYING) {
        return false;
    }
    status = OrderStatus.DELIVERY_WAITING;
    return true;
}
```

- [ ] **Step 4: 도메인 테스트를 다시 실행한다**

Run: `./gradlew :order-service:test --tests com.example.order.domain.entity.OrderTest`

Expected: `BUILD SUCCESSFUL` and all `OrderTest` tests pass.

---

### Task 2: order-service가 완료 이벤트를 멱등 처리한다

**Files:**
- Create: `order-service/src/main/java/com/example/order/application/usecase/MarkOrderPaymentCompletedUseCase.java`
- Create: `order-service/src/main/java/com/example/order/application/service/MarkOrderPaymentCompletedService.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCompletedPayload.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCompletedConsumer.java`
- Modify: `order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java`
- Modify: `order-service/src/main/resources/application.yml`
- Create: `order-service/src/test/java/com/example/order/application/service/MarkOrderPaymentCompletedServiceTest.java`
- Create: `order-service/src/test/java/com/example/order/infrastructure/messaging/PaymentCompletedConsumerTest.java`

**Interfaces:**
- Consumes: `OrderRepository.findByIdForUpdate(long)`, `Order.markPaymentCompleted()`
- Produces: `MarkOrderPaymentCompletedUseCase.execute(new Command(long orderId))`
- Produces: Kafka payload `PaymentCompletedPayload` with all existing fields plus nullable `Long orderId`; absent legacy `orderId` is acknowledged and skipped

- [ ] **Step 1: 행 잠금과 중복 no-op 서비스 테스트를 작성한다**

```java
@Test
void pending_order_is_saved_as_delivery_waiting() {
    Order order = Order.of(7L, 42L, OrderStatus.PENDING);
    when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));

    service.execute(new MarkOrderPaymentCompletedUseCase.Command(7L));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
    verify(orderRepository).save(order);
}

@Test
void duplicate_completed_event_does_not_save_again() {
    Order order = Order.of(7L, 42L, OrderStatus.DELIVERY_WAITING);
    when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));

    service.execute(new MarkOrderPaymentCompletedUseCase.Command(7L));

    verify(orderRepository, never()).save(any());
}
```

- [ ] **Step 2: consumer 계약 실패 테스트를 작성한다**

```java
@Test
void valid_completed_event_executes_and_acknowledges() {
    Acknowledgment ack = mock(Acknowledgment.class);
    var record = new ConsumerRecord<String, String>("payment.completed", 0, 1L, "pay_1",
        "{\"paymentKey\":\"pay_1\",\"orderId\":7}");

    consumer.consume(record, ack);

    verify(useCase).execute(new MarkOrderPaymentCompletedUseCase.Command(7L));
    verify(ack).acknowledge();
}

@Test
void legacy_event_without_order_id_is_acknowledged_without_execution() {
    Acknowledgment ack = mock(Acknowledgment.class);
    var record = new ConsumerRecord<String, String>("payment.completed", 0, 1L, "pay_old",
        "{\"paymentKey\":\"pay_old\"}");

    consumer.consume(record, ack);

    verifyNoInteractions(useCase);
    verify(ack).acknowledge();
}
```

- [ ] **Step 3: 새 테스트들이 구현 부재로 실패하는지 확인한다**

Run: `./gradlew :order-service:test --tests '*MarkOrderPaymentCompletedServiceTest' --tests '*PaymentCompletedConsumerTest'`

Expected: new use case, service, payload, and consumer types are missing.

- [ ] **Step 4: use case와 트랜잭션 서비스를 구현한다**

```java
public interface MarkOrderPaymentCompletedUseCase {
    record Command(long orderId) {}
    void execute(Command command);
}

@RequiredArgsConstructor
public class MarkOrderPaymentCompletedService implements MarkOrderPaymentCompletedUseCase {
    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(Command command) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByIdForUpdate(command.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: orderId=" + command.orderId()));
            if (order.markPaymentCompleted()) {
                orderRepository.save(order);
            }
        });
    }
}
```

- [ ] **Step 5: payload, consumer, bean과 topic을 구현한다**

```java
public record PaymentCompletedPayload(
    String paymentKey,
    Long orderId,
    long merchantId,
    BigDecimal totalAmount,
    List<Item> items,
    String completedAt
) {
    public record Item(long paymentItemId, BigDecimal itemAmount) {}
}

@KafkaListener(
    topics = "${kafka.topic.payment-completed}",
    groupId = "${spring.kafka.consumer.group-id}",
    containerFactory = "kafkaListenerContainerFactory")
public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    try {
        PaymentCompletedPayload payload = objectMapper.readValue(record.value(), PaymentCompletedPayload.class);
        if (payload.orderId() != null && payload.orderId() > 0) {
            useCase.execute(new MarkOrderPaymentCompletedUseCase.Command(payload.orderId()));
        }
        ack.acknowledge();
    } catch (Exception e) {
        throw new IllegalStateException("payment.completed 처리 실패. offset=" + record.offset(), e);
    }
}
```

Add `payment-completed: payment.completed` under `kafka.topic` and register `MarkOrderPaymentCompletedService` in `PersistenceConfig` with the existing `OrderRepository` and `TransactionTemplate` beans.

- [ ] **Step 6: order-service의 관련 테스트를 실행한다**

Run: `./gradlew :order-service:test --tests '*MarkOrderPaymentCompletedServiceTest' --tests '*PaymentCompletedConsumerTest' --tests com.example.order.domain.entity.OrderTest`

Expected: `BUILD SUCCESSFUL` and all selected tests pass.

---

### Task 3: 완료 이벤트에 orderId를 추가하고 정산 호환성을 증명한다

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/PaymentCreateTxWriter.java`
- Modify: `payment-service/src/test/java/com/example/payment/integration/PaymentCompletedOutboxIntegrationTest.java`
- Modify: `settlement-service/src/test/java/com/example/settlement/integration/SaleLedgerIntegrationTest.java`

**Interfaces:**
- Consumes: `Payment.getOrderId()`
- Produces: existing `payment.completed` JSON plus numeric `orderId`
- Preserves: `paymentKey`, `merchantId`, `totalAmount`, `items`, `completedAt`

- [ ] **Step 1: outbox payload의 orderId 실패 검증을 추가한다**

```java
assertThat(node.get("orderId").asLong()).isEqualTo(777L);
```

Insert the assertion in `createPayment_writesOnePendingOutboxRow_withZSuffix()` after parsing the stored JSON.

- [ ] **Step 2: 정산 통합 이벤트에 추가 필드를 넣는다**

```json
{"paymentKey":"PAY-SALE-1","orderId":7001,"merchantId":77,"totalAmount":50000,
 "items":[{"paymentItemId":1,"itemAmount":30000},{"paymentItemId":2,"itemAmount":20000}],
 "completedAt":"2026-07-31T00:00:00.123Z"}
```

Keep the existing assertions: one SALE line and `gross_amount=50000`.

- [ ] **Step 3: payment payload 테스트가 실패하는지 확인한다**

Run: `./gradlew :payment-service:test --tests '*PaymentCompletedOutboxIntegrationTest.createPayment_writesOnePendingOutboxRow_withZSuffix'`

Expected: assertion fails because `orderId` is absent.

- [ ] **Step 4: 완료 payload에 orderId를 추가한다**

```java
return String.format(
    "{\"paymentKey\":\"%s\",\"orderId\":%d,\"merchantId\":%d,\"totalAmount\":%s," +
    "\"items\":%s,\"completedAt\":\"%s\"}",
    saved.getPaymentKey(), saved.getOrderId(), saved.getMerchantId(),
    saved.getTotalAmount().toPlainString(), itemsJson,
    saved.getCreatedAt().toInstant(ZoneOffset.UTC));
```

- [ ] **Step 5: payment와 settlement 호환 테스트를 실행한다**

Run: `./gradlew :payment-service:test --tests '*PaymentCompletedOutboxIntegrationTest.createPayment_writesOnePendingOutboxRow_withZSuffix' :settlement-service:test --tests '*SaleLedgerIntegrationTest.saleEventRecordsLedgerLineAndGross'`

Expected: `BUILD SUCCESSFUL`; payload has `orderId`, settlement still records gross 50,000 once.

---

### Task 4: #115 전체 회귀를 검증한다

**Files:**
- Modify only if a failing existing assertion still expects `DELIVERY_WAITING` at order creation.

**Interfaces:**
- Consumes: Tasks 1–3 outputs
- Produces: deployable #115 slice with no pending test failures

- [ ] **Step 1: 영향 모듈 전체 테스트를 실행한다**

Run: `./gradlew :order-service:test :payment-service:test :settlement-service:test`

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: 정적 diff 검사를 실행한다**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: GitHub #115 AC를 결과와 대조한다**

Check that the fresh test output proves: initial `PENDING`, one transition on completed event, duplicate no-op, settlement accepts additive `orderId`, and no completed event leaves the order non-deliverable.
