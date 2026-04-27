# Payment Service 스케줄러 설계

날짜: 2026-04-27  
대상 모듈: `payment-service`  
참조: `sysdesign/cancel-design.md` 섹션 8

---

## 1. 개요

취소 플로우의 복구 및 비동기 발행을 담당하는 스케줄러 4개를 payment-service에 추가한다.  
분산락은 Redisson `RLock`을 직접 사용한다 (ShedLock 미사용).

| 스케줄러 | 주기 | 락 TTL | 역할 |
|---------|------|--------|------|
| OutboxPublisherScheduler | 10초 | 9초 | Outbox PENDING → Kafka 발행 |
| PendingRecoveryScheduler | 60초 | 55초 | CancelRequest PENDING 5분 초과 복구 |
| ProcessingRecoveryScheduler | 60초 | 55초 | CancelRequest PROCESSING 5분 초과 복구 |
| CompensationRetryScheduler | 30초 | 25초 | 보상 재시도 |

---

## 2. 분산락 전략

ShedLock 미사용. Redisson `RLock.tryLock()` 직접 사용.

```java
RLock lock = redissonClient.getLock("lock:scheduler:outbox-publisher");
if (!lock.tryLock(0, 9, TimeUnit.SECONDS)) return;
try {
    service.doWork();
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

- `tryLock(waitTime=0, leaseTime=TTL)` — 즉시 획득 실패 시 skip
- leaseTime = 주기보다 1초 짧게 설정 (이전 인스턴스 크래시 시 자동 해제)
- Redis 키: `lock:scheduler:{name}`

기존 `shedlock` 테이블(V6 migration)은 사용하지 않음.

---

## 3. 신규 파일 목록

### 추가

| 파일 | 레이어 | 설명 |
|------|--------|------|
| `infrastructure/config/SchedulerConfig.java` | infrastructure | `@EnableScheduling`, `@EnableAsync` |
| `infrastructure/scheduler/OutboxPublisherScheduler.java` | infrastructure | 10초, 락 → Service 위임 |
| `infrastructure/scheduler/PendingRecoveryScheduler.java` | infrastructure | 60초, 골격 |
| `infrastructure/scheduler/ProcessingRecoveryScheduler.java` | infrastructure | 60초, 골격 |
| `infrastructure/scheduler/CompensationRetryScheduler.java` | infrastructure | 30초, 골격 |
| `infrastructure/messaging/KafkaOutboxPublisher.java` | infrastructure | KafkaTemplate 래핑 |
| `application/service/OutboxPublisherService.java` | application | Outbox 조회 → 발행 → PUBLISHED |

### 변경

| 파일 | 변경 내용 |
|------|---------|
| `payment-service/build.gradle` | `spring-kafka`, `redisson-spring-boot-starter` 추가 |
| `application.yml` | Kafka producer, Redis, 락 키 설정 추가 |
| `application/interfaces/CancelEventOutboxRepository.java` | `findPendingBatch(int limit)` 추가 |
| `infrastructure/persistence/CancelEventOutboxJpaRepository.java` | `findTop1000ByStatusOrderByCreatedAtAsc()` 추가 |
| `infrastructure/persistence/CancelEventOutboxRepositoryImpl.java` | `findPendingBatch()` 구현 추가 |

---

## 4. OutboxPublisher 상세 설계

### OutboxPublisherService

```
1. outboxRepository.findPendingBatch(1000) 호출
2. 건별로 kafkaOutboxPublisher.publish(payload) 호출
3. 성공 → outboxJpaRepository.markPublished(cancelRequestId)
4. 실패 → log.error + 해당 건 skip (다음 주기 재시도)
```

- 배치 크기 1000: TPS 100 기준 10초에 1000건 발생, 충분
- 발행 실패 건은 PENDING 유지 → 다음 주기 자동 재시도
- 부분 실패 허용: 한 건 실패가 전체를 막으면 안 됨

### KafkaOutboxPublisher

```java
kafkaTemplate.send(topic, cancelRequestId.toString(), payload)
```

- topic: `payment.cancelled`
- key: `cancelRequestId` (String)
- value: Outbox에 저장된 JSON payload 그대로 발행
- producer 설정: `acks=all`, `enable-idempotence=true`

---

## 5. 나머지 3개 스케줄러 — 골격

비즈니스 로직은 TODO. 구조만 확립.

### PendingRecoveryScheduler

```
대상: CancelRequest PENDING, createdAt < now - 5분
처리:
  TODO: risk checkCharge API 호출
  TODO: charged=true → compensate → FAILED
  TODO: charged=false → FAILED
```

### ProcessingRecoveryScheduler

```
대상: CancelRequest PROCESSING, updatedAt < now - 5분  
처리:
  TODO: PG사 조회 API 호출
  TODO: 성공 → TX 3 재실행
  TODO: 실패(재시도 가능) → PG사 재호출
  TODO: 실패(재시도 불가) → compensate → FAILED
  TODO: pending 1시간 초과 → compensate → FAILED → 운영팀 알림
```

### CompensationRetryScheduler

```
대상: compensation_retry, next_retry_at <= now, status != DONE
처리:
  TODO: risk compensate API 재호출
  TODO: 성공 → DONE
  TODO: 실패 → incrementAttempt + 지수 백오프 스케줄
  TODO: 최대 횟수 초과 → EXHAUSTED + 운영팀 알림
```

---

## 6. 인터페이스 변경

### CancelEventOutboxRepository

```java
// 추가
List<CancelEventOutboxJpaEntity> findPendingBatch(int limit);
```

---

## 7. 의존성 추가

```groovy
// payment-service/build.gradle
implementation 'org.springframework.kafka:spring-kafka'
implementation 'org.redisson:redisson-spring-boot-starter:3.27.2'
```

---

## 8. 설정 추가 (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      enable-idempotence: true
  data:
    redis:
      host: localhost
      port: 6379

kafka:
  topic:
    payment-cancelled: payment.cancelled

scheduler:
  lock:
    outbox-publisher: lock:scheduler:outbox-publisher
    pending-recovery: lock:scheduler:pending-recovery
    processing-recovery: lock:scheduler:processing-recovery
    compensation-retry: lock:scheduler:compensation-retry
```

---

## 9. 테스트 범위

**OutboxPublisherService 단위 테스트만** (Mockito):

| 케이스 | 검증 |
|--------|------|
| PENDING 건 존재 시 | Kafka 발행 + `markPublished` 호출 |
| Kafka 발행 실패 시 | 해당 건 skip, 나머지 계속 처리 |
| PENDING 건 없음 | 아무 동작 없음 |

`OutboxPublisherScheduler` 테스트 제외 — 락 로직은 인프라 레이어.  
나머지 3개 스케줄러 테스트 제외 — 골격 구현이므로 테스트 불필요.

---

## 10. 미구현 항목 (추후)

- `PendingRecoveryScheduler` 비즈니스 로직 — `RiskManagementPort.checkCharge()` 추가 필요
- `ProcessingRecoveryScheduler` 비즈니스 로직 — `PgCancelPort.getStatus()` 추가 필요
- `CompensationRetryScheduler` 비즈니스 로직 — `CompensationRetryRepository.findDue()` 추가 필요
- TPS 1000+ 시 CDC(Debezium) 전환 검토
