# order-service 설계 스펙

**날짜**: 2026-04-24  
**범위**: payment.cancelled Kafka Consumer + OrderItem/Order 상태 동기화

---

## 1. 구현 범위

- `payment.cancelled` Kafka Consumer (멱등 처리 + 재시도 + DLQ)
- `payment.cancelled.retry` Kafka Consumer (재시도)
- Order / OrderItem 최소 스키마 및 도메인 엔티티 (Kafka Consumer 처리에 필요한 것만)
- `processed_cancel_event` 멱등성 테이블
- 단위 테스트

**범위 외**: 주문 생성/조회 HTTP API, Order 외 도메인 기능

---

## 2. 스키마 (V1__create_order_core.sql)

```sql
order (id, status, created_at, updated_at)
order_item (id, order_id, status, created_at, updated_at)
processed_cancel_event (id, cancel_request_id UK, processed_at)
```

### order.status enum 값
`PENDING`, `PAYMENT_VERIFYING`, `PAID`, `DELIVERY_WAITING`, `DELIVERING`,
`DELIVERED`, `CONFIRMED`, `PARTIAL_CANCELLED`, `CANCELLED`

### order_item.status enum 값
`ACTIVE`, `CANCELLED`

### 인덱스
- `processed_cancel_event.cancel_request_id` — UNIQUE KEY (멱등 방어)
- `order_item.order_id` — INDEX (Order별 전체 아이템 조회)

---

## 3. 레이어 구조

### domain/entity
| 클래스 | 책임 |
|--------|------|
| `Order` | id, status, `cancel()`, `partialCancel()` — 순수 Java |
| `OrderItem` | id, orderId, status, `cancel()` — 순수 Java |
| `OrderStatus` | enum (전체 상태값) |
| `OrderItemStatus` | enum (`ACTIVE`, `CANCELLED`) |

### application
| 클래스 | 책임 |
|--------|------|
| `ProcessCancelledItemsUseCase` | Command(cancelRequestId, cancelledOrderItemIds) 인터페이스 |
| `ProcessCancelledItemsService` | TX 경계 소유, 멱등 체크 → 상태 변경 → 기록 |
| `OrderRepository` | `findById()`, `findByIdForUpdate()` |
| `OrderItemRepository` | `findAllByIdIn()`, `findAllByOrderId()` |
| `ProcessedCancelEventRepository` | `existsByCancelRequestId()`, `save()` |

### infrastructure/messaging
| 클래스 | 책임 |
|--------|------|
| `PaymentCancelledConsumer` | `payment.cancelled` 구독 (group: `order-service`) |
| `PaymentCancelledRetryConsumer` | `payment.cancelled.retry` 구독 (group: `order-service-retry`) |
| `RetryRouter` | 헤더 파싱, retry-count 기반 retry 토픽 / DLQ 발행 결정 |
| `PaymentCancelledPayload` | 이벤트 역직렬화 DTO |

### infrastructure/config
- `KafkaConsumerConfig` — 메인 / retry 두 개 ConsumerFactory + ListenerContainerFactory
- `KafkaProducerConfig` — retry / DLQ 발행용 KafkaTemplate

---

## 4. Consumer 처리 흐름

### 정상 경로 (PaymentCancelledConsumer)

```
1. cancelRequestId로 processed_cancel_event 조회
   → 존재하면 no-op + ack (멱등 처리)

2. cancelledItems의 orderItemId 목록으로 OrderItem 일괄 조회
   → 하나라도 없으면 OrderItemNotFoundException → 데이터 오류 → DLQ → ack

3. TX:
   a. OrderItem.cancel() — 각 아이템 CANCELLED
   b. 해당 Order의 전체 OrderItem 조회
      전부 CANCELLED → Order.cancel()         → status = CANCELLED
      일부 ACTIVE    → Order.partialCancel()   → status = PARTIAL_CANCELLED
   c. processed_cancel_event INSERT

4. ack
```

### 실패 경로 (RetryRouter)

```
retry-count 헤더 조회 (없으면 0)

데이터 오류 (OrderItemNotFoundException 등):
  → DLQ 발행 → ack  (재시도해도 해결 안 됨)

일시적 오류 (DB 타임아웃 등):
  retry-count < 3  → payment.cancelled.retry 발행
                     헤더: retry-count+1, last-error, original-topic, first-failed-at
                     → ack
  retry-count >= 3 → DLQ 발행 → ack
```

### DLQ 메시지 포맷

```json
{
  "originalMessage": { },
  "dlqMeta": {
    "originalTopic": "payment.cancelled",
    "originalPartition": 3,
    "originalOffset": 1024,
    "retryCount": 3,
    "firstFailedAt": "2026-04-24T10:00:00Z",
    "lastFailedAt": "2026-04-24T10:20:00Z",
    "lastError": "OrderItem not found: orderItemId=99",
    "movedToDlqAt": "2026-04-24T10:20:05Z"
  }
}
```

### Retry Consumer (PaymentCancelledRetryConsumer)

- `ProcessCancelledItemsUseCase` 동일 호출
- retry-count는 헤더에 이미 존재 → `RetryRouter` 동일 사용
- `next-retry-at` 헤더는 모니터링/관찰 용도로만 기록, 실제 대기 없이 즉시 처리 시도
- `first-failed-at` 헤더: retry-count=0→1 전환 시(최초 실패 시) 현재 시각 기록, 이후 재시도 시 유지

---

## 5. TX 경계

```
TX 1 (ProcessCancelledItemsService):
  OrderItem 상태 변경 (bulk)
  Order 상태 재계산
  processed_cancel_event INSERT

TX 밖:
  offset ack (TX 커밋 후)
  retry/DLQ 발행 (실패 시)
```

processed_cancel_event INSERT가 UK 충돌(DataIntegrityViolationException) 나면
이미 처리된 것으로 간주 → 정상 처리로 ack.

---

## 6. Kafka 설정

### Consumer (메인)
```properties
group.id=order-service
enable.auto.commit=false
isolation.level=read_committed
max.poll.records=100
ack-mode=MANUAL_IMMEDIATE
```

### Consumer (retry)
```properties
group.id=order-service-retry
enable.auto.commit=false
max.poll.records=50
ack-mode=MANUAL_IMMEDIATE
```

### Producer (retry/DLQ 발행)
```properties
acks=all
enable.idempotence=true
retries=Integer.MAX_VALUE
```

---

## 7. 단위 테스트 범위

### ProcessCancelledItemsServiceTest (application, Mockito)
- `should_do_nothing_when_cancel_request_already_processed` — 멱등 no-op
- `should_cancel_order_when_all_items_cancelled` — 전체 취소 → Order CANCELLED
- `should_partial_cancel_order_when_some_items_remain_active` — 부분 취소 → PARTIAL_CANCELLED
- `should_throw_when_order_item_not_found` — 데이터 오류 예외

### RetryRouterTest (infrastructure, 순수 Java)
- `should_publish_to_retry_topic_when_transient_error_and_retry_count_below_3`
- `should_publish_to_dlq_when_transient_error_and_retry_count_reaches_3`
- `should_publish_to_dlq_immediately_when_data_error`

### OrderTest / OrderItemTest (domain, 순수 Java)
- `should_become_cancelled_when_cancel_called`
- `should_become_partial_cancelled_when_partial_cancel_called`
- `should_throw_when_invalid_status_transition`

---

## 8. 완료 기준

- [ ] 테스트 전체 통과
- [ ] domain 레이어에 Spring/JPA 어노테이션 없음
- [ ] processed_cancel_event UK 멱등 방어 동작
- [ ] 3회 실패 시 DLQ 발행 확인
- [ ] offset ack는 TX 커밋 이후에만 실행
