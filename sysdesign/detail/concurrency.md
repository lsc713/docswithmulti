## 7. 락 전략 심화

### 7-1. 락 종류 비교

| 락 종류 | 범위 | 유지 시간 | 용도 |
|---------|------|---------|------|
| UK 제약 | 단일 DB, INSERT 중복 | 트랜잭션 | 중복 행 방어 |
| Row Lock (FOR UPDATE) | 단일 DB, 특정 행 | 트랜잭션 커밋까지 | 읽기-수정-쓰기 원자성 |
| 낙관적 락 (version) | 단일 DB | 없음 (충돌 감지만) | 쓰기 충돌 감지 후 재시도 |
| 분산락 | 여러 인스턴스 | 명시적 TTL | 인스턴스 간 실행 제어 |

### 7-2. 분산락이 필요한 이유

```
UK / Row Lock / 낙관적 락:
  단일 DB 트랜잭션 내에서만 유효
  인스턴스 A의 TX와 인스턴스 B의 TX는 서로 다른 연결
  → 두 인스턴스의 실행 순서를 제어할 수 없음

분산락이 필요한 상황:
  스케줄러가 여러 인스턴스에서 동시에 실행될 때
  "전체 클러스터에서 단 하나의 인스턴스만 실행"을 보장해야 할 때
```

**스케줄러 중복 실행 문제:**

```
인스턴스 A, B, C 모두 60초마다 복구 스케줄러 실행

A: PROCESSING 건 조회 → cancelRequestId=1 발견
B: PROCESSING 건 조회 → cancelRequestId=1 발견 (동시)
C: PROCESSING 건 조회 → cancelRequestId=1 발견 (동시)

A, B, C 모두 cancelRequestId=1 재처리 시도
→ 보상 트랜잭션 3번 실행
→ Outbox 중복 발행
→ Kafka 메시지 3번 발행
```

### 7-3. ShedLock 동작 원리

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until DATETIME(3)  NOT NULL,
    locked_at  DATETIME(3)  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

```
스케줄러 실행 시:
  1. shedlock 테이블에서 name="cancel-recovery" 행 조회
  2. lock_until이 현재 시각보다 미래이면 → 이미 다른 인스턴스 실행 중
     → 실행 skip
  3. lock_until이 과거이면 (또는 행 없으면) → 락 획득
     → lock_until = NOW + 55초, locked_by = 현재 인스턴스
     → 스케줄러 실행
  4. 실행 완료 후 lock_until 업데이트 (자동 해제)
```

```java
@Scheduled(fixedDelay = 60_000)
@SchedulerLock(name = "cancel-recovery", lockAtMostFor = "55s")
public void recover() {
    // 이 메서드는 전체 클러스터에서 동시에 하나만 실행
}
```

```
lockAtMostFor = "55s":
  실행 중 인스턴스가 다운되면
  최대 55초 후 다른 인스턴스가 락 획득 가능
  (인스턴스 다운 감지 후 자동 복구)

실행 주기(60초) > lockAtMostFor(55초):
  정상 실행 완료 후 락이 해제됨
  다음 주기에 다시 락 획득 가능
```

### 7-4. ShedLock 대안 비교

| 방법 | 원리 | 장점 | 단점 | 적합한 경우 |
|------|------|------|------|-----------|
| ShedLock (현재) | DB 행으로 분산 락 | 추가 인프라 없음, 구현 단순 | DB 부하 증가, 락 세밀도 낮음 | MySQL 이미 사용 중, 초기 단계 |
| Redis 분산락 | SET NX PX + Lua | 빠름, TTL 자동 만료, 세밀한 제어 | Redis 장애 시 스케줄러 중단, 추가 인프라 | Redis 이미 도입됨, TPS 높은 경우 |
| Redlock | Redis 다중 노드 과반수 획득 | 단일 Redis 장애에도 락 유지 | 구현 복잡도 높음 | 고가용성 필수 환경 |
| Quartz Cluster | 전용 스케줄러 DB | 기능 풍부, 모니터링 용이, 재시도 내장 | 별도 인프라, 러닝커브 높음 | 스케줄러 기능이 복잡한 경우 |
| 단일 인스턴스 분리 | 스케줄러 전용 인스턴스 | 가장 단순, 충돌 없음 | 해당 인스턴스 다운 시 스케줄 중단 | 스케줄러 중단 허용 가능한 경우 |

**ShedLock 선택 이유:**
```
이미 MySQL을 쓰고 있어서 추가 인프라 없음
@SchedulerLock 어노테이션 하나로 적용
Redis 장애와 무관하게 스케줄러 동작 보장

MySQL 장애 시 ShedLock도 동작 안 하지만:
  어차피 MySQL 장애면 스케줄러 자체가 의미 없음
  (재처리할 CancelRequest 조회 자체가 불가)
  → MySQL 의존성이 단점이 아님
```

**Redis 도입 후 전환 검토 시:**
```
RedisLockProvider로 교체 가능 (ShedLock이 provider 교체 지원)
코드 변경 없이 의존성과 설정만 변경
```

### 7-5. Redis 분산락 vs ShedLock 상세 비교

**ShedLock (DB 기반):**

```
락 획득:
  UPDATE shedlock
  SET lock_until = NOW() + 55초,
      locked_at  = NOW(),
      locked_by  = 'instanceA'
  WHERE name = 'cancel-recovery'
  AND lock_until < NOW()

  affected rows = 1 → 락 획득 성공
  affected rows = 0 → 다른 인스턴스가 보유 중 → skip

원자성: DB UPDATE의 원자성으로 보장
장애: MySQL 장애 시 락 획득 불가 → 스케줄러 중단
      (어차피 MySQL 장애면 스케줄러 의미 없음)
```

**Redis 분산락:**

```
락 획득:
  SET lock:cancel-recovery {instanceA_uuid} NX PX 55000
  NX: 키 없을 때만 SET (원자적)
  PX: 밀리초 TTL

  성공 → 락 획득
  실패 → 다른 인스턴스가 보유 중 → skip

락 해제 시 주의:
  내가 건 락인지 확인 후 삭제해야 함
  TTL 만료 후 다른 인스턴스가 락을 가졌을 수 있음
  → Lua 스크립트로 확인 + 삭제를 원자적으로 수행

  if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
  else
    return 0
  end

장애: Redis 장애 시 락 획득 불가 → 스케줄러 전체 중단
      Redis Cluster 구성으로 가용성 높일 수 있음
```

**Redis 분산락 구현 코드 (Redisson 활용):**

```java
// Redis 분산락으로 스케줄러 보호
@Component
@RequiredArgsConstructor
public class CancelRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final CancelRecoveryService cancelRecoveryService;

    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        RLock lock = redissonClient.getLock("lock:cancel-recovery");

        // 락 획득 시도 (대기 0초, 55초 후 자동 만료)
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
            if (!acquired) {
                return;  // 다른 인스턴스가 실행 중 → skip
            }
            // 스케줄러 로직 실행
            doRecover();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

```
ShedLock vs Redis 분산락 선택 기준:

Redis 미도입 → ShedLock (추가 인프라 없음)
Redis 도입됨 → Redis 분산락 검토
  장점: 더 빠름, TTL 자동 만료, 락 상태 모니터링 쉬움
  단점: Redis 장애 = 스케줄러 중단

금융 시스템에서 스케줄러 중단은 치명적
→ Redis Cluster + Sentinel 구성으로 가용성 확보 필요
→ 운영 복잡도 증가
→ 현재 단계에서 ShedLock이 더 현실적
```

**Redlock (다중 노드 Redis):**

```
Redis 노드 N개 중 N/2+1개에서 락 획득해야 유효
단일 Redis 장애에도 락 유지

예시 (Redis 노드 5개):
  3개 이상에서 락 획득 → 유효
  2개 이하에서만 획득 → 실패로 간주

단점:
  구현 복잡도 높음
  네트워크 지연으로 락 유효성 판단이 어려움
  Martin Kleppmann이 안전하지 않다고 비판한 논쟁 있음
→ 현재 규모에서 과잉
```

### 7-6. 우리 시스템 락 전체 정리

```
Record Lock (FOR UPDATE):
  merchant_cancel_usage — 가맹점 한도 동시 차감 방어

낙관적 락 (version):
  payment_item — 동일 항목 동시 수정 방어

UK 제약:
  idempotency_key — 중복 요청 방어
  cancel_usage_compensation — 중복 보상 방어
  processed_cancel_event — Kafka 중복 처리 방어
  cancel_event_outbox (cancel_request_id UK) — 중복 Outbox INSERT 방어

ShedLock:
  cancel-recovery 스케줄러
  outbox-publisher 스케줄러
  compensation-retry 스케줄러
```

---

## 8. PG사 성공 후 Outbox INSERT 실패 케이스

### 8-1. 이 케이스가 별도 케이스인가

아니야. **PG사 성공 + TX 3 실패** 케이스와 동일한 흐름이야.

```
TX 3 안의 처리 순서:
  1. PaymentItem 상태 변경
  2. Payment 상태 변경
  3. CancelRequest → COMPLETED
  4. cancel_event_outbox INSERT  ← 여기서 실패
  5. idempotency_key 저장

4번에서 실패하면 TX 3 전체 롤백
→ 1, 2, 3, 4, 5 모두 롤백
→ CancelRequest는 PROCESSING 상태로 남음 (TX 2에서 커밋됨)
→ PG사는 이미 취소 완료됨

= PG사 성공 + TX 3 실패와 완전히 동일한 상황
→ 복구 스케줄러가 PROCESSING 5분 초과 감지
→ PG사 결과 조회 → 성공 확인 → TX 3 재시도
```

### 8-2. Outbox INSERT가 실패하는 원인

```
원인 1: cancel_request_id UK 충돌
  이미 Outbox 행이 존재
  → 이전 TX 3 시도에서 Outbox만 INSERT 됐다가 이후 롤백이 안 된 경우
  → 실제로는 발생하지 않음 (TX 3 전체가 원자적으로 롤백되기 때문)
  → UK 충돌이 발생했다면 이미 Outbox가 있다는 의미
     → Outbox 스케줄러가 발행할 것 → 오히려 정상

원인 2: DB 용량 부족, 디스크 장애
  더 근본적인 문제
  Outbox INSERT뿐 아니라 모든 DB 쓰기 실패
  → 운영 차원의 대응 필요
```

### 8-3. 대안 — Outbox INSERT 실패를 별도로 처리해야 하는가

**대안 1 — 현재 설계: TX 3 롤백 후 스케줄러에 위임**

```
장점:
  별도 처리 로직 없음
  TX 3이 멱등하므로 재시도 안전
  스케줄러가 일관되게 처리

단점:
  최대 5분 지연 (스케줄러 감지 시간)
  그 사이 Kafka 이벤트 발행 안 됨
  → order-service가 취소 완료를 모름
```

**대안 2 — Outbox INSERT만 별도 재시도**

```java
@Transactional
private CancelPaymentResponse completeCancel(...) {
    paymentItemRepository.saveAll(updatedItems);
    paymentRepository.save(payment);
    cancelRequestRepository.save(cancelRequest.toCompleted());
    idempotencyKeyManager.save(idempotencyKey, response);
    // Outbox INSERT는 TX 밖에서 별도 처리
}

// TX 커밋 후
try {
    outboxRepository.save(CancelEventOutbox.of(cancelRequest, payment));
} catch (Exception e) {
    // INSERT 실패 시 보정 스케줄러 또는 별도 retry
    outboxRetryRepository.save(...);
}
```

```
문제:
  Outbox INSERT를 TX 밖으로 꺼내면
  PaymentItem 변경 커밋 후 Outbox INSERT 전 서버 다운 시
  이벤트가 영원히 발행 안 됨
  → Outbox Pattern의 핵심 보장을 깨뜨림

  Outbox INSERT가 TX 안에 있어야
  "PaymentItem 변경과 이벤트가 원자적으로 기록됨"이 보장됨
```

**대안 3 — CDC (Debezium)**

```
payment_item 테이블 변경을 binlog로 감지
→ 직접 Kafka로 발행
→ Outbox 테이블 자체가 불필요

장점:
  Outbox INSERT 실패 문제 자체가 없어짐
  DB 변경 → Kafka 발행이 자동

단점:
  Debezium 인프라 추가 필요
  binlog 설정, CDC 파이프라인 운영 복잡도
  현재 규모에서 과잉
```

### 8-4. 현재 설계를 선택한 이유

```
Outbox INSERT는 TX 3의 마지막 단계
TX 3 자체가 멱등하게 설계됨
  → 실패 시 스케줄러가 TX 3 전체를 안전하게 재시도 가능

cancel_event_outbox UK (cancel_request_id):
  TX 3 재시도 시 Outbox INSERT 중복 방어
  이미 있으면 INSERT 실패 → no-op → TX 3 계속 진행

결론:
  Outbox INSERT 실패를 별도로 처리하는 것보다
  TX 3 전체를 멱등하게 재시도하는 것이 더 단순하고 안전
  모든 실패 케이스를 스케줄러 하나로 일관되게 처리
```

**TX 3 멱등성 보장 방법 (재확인):**

| 작업 | 멱등 보장 수단 |
|------|-------------|
| PaymentItem 변경 | version 낙관적 락, 이미 반영됐으면 확인 후 skip |
| Payment 상태 변경 | PaymentItem 합산 결과 → 항상 동일한 결과 |
| CancelRequest COMPLETED | 이미 COMPLETED면 변경 없음 |
| Outbox INSERT | cancel_request_id UK → 중복 시 no-op |
| idempotency_key 저장 | idem_key UK → 중복 시 no-op |

---

