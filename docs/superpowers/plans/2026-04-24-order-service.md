# order-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `payment.cancelled` Kafka Consumer 구현 — OrderItem/Order 상태 동기화, 3회 재시도, DLQ 발행, 멱등성 보장

**Architecture:** 별도 Main/Retry Consumer 클래스가 공유 `ProcessCancelledItemsUseCase`에 위임한다. TX 안에서 멱등 체크 → OrderItem CANCELLED → Order 상태 재계산 → processed_cancel_event INSERT. 실패 시 `RetryRouter`가 헤더 기반으로 retry 토픽 또는 DLQ로 분기한다.

**Tech Stack:** Java 21, Spring Boot 3, Spring Kafka, Spring Data JPA, MySQL 8, Flyway, JUnit 5, Mockito, Lombok

---

## File Map

```
order-service/
├── build.gradle
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__create_order_core.sql
├── src/main/java/com/example/order/
│   ├── OrderServiceApplication.java
│   ├── domain/entity/
│   │   ├── OrderStatus.java
│   │   ├── OrderItemStatus.java
│   │   ├── Order.java
│   │   └── OrderItem.java
│   ├── application/
│   │   ├── exception/OrderItemNotFoundException.java
│   │   ├── interfaces/
│   │   │   ├── OrderRepository.java
│   │   │   ├── OrderItemRepository.java
│   │   │   └── ProcessedCancelEventRepository.java
│   │   ├── usecase/ProcessCancelledItemsUseCase.java
│   │   └── service/ProcessCancelledItemsService.java
│   └── infrastructure/
│       ├── persistence/
│       │   ├── OrderJpaEntity.java
│       │   ├── OrderItemJpaEntity.java
│       │   ├── ProcessedCancelEventJpaEntity.java
│       │   ├── OrderJpaRepository.java
│       │   ├── OrderItemJpaRepository.java
│       │   ├── ProcessedCancelEventJpaRepository.java
│       │   ├── OrderRepositoryImpl.java
│       │   ├── OrderItemRepositoryImpl.java
│       │   └── ProcessedCancelEventRepositoryImpl.java
│       ├── messaging/
│       │   ├── PaymentCancelledPayload.java
│       │   ├── DlqMessage.java
│       │   ├── RetryRouter.java
│       │   ├── PaymentCancelledConsumer.java
│       │   └── PaymentCancelledRetryConsumer.java
│       └── config/
│           ├── PersistenceConfig.java
│           ├── KafkaConsumerConfig.java
│           └── KafkaProducerConfig.java
└── src/test/java/com/example/order/
    ├── domain/entity/
    │   ├── OrderTest.java
    │   └── OrderItemTest.java
    ├── application/service/
    │   └── ProcessCancelledItemsServiceTest.java
    └── infrastructure/messaging/
        └── RetryRouterTest.java
```

---

## Task 1: Project Scaffolding

**Files:**
- Modify: `order-service/build.gradle`
- Create: `order-service/src/main/resources/db/migration/V1__create_order_core.sql`
- Create: `order-service/src/main/resources/application.yml`
- Create: `order-service/src/main/java/com/example/order/OrderServiceApplication.java`

- [ ] **Step 1: build.gradle 작성**

```groovy
// order-service/build.gradle
// 부모 build.gradle의 subprojects 설정을 상속받음

dependencies {
    implementation 'org.springframework.kafka:spring-kafka'
}
```

- [ ] **Step 2: DDL 작성**

`order` 는 MySQL 예약어이므로 테이블명을 `orders` 로 사용한다.

```sql
-- order-service/src/main/resources/db/migration/V1__create_order_core.sql
CREATE TABLE orders (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    status     VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
);

CREATE TABLE order_item (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_id   BIGINT      NOT NULL,
    status     VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    INDEX idx_order_item_order_id (order_id)
);

CREATE TABLE processed_cancel_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64) NOT NULL,
    processed_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_processed_cancel_event_cancel_request_id (cancel_request_id)
);
```

- [ ] **Step 3: application.yml 작성**

```yaml
# order-service/src/main/resources/application.yml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/order_db?serverTimezone=UTC&characterEncoding=UTF-8
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:password}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092,localhost:9093,localhost:9094}
    consumer:
      group-id: order-service
      retry-group-id: order-service-retry

kafka:
  topic:
    payment-cancelled: payment.cancelled
    payment-cancelled-retry: payment.cancelled.retry
    payment-cancelled-dlq: payment.cancelled.DLQ
```

- [ ] **Step 4: Application main class 작성**

```java
// order-service/src/main/java/com/example/order/OrderServiceApplication.java
package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: 커밋**

```bash
git add order-service/
git commit -m "feat(order): 프로젝트 스캐폴딩 — DDL, application.yml, Application"
```

---

## Task 2: Domain Entities

**Files:**
- Create: `order-service/src/main/java/com/example/order/domain/entity/OrderStatus.java`
- Create: `order-service/src/main/java/com/example/order/domain/entity/OrderItemStatus.java`
- Create: `order-service/src/main/java/com/example/order/domain/entity/Order.java`
- Create: `order-service/src/main/java/com/example/order/domain/entity/OrderItem.java`
- Test: `order-service/src/test/java/com/example/order/domain/entity/OrderTest.java`
- Test: `order-service/src/test/java/com/example/order/domain/entity/OrderItemTest.java`

- [ ] **Step 1: 실패하는 도메인 테스트 작성**

```java
// order-service/src/test/java/com/example/order/domain/entity/OrderTest.java
package com.example.order.domain.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void should_become_cancelled_when_cancel_called() {
        Order order = Order.of(1L, OrderStatus.PAID);
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void should_become_partial_cancelled_when_partialCancel_called() {
        Order order = Order.of(1L, OrderStatus.PAID);
        order.partialCancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_CANCELLED);
    }
}
```

```java
// order-service/src/test/java/com/example/order/domain/entity/OrderItemTest.java
package com.example.order.domain.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderItemTest {

    @Test
    void should_become_cancelled_when_cancel_called() {
        OrderItem item = OrderItem.of(1L, 10L, OrderItemStatus.ACTIVE);
        item.cancel();
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(item.isCancelled()).isTrue();
    }

    @Test
    void should_not_be_cancelled_when_active() {
        OrderItem item = OrderItem.of(1L, 10L, OrderItemStatus.ACTIVE);
        assertThat(item.isCancelled()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.domain.entity.*"
```

Expected: FAIL (클래스 없음)

- [ ] **Step 3: enum 작성**

```java
// order-service/src/main/java/com/example/order/domain/entity/OrderStatus.java
package com.example.order.domain.entity;

public enum OrderStatus {
    PENDING, PAYMENT_VERIFYING, PAID, DELIVERY_WAITING,
    DELIVERING, DELIVERED, CONFIRMED, PARTIAL_CANCELLED, CANCELLED
}
```

```java
// order-service/src/main/java/com/example/order/domain/entity/OrderItemStatus.java
package com.example.order.domain.entity;

public enum OrderItemStatus {
    ACTIVE, CANCELLED
}
```

- [ ] **Step 4: Order, OrderItem 엔티티 작성**

```java
// order-service/src/main/java/com/example/order/domain/entity/Order.java
package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class Order {

    private final long id;
    private OrderStatus status;

    private Order(long id, OrderStatus status) {
        this.id = id;
        this.status = status;
    }

    public static Order of(long id, OrderStatus status) {
        return new Order(id, status);
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void partialCancel() {
        this.status = OrderStatus.PARTIAL_CANCELLED;
    }
}
```

```java
// order-service/src/main/java/com/example/order/domain/entity/OrderItem.java
package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class OrderItem {

    private final long id;
    private final long orderId;
    private OrderItemStatus status;

    private OrderItem(long id, long orderId, OrderItemStatus status) {
        this.id = id;
        this.orderId = orderId;
        this.status = status;
    }

    public static OrderItem of(long id, long orderId, OrderItemStatus status) {
        return new OrderItem(id, orderId, status);
    }

    public void cancel() {
        this.status = OrderItemStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return this.status == OrderItemStatus.CANCELLED;
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.domain.entity.*"
```

Expected: PASS (4 tests)

- [ ] **Step 6: 커밋**

```bash
git add order-service/src/main/java/com/example/order/domain/ \
        order-service/src/test/java/com/example/order/domain/
git commit -m "feat(order): Order, OrderItem 도메인 엔티티 + 단위 테스트"
```

---

## Task 3: Application Layer — Interfaces, UseCase, Exception

**Files:**
- Create: `order-service/src/main/java/com/example/order/application/exception/OrderItemNotFoundException.java`
- Create: `order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java`
- Create: `order-service/src/main/java/com/example/order/application/interfaces/OrderItemRepository.java`
- Create: `order-service/src/main/java/com/example/order/application/interfaces/ProcessedCancelEventRepository.java`
- Create: `order-service/src/main/java/com/example/order/application/usecase/ProcessCancelledItemsUseCase.java`

- [ ] **Step 1: 예외 클래스 작성**

```java
// order-service/src/main/java/com/example/order/application/exception/OrderItemNotFoundException.java
package com.example.order.application.exception;

import java.util.List;

public class OrderItemNotFoundException extends RuntimeException {

    public OrderItemNotFoundException(List<Long> orderItemIds) {
        super("OrderItem not found: orderItemIds=" + orderItemIds);
    }
}
```

- [ ] **Step 2: 인터페이스 작성**

```java
// order-service/src/main/java/com/example/order/application/interfaces/OrderRepository.java
package com.example.order.application.interfaces;

import com.example.order.domain.entity.Order;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findByIdForUpdate(long id);
    void save(Order order);
}
```

```java
// order-service/src/main/java/com/example/order/application/interfaces/OrderItemRepository.java
package com.example.order.application.interfaces;

import com.example.order.domain.entity.OrderItem;
import java.util.List;

public interface OrderItemRepository {
    List<OrderItem> findAllByIdIn(List<Long> ids);
    List<OrderItem> findAllByOrderId(long orderId);
    void saveAll(List<OrderItem> items);
}
```

```java
// order-service/src/main/java/com/example/order/application/interfaces/ProcessedCancelEventRepository.java
package com.example.order.application.interfaces;

public interface ProcessedCancelEventRepository {
    boolean existsByCancelRequestId(String cancelRequestId);
    void save(String cancelRequestId);
}
```

- [ ] **Step 3: UseCase 작성**

```java
// order-service/src/main/java/com/example/order/application/usecase/ProcessCancelledItemsUseCase.java
package com.example.order.application.usecase;

import java.util.List;

public interface ProcessCancelledItemsUseCase {

    void execute(Command command);

    record Command(String cancelRequestId, List<Long> cancelledOrderItemIds) {}
}
```

- [ ] **Step 4: 커밋**

```bash
git add order-service/src/main/java/com/example/order/application/
git commit -m "feat(order): application 레이어 — 인터페이스, UseCase, 예외"
```

---

## Task 4: ProcessCancelledItemsService (TDD)

**Files:**
- Create: `order-service/src/main/java/com/example/order/application/service/ProcessCancelledItemsService.java`
- Test: `order-service/src/test/java/com/example/order/application/service/ProcessCancelledItemsServiceTest.java`

- [ ] **Step 1: 실패하는 서비스 테스트 작성**

```java
// order-service/src/test/java/com/example/order/application/service/ProcessCancelledItemsServiceTest.java
package com.example.order.application.service;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessCancelledItemsServiceTest {

    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private ProcessedCancelEventRepository processedCancelEventRepository;
    private ProcessCancelledItemsService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderItemRepository = mock(OrderItemRepository.class);
        processedCancelEventRepository = mock(ProcessedCancelEventRepository.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        service = new ProcessCancelledItemsService(
            orderRepository, orderItemRepository, processedCancelEventRepository,
            new TransactionTemplate(txManager));
    }

    @Test
    void should_do_nothing_when_cancel_request_already_processed() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_1")).thenReturn(true);

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_1", List.of(10L)));

        verify(orderItemRepository, never()).findAllByIdIn(any());
        verify(processedCancelEventRepository, never()).save(any());
    }

    @Test
    void should_throw_when_order_item_not_found() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_2")).thenReturn(false);
        when(orderItemRepository.findAllByIdIn(List.of(99L))).thenReturn(List.of());

        assertThrows(OrderItemNotFoundException.class,
            () -> service.execute(new ProcessCancelledItemsUseCase.Command("cr_2", List.of(99L))));
    }

    @Test
    void should_cancel_order_when_all_items_cancelled() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_3")).thenReturn(false);

        OrderItem item1 = OrderItem.of(10L, 1L, OrderItemStatus.ACTIVE);
        OrderItem item2 = OrderItem.of(11L, 1L, OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByIdIn(List.of(10L, 11L))).thenReturn(List.of(item1, item2));

        Order order = Order.of(1L, OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        // item1, item2 가 cancel() 호출 후 CANCELLED 상태가 됨
        when(orderItemRepository.findAllByOrderId(1L)).thenReturn(List.of(item1, item2));

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_3", List.of(10L, 11L)));

        assertThat(item1.isCancelled()).isTrue();
        assertThat(item2.isCancelled()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(processedCancelEventRepository).save("cr_3");
    }

    @Test
    void should_partial_cancel_order_when_some_items_remain_active() {
        when(processedCancelEventRepository.existsByCancelRequestId("cr_4")).thenReturn(false);

        OrderItem item1 = OrderItem.of(10L, 1L, OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByIdIn(List.of(10L))).thenReturn(List.of(item1));

        Order order = Order.of(1L, OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(order));

        // item1 은 cancel() 후 CANCELLED, item2 는 여전히 ACTIVE
        OrderItem item2 = OrderItem.of(11L, 1L, OrderItemStatus.ACTIVE);
        when(orderItemRepository.findAllByOrderId(1L)).thenReturn(List.of(item1, item2));

        service.execute(new ProcessCancelledItemsUseCase.Command("cr_4", List.of(10L)));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_CANCELLED);
        verify(processedCancelEventRepository).save("cr_4");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.application.service.ProcessCancelledItemsServiceTest"
```

Expected: FAIL (클래스 없음)

- [ ] **Step 3: ProcessCancelledItemsService 구현**

```java
// order-service/src/main/java/com/example/order/application/service/ProcessCancelledItemsService.java
package com.example.order.application.service;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@RequiredArgsConstructor
public class ProcessCancelledItemsService implements ProcessCancelledItemsUseCase {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProcessedCancelEventRepository processedCancelEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(Command command) {
        transactionTemplate.execute(status -> {
            if (processedCancelEventRepository.existsByCancelRequestId(command.cancelRequestId())) {
                return null;
            }

            List<OrderItem> items = orderItemRepository.findAllByIdIn(command.cancelledOrderItemIds());
            if (items.size() != command.cancelledOrderItemIds().size()) {
                throw new OrderItemNotFoundException(command.cancelledOrderItemIds());
            }

            items.forEach(OrderItem::cancel);
            orderItemRepository.saveAll(items);

            long orderId = items.get(0).getOrderId();
            Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: orderId=" + orderId));

            boolean allCancelled = orderItemRepository.findAllByOrderId(orderId)
                .stream().allMatch(OrderItem::isCancelled);

            if (allCancelled) {
                order.cancel();
            } else {
                order.partialCancel();
            }

            orderRepository.save(order);
            processedCancelEventRepository.save(command.cancelRequestId());
            return null;
        });
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.application.service.ProcessCancelledItemsServiceTest"
```

Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add order-service/src/main/java/com/example/order/application/service/ \
        order-service/src/test/java/com/example/order/application/service/
git commit -m "feat(order): ProcessCancelledItemsService TDD 구현"
```

---

## Task 5: JPA Persistence Layer

**Files:**
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderJpaEntity.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemJpaEntity.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventJpaEntity.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderJpaRepository.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemJpaRepository.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventJpaRepository.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderRepositoryImpl.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemRepositoryImpl.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventRepositoryImpl.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java`

- [ ] **Step 1: JPA 엔티티 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderJpaEntity.java
package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderJpaEntity() {}

    public void updateStatus(OrderStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Order toDomain() {
        return Order.of(id, status);
    }

    public Long getId() { return id; }
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemJpaEntity.java
package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_item")
public class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderItemStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderItemJpaEntity() {}

    public void updateStatus(OrderItemStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public OrderItem toDomain() {
        return OrderItem.of(id, orderId, status);
    }

    public Long getId() { return id; }
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventJpaEntity.java
package com.example.order.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_cancel_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_processed_cancel_event_cancel_request_id",
        columnNames = "cancel_request_id"))
public class ProcessedCancelEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64, unique = true)
    private String cancelRequestId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedCancelEventJpaEntity() {}

    public static ProcessedCancelEventJpaEntity of(String cancelRequestId) {
        ProcessedCancelEventJpaEntity e = new ProcessedCancelEventJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.processedAt = Instant.now();
        return e;
    }
}
```

- [ ] **Step 2: Spring Data JPA 리포지토리 인터페이스 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderJpaRepository.java
package com.example.order.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderJpaEntity o WHERE o.id = :id")
    Optional<OrderJpaEntity> findByIdForUpdate(@Param("id") Long id);
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemJpaRepository.java
package com.example.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderItemJpaRepository extends JpaRepository<OrderItemJpaEntity, Long> {
    List<OrderItemJpaEntity> findAllByOrderId(Long orderId);
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventJpaRepository.java
package com.example.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCancelEventJpaRepository extends JpaRepository<ProcessedCancelEventJpaEntity, Long> {
    boolean existsByCancelRequestId(String cancelRequestId);
}
```

- [ ] **Step 3: Repository 구현체 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderRepositoryImpl.java
package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.OrderRepository;
import com.example.order.domain.entity.Order;
import lombok.RequiredArgsConstructor;
import java.util.Optional;

@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpa;

    @Override
    public Optional<Order> findByIdForUpdate(long id) {
        return jpa.findByIdForUpdate(id).map(OrderJpaEntity::toDomain);
    }

    @Override
    public void save(Order order) {
        jpa.findById(order.getId())
            .ifPresent(e -> e.updateStatus(order.getStatus()));
    }
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/OrderItemRepositoryImpl.java
package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class OrderItemRepositoryImpl implements OrderItemRepository {

    private final OrderItemJpaRepository jpa;

    @Override
    public List<OrderItem> findAllByIdIn(List<Long> ids) {
        return jpa.findAllById(ids).stream()
            .map(OrderItemJpaEntity::toDomain)
            .toList();
    }

    @Override
    public List<OrderItem> findAllByOrderId(long orderId) {
        return jpa.findAllByOrderId(orderId).stream()
            .map(OrderItemJpaEntity::toDomain)
            .toList();
    }

    @Override
    public void saveAll(List<OrderItem> items) {
        Map<Long, OrderItemStatus> statusMap = items.stream()
            .collect(Collectors.toMap(OrderItem::getId, OrderItem::getStatus));
        List<OrderItemJpaEntity> entities = jpa.findAllById(statusMap.keySet());
        entities.forEach(e -> e.updateStatus(statusMap.get(e.getId())));
        jpa.saveAll(entities);
    }
}
```

```java
// order-service/src/main/java/com/example/order/infrastructure/persistence/ProcessedCancelEventRepositoryImpl.java
package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProcessedCancelEventRepositoryImpl implements ProcessedCancelEventRepository {

    private final ProcessedCancelEventJpaRepository jpa;

    @Override
    public boolean existsByCancelRequestId(String cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }

    @Override
    public void save(String cancelRequestId) {
        jpa.save(ProcessedCancelEventJpaEntity.of(cancelRequestId));
    }
}
```

- [ ] **Step 4: PersistenceConfig 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java
package com.example.order.infrastructure.config;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.service.ProcessCancelledItemsService;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.infrastructure.persistence.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.order.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public OrderRepository orderRepository(OrderJpaRepository jpa) {
        return new OrderRepositoryImpl(jpa);
    }

    @Bean
    public OrderItemRepository orderItemRepository(OrderItemJpaRepository jpa) {
        return new OrderItemRepositoryImpl(jpa);
    }

    @Bean
    public ProcessedCancelEventRepository processedCancelEventRepository(
        ProcessedCancelEventJpaRepository jpa) {
        return new ProcessedCancelEventRepositoryImpl(jpa);
    }

    @Bean
    public ProcessCancelledItemsUseCase processCancelledItemsUseCase(
        OrderRepository orderRepository,
        OrderItemRepository orderItemRepository,
        ProcessedCancelEventRepository processedCancelEventRepository,
        TransactionTemplate transactionTemplate) {
        return new ProcessCancelledItemsService(
            orderRepository, orderItemRepository, processedCancelEventRepository, transactionTemplate);
    }
}
```

- [ ] **Step 5: 커밋**

```bash
git add order-service/src/main/java/com/example/order/infrastructure/persistence/ \
        order-service/src/main/java/com/example/order/infrastructure/config/PersistenceConfig.java
git commit -m "feat(order): JPA 영속성 레이어 — 엔티티, 리포지토리, PersistenceConfig"
```

---

## Task 6: RetryRouter + DlqMessage (TDD)

**Files:**
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/DlqMessage.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/RetryRouter.java`
- Test: `order-service/src/test/java/com/example/order/infrastructure/messaging/RetryRouterTest.java`

- [ ] **Step 1: 실패하는 RetryRouter 테스트 작성**

```java
// order-service/src/test/java/com/example/order/infrastructure/messaging/RetryRouterTest.java
package com.example.order.infrastructure.messaging;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetryRouterTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private RetryRouter retryRouter;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        retryRouter = new RetryRouter(
            kafkaTemplate, new ObjectMapper(),
            "payment.cancelled.retry", "payment.cancelled.DLQ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_retry_when_transient_error_and_retry_count_below_3() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");

        retryRouter.route(record, new RuntimeException("DB timeout"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.retry");
        assertThat(headerValue(captor.getValue(), "retry-count")).isEqualTo("1");
        assertThat(headerValue(captor.getValue(), "original-topic")).isEqualTo("payment.cancelled");
        assertThat(headerValue(captor.getValue(), "first-failed-at")).isNotNull();
        assertThat(headerValue(captor.getValue(), "last-error")).contains("DB timeout");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_dlq_when_transient_error_and_retry_count_reaches_3() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled.retry", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");
        record.headers().add("retry-count", "3".getBytes(StandardCharsets.UTF_8));

        retryRouter.route(record, new RuntimeException("DB timeout"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.DLQ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_dlq_immediately_when_data_error() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");

        retryRouter.route(record, new OrderItemNotFoundException(List.of(99L)));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.DLQ");
    }

    private String headerValue(ProducerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.infrastructure.messaging.RetryRouterTest"
```

Expected: FAIL (클래스 없음)

- [ ] **Step 3: DlqMessage 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/messaging/DlqMessage.java
package com.example.order.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

public record DlqMessage(String originalMessage, DlqMeta dlqMeta) {

    public record DlqMeta(
        String originalTopic,
        int originalPartition,
        long originalOffset,
        int retryCount,
        String firstFailedAt,
        String lastFailedAt,
        String lastError,
        String movedToDlqAt
    ) {}

    public static DlqMessage of(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        Instant now = Instant.now();
        String firstFailedAt = headerValue(record, "first-failed-at").orElse(now.toString());
        return new DlqMessage(
            record.value(),
            new DlqMeta(
                record.topic(),
                record.partition(),
                record.offset(),
                retryCount,
                firstFailedAt,
                now.toString(),
                truncate(e.getMessage(), 200),
                now.toString()
            )
        );
    }

    private static Optional<String> headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return Optional.empty();
        return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 4: RetryRouter 구현**

```java
// order-service/src/main/java/com/example/order/infrastructure/messaging/RetryRouter.java
package com.example.order.infrastructure.messaging;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
public class RetryRouter {

    private static final int MAX_RETRY_COUNT = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String retryTopic;
    private final String dlqTopic;

    public RetryRouter(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                       String retryTopic, String dlqTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retryTopic = retryTopic;
        this.dlqTopic = dlqTopic;
    }

    public void route(ConsumerRecord<String, String> record, Exception e) {
        int retryCount = parseRetryCount(record);
        if (isDataError(e) || retryCount >= MAX_RETRY_COUNT) {
            publishToDlq(record, retryCount, e);
        } else {
            publishToRetry(record, retryCount, e);
        }
    }

    private boolean isDataError(Exception e) {
        return e instanceof OrderItemNotFoundException;
    }

    private void publishToRetry(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        int newRetryCount = retryCount + 1;
        Instant now = Instant.now();
        String firstFailedAt = retryCount == 0
            ? now.toString()
            : headerStringValue(record, "first-failed-at").orElse(now.toString());

        ProducerRecord<String, String> retryRecord = new ProducerRecord<>(retryTopic, record.key(), record.value());
        retryRecord.headers()
            .add("retry-count", String.valueOf(newRetryCount).getBytes(StandardCharsets.UTF_8))
            .add("next-retry-at", now.plus(retryDelay(newRetryCount)).toString().getBytes(StandardCharsets.UTF_8))
            .add("original-topic", record.topic().getBytes(StandardCharsets.UTF_8))
            .add("first-failed-at", firstFailedAt.getBytes(StandardCharsets.UTF_8))
            .add("last-error", truncate(e.getMessage(), 200).getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(retryRecord);
        log.warn("retry 토픽 발행. retryCount={}, offset={}", newRetryCount, record.offset());
    }

    private void publishToDlq(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        try {
            String dlqPayload = objectMapper.writeValueAsString(DlqMessage.of(record, retryCount, e));
            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, record.key(), dlqPayload);
            kafkaTemplate.send(dlqRecord);
            log.error("DLQ 발행. retryCount={}, offset={}, error={}", retryCount, record.offset(), e.getMessage());
        } catch (Exception ex) {
            log.error("DLQ 발행 실패. offset={}", record.offset(), ex);
        }
    }

    private int parseRetryCount(ConsumerRecord<String, String> record) {
        return headerStringValue(record, "retry-count").map(Integer::parseInt).orElse(0);
    }

    private Optional<String> headerStringValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return Optional.empty();
        return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private Duration retryDelay(int retryCount) {
        return switch (retryCount) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(10);
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
./gradlew :order-service:test --tests "com.example.order.infrastructure.messaging.RetryRouterTest"
```

Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add order-service/src/main/java/com/example/order/infrastructure/messaging/DlqMessage.java \
        order-service/src/main/java/com/example/order/infrastructure/messaging/RetryRouter.java \
        order-service/src/test/java/com/example/order/infrastructure/messaging/RetryRouterTest.java
git commit -m "feat(order): RetryRouter + DlqMessage TDD 구현"
```

---

## Task 7: Kafka Configs

**Files:**
- Create: `order-service/src/main/java/com/example/order/infrastructure/config/KafkaConsumerConfig.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/config/KafkaProducerConfig.java`

- [ ] **Step 1: KafkaProducerConfig 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/config/KafkaProducerConfig.java
package com.example.order.infrastructure.config;

import com.example.order.infrastructure.messaging.RetryRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public RetryRouter retryRouter(
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        @Value("${kafka.topic.payment-cancelled-retry}") String retryTopic,
        @Value("${kafka.topic.payment-cancelled-dlq}") String dlqTopic) {
        return new RetryRouter(kafkaTemplate, objectMapper, retryTopic, dlqTopic);
    }
}
```

- [ ] **Step 2: KafkaConsumerConfig 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/config/KafkaConsumerConfig.java
package com.example.order.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.retry-group-id}")
    private String retryGroupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, String> retryConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, retryGroupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> retryKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(retryConsumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
```

- [ ] **Step 3: 커밋**

```bash
git add order-service/src/main/java/com/example/order/infrastructure/config/KafkaConsumerConfig.java \
        order-service/src/main/java/com/example/order/infrastructure/config/KafkaProducerConfig.java
git commit -m "feat(order): KafkaConsumerConfig + KafkaProducerConfig"
```

---

## Task 8: Consumers

**Files:**
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledPayload.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumer.java`
- Create: `order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledRetryConsumer.java`

- [ ] **Step 1: PaymentCancelledPayload 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledPayload.java
package com.example.order.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.List;

public record PaymentCancelledPayload(
    String cancelRequestId,
    String paymentKey,
    long merchantId,
    List<CancelledItem> cancelledItems,
    String cancelledAt
) {
    public record CancelledItem(
        long paymentItemId,
        long orderItemId,
        BigDecimal itemAmount
    ) {}
}
```

- [ ] **Step 2: PaymentCancelledConsumer 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledConsumer.java
package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledConsumer {

    private final ProcessCancelledItemsUseCase processUseCase;
    private final RetryRouter retryRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);
            List<Long> orderItemIds = payload.cancelledItems().stream()
                .map(PaymentCancelledPayload.CancelledItem::orderItemId)
                .toList();

            processUseCase.execute(
                new ProcessCancelledItemsUseCase.Command(payload.cancelRequestId(), orderItemIds));

            log.info("payment.cancelled 처리 완료. cancelRequestId={}", payload.cancelRequestId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("payment.cancelled 처리 실패. offset={}", record.offset(), e);
            retryRouter.route(record, e);
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 3: PaymentCancelledRetryConsumer 작성**

```java
// order-service/src/main/java/com/example/order/infrastructure/messaging/PaymentCancelledRetryConsumer.java
package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledRetryConsumer {

    private final ProcessCancelledItemsUseCase processUseCase;
    private final RetryRouter retryRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled-retry}",
        groupId = "${spring.kafka.consumer.retry-group-id}",
        containerFactory = "retryKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);
            List<Long> orderItemIds = payload.cancelledItems().stream()
                .map(PaymentCancelledPayload.CancelledItem::orderItemId)
                .toList();

            processUseCase.execute(
                new ProcessCancelledItemsUseCase.Command(payload.cancelRequestId(), orderItemIds));

            log.info("payment.cancelled.retry 처리 완료. cancelRequestId={}", payload.cancelRequestId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("payment.cancelled.retry 처리 실패. offset={}", record.offset(), e);
            retryRouter.route(record, e);
            ack.acknowledge();
        }
    }
}
```

- [ ] **Step 4: 전체 테스트 통과 확인**

```bash
./gradlew :order-service:test
```

Expected: PASS (전체 테스트 통과)

- [ ] **Step 5: 최종 커밋**

```bash
git add order-service/src/main/java/com/example/order/infrastructure/messaging/
git commit -m "feat(order): Kafka Consumer 구현 완료 — payment.cancelled 멱등처리, 재시도, DLQ"
```
