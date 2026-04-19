## 11. 예상 면접 질문

### 설계 관련

1. 이 시스템에서 가장 어려웠던 부분은?
2. 분산 트랜잭션을 어떻게 처리했나요?
3. SAGA 패턴의 Choreography와 Orchestration 차이는?
4. Outbox Pattern의 단점과 보완 방법은?
5. 멱등성을 여러 레이어에서 보장한 이유는?
6. Circuit Breaker를 Fail-closed로 설정한 이유는?
7. 레이어를 왜 분리했나요? 도메인을 프레임워크와 분리한 이유는?

### 동시성 관련

8. 동시성 문제가 몇 가지 케이스로 발생하는지, 각각 어떻게 해결했는지?
9. Pessimistic Lock과 Optimistic Lock을 각각 어디에 사용했는지, 이유는?
10. FOR UPDATE 사용 시 데드락은 어떻게 방지했나요?
11. 선차감 방식을 선택한 이유는?

### HTTP 경계와 트랜잭션 관련

12. HTTP 요청으로 외부 서비스를 호출할 때 트랜잭션 원자성이 보장되나요?
13. risk-management-service 커밋 후 payment-service가 실패하면 어떻게 되나요?
14. 보상 트랜잭션도 멱등하게 설계한 이유는?

### Kafka 관련

15. Kafka와 RabbitMQ의 차이는?
16. At-least-once를 선택하고 Exactly-once를 선택하지 않은 이유는?
17. Kafka 순서 보장은 어떻게 하나요?
18. DLQ에 메시지가 쌓였을 때 처리 방법은?
19. offset 커밋을 수동으로 하는 이유는?

### DB 관련

20. DECIMAL과 FLOAT의 차이와 금융에서 FLOAT을 쓰면 안 되는 이유는?
21. DB를 모듈별로 분리한 이유와 단점은?
22. 스냅샷 방식의 장단점은?

### 장애 대응 관련

23. 서버가 재시작됐을 때 어떻게 복구하나요?
24. PROCESSING 재처리 시 이중 차감을 어떻게 방지하나요?
25. EXHAUSTED 상태가 발생하면 어떻게 처리하나요?


---

## 오늘 대화에서 나온 핵심 질문들

### DB 커넥션 풀

**Q. cancel 메서드에 @Transactional이 없는 이유는?**
```
@Transactional을 걸면 HTTP 호출 구간에도 DB 커넥션이 점유됩니다.
커넥션 풀이 고갈되면 다른 요청들이 커넥션을 얻지 못해 대기합니다.
TX를 3개로 분리해서 HTTP 호출 구간에는 커넥션을 풀에 반납합니다.
```

### 멱등성

**Q. Idempotency-Key를 서버가 생성하지 않고 클라이언트가 생성하는 이유는?**
```
서버가 생성하면 키 발급 API + 실제 요청 API 두 번 호출 필요합니다.
클라이언트가 UUID를 생성하면 재시도 시 같은 UUID를 재사용하고
새 요청 시 새 UUID를 생성합니다.
Stripe, Toss Payments 등 업계 표준입니다.
```

**Q. paymentHistory 기반 복합키로 하면 안 되나요?**
```
같은 PaymentItem에 대해 50만원 부분취소 후
추가 30만원 취소 시도 시 동일 키가 생성되어
정상 요청이 차단됩니다.
```

### 동시성

**Q. Payment FOR UPDATE를 쓰지 않고 낙관적 락을 쓴 이유는?**
```
동일 결제건 동시 요청은 매우 드문 케이스입니다.
모든 요청에 FOR UPDATE를 걸면 정상 케이스까지 직렬화됩니다.
다만 FOR UPDATE를 쓰면 risk, PG사 호출 전에 충돌을 감지할 수 있어
불필요한 HTTP 호출을 줄일 수 있습니다.
충돌이 잦은 환경에서는 FOR UPDATE 전환을 검토합니다.
```

### SAGA / 분산 트랜잭션

**Q. HTTP 경계에서 트랜잭션 원자성이 깨지는 케이스는?**
```
케이스 1: risk 호출 전 오류 → 둘 다 롤백 (문제 없음)
케이스 2: risk 내부 오류 → 둘 다 롤백 (문제 없음)
케이스 3: risk 성공 후 응답 유실 → risk만 커밋 → 보상 트랜잭션
케이스 4: risk 성공 후 payment 오류 → risk만 커밋 → 보상 트랜잭션
케이스 5: TX 3 완료 후 Kafka 발행 전 다운 → Outbox 스케줄러 처리
```

### PG사

**Q. PG사를 먼저 호출하고 DB를 나중에 하는 이유는?**
```
PG사 성공 + DB 실패: 환불됐고 DB만 맞추면 됩니다. TX 3 재시도 가능.
DB 성공 + PG사 실패: 시스템은 취소됐다고 표시됐는데 환불 안 됨. 고객 피해.
```

### Kafka

**Q. Exactly-once를 어떻게 달성했나요?**
```
At-least-once + Consumer 멱등성 조합입니다.
Outbox Pattern과 수동 offset 커밋으로 메시지 유실을 방지하고
processed_cancel_event UK로 중복 수신 시 한 번만 처리합니다.
Kafka Transactions는 트랜잭션 코디네이터, 2단계 커밋 오버헤드로 미채택했습니다.
```

**Q. 파티션 수를 늘리면 안 되나요?**
```
프로덕션에서 파티션을 늘리면 해시 기반 파티셔닝이 깨져서
Consumer 재할당(Rebalancing)이 발생하고 처리가 중단됩니다.
처음부터 파티션 10개로 여유있게 설정한 이유입니다.
```

### CancelRequest 상태

**Q. PENDING과 PROCESSING의 차이는?**
```
PENDING: risk 호출 전. 스케줄러가 처음부터 재처리 (risk 재호출 포함)
PROCESSING: risk 완료 후. 스케줄러가 TX 3만 재처리 (used_amount 재차감 금지)
```

### ShedLock

**Q. 분산 환경에서 스케줄러 중복 실행을 어떻게 방지하나요?**
```
ShedLock을 사용합니다. DB 행으로 분산 락을 구현해서
전체 클러스터에서 하나의 인스턴스만 실행되도록 보장합니다.
lockAtMostFor을 실행 주기보다 짧게 설정해서
인스턴스 다운 시 자동 해제되도록 합니다.
```

---

## 추가 질문 (오늘 대화 기반)

**Q. @Transactional(readOnly=true)를 붙이는 이유는?**
```
단일 MySQL 환경에서는 Dirty Checking 생략으로
미세한 성능 향상과 코드 가독성 이점이 있습니다.
실질적으로는 RDS 환경에서 Read Replica로 라우팅하기 위해 사용합니다.
현재는 단일 MySQL이라 RDS 도입 시 추가할 예정입니다.
```

**Q. 분산락이란 무엇이고 JPA 락과의 차이는?**
```
DB 트랜잭션 밖에서도 여러 인스턴스가
공유 자원이나 실행을 동시에 하나만 수행하도록 보장하는 락입니다.
JPA 락은 DB 트랜잭션 안에서만 유효하고
같은 DB를 공유하는 트랜잭션 간 제어가 목적입니다.
분산락은 스케줄러처럼 트랜잭션 밖에서
인스턴스 간 실행 자체를 제어할 때 필요합니다.

구현 방법:
  ShedLock: MySQL shedlock 테이블 (현재)
  Redis: Redisson tryLock
  Named Lock: MySQL GET_LOCK 내장 함수
```

**Q. DLQ가 무엇이고 언제 사용하는가?**
```
Dead Letter Queue로 처리 실패한 메시지를 격리하는 Kafka 토픽입니다.

즉시 DLQ: 데이터 오류, 역직렬화 실패 등 재시도해도 의미 없는 경우
재시도 후 DLQ: 일시적 오류가 3회 초과 실패한 경우

DLQ Retention을 30일로 설정한 이유는
원인 파악과 수동 처리에 충분한 시간이 필요하기 때문입니다.
```

**Q. FOR UPDATE 한계와 대안은?**
```
같은 가맹점에 요청이 집중되면 직렬화로 처리량이 제한됩니다.

대안:
  Redis 분산 카운터: 원자적 INCRBY, 락 없이 빠름
    단, 금융 데이터를 Redis에 두는 위험
  가맹점별 인스턴스 라우팅: 인스턴스 10대 이상에서 검토
    인스턴스 다운 시 해당 가맹점 전체 실패 위험

현재 TPS 100, 가맹점이 분산되면 FOR UPDATE로 충분합니다.
```

**Q. TPS가 증가하면 어떻게 확장하는가?**
```
TPS 1000: Read Replica + Redis 도입
TPS 5000+: merchantId 기반 DB 샤딩
TPS 10000+: CDC(Debezium) + CQRS

병목 발생 순서:
  1. merchant_cancel_usage FOR UPDATE
  2. Outbox 스케줄러 처리량
  3. payment DB 쓰기
  4. Kafka Consumer Lag
```

---

## 멱등성 설계 심화 Q&A

**Q. 클라이언트 UUID 방식의 한계는?**
```
UUID가 다른 동일 요청은 새 요청으로 처리됩니다.
재시도 시 UUID를 재사용하면 멱등하지만
UUID가 달라지면 PaymentItem 잔여액 검증에서 422 에러가 발생합니다.
기존 성공 응답을 반환하지 못하므로 완벽한 멱등성이 아닙니다.
```

**Q. request_hash 방식으로 어떻게 개선했나?**
```
paymentKey + cancelItems(paymentItemId 정렬)를 SHA-256으로 해시해서
서버가 직접 요청 내용을 식별합니다.
UUID가 달라도 같은 내용이면 같은 hash → 기존 응답 반환
Idempotency-Key 헤더를 제거해서 클라이언트 구현을 단순화했습니다.
```

**Q. cancelItems 정렬이 왜 필요한가?**
```
순서가 다른 동일 내용의 요청이 다른 hash를 생성할 수 있습니다.
paymentItemId 기준으로 정렬 후 해시를 생성해서
순서에 무관하게 동일한 hash를 보장합니다.
```

**Q. 동시에 같은 요청이 2번 들어오면?**
```
cancel_request 테이블에 (payment_id, request_hash) Unique Key를 추가합니다.
TX 1 INSERT 시 하나만 성공하고 하나는 UK 충돌로 실패합니다.
실패한 쪽은 기존 CancelRequest를 조회해서 상태에 따라 응답합니다.
```

**Q. FAILED 상태인 요청이 재시도되면?**
```
FAILED = 취소가 실제로 안 된 상태이므로 재시도를 허용합니다.
COMPLETED이면 기존 응답을 반환하고
PENDING/PROCESSING이면 처리 중 응답을 반환합니다.
```

---

## 분산락 심화 Q&A

**Q. Redisson과 Lettuce 기반 분산락의 차이는?**
```
Redisson은 분산락 전용 기능을 내장하고 있습니다.
Lua 스크립트로 락 획득/해제의 원자성을 보장합니다.
Lettuce는 Spring Data Redis 기본 클라이언트로
직접 스핀락을 구현해야 합니다.
스핀락은 락 획득 실패 시 계속 재시도하므로
CPU를 낭비할 수 있습니다.
실무에서는 Redisson을 권장합니다.
```

**Q. 스핀락이란?**
```
락 획득 실패 시 일정 간격으로 계속 재시도하는 방식입니다.
"빙글빙글 돌면서 기다린다"는 의미입니다.
재시도 간격이 짧으면 CPU를 낭비하고
길면 불필요한 지연이 발생합니다.
```

**Q. Consistent Hashing이란?**
```
원형 링(0~2^32) 위에 샤드를 배치하고
데이터를 해시해서 시계 방향으로 가장 가까운 샤드에 저장합니다.
샤드 추가 시 인접 구간의 데이터만 재배치되고
나머지는 그대로 유지됩니다.
일반 해싱(N % 샤드수)은 샤드 추가 시 대부분이 재배치되는 문제가 있어
프로덕션에서 사실상 사용 불가합니다.
```
