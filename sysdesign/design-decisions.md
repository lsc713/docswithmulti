# 설계 결정 고민 — 대안 분석 및 트레이드오프

> 이 문서는 설계 과정에서 고민했던 대안들과 트레이드오프를 기록합니다.

---

## 1. idempotency_key 테이블 설계

### 1-1. 멱등성 설계 변천

**1단계: 클라이언트 UUID (초기 설계)**

```
문제:
  UUID가 다른 동일 요청은 새 요청으로 처리
  → 완벽한 멱등성 보장 불가

예시:
  1차 요청: uuid-aaaa, 상품A 30만원 취소 → 성공
  2차 요청: uuid-bbbb, 상품A 30만원 취소 (UUID 다름)
  → 새 요청으로 처리 → PaymentItem 잔여액 0 → 422 에러
  → 기존 성공 응답이 아닌 에러 반환 → 멱등성 위반
```

### 1-1. 멱등성 설계 변천

**1단계: 클라이언트 UUID (초기 설계)**

```
문제:
  UUID가 다른 동일 요청은 새 요청으로 처리
  → 완벽한 멱등성 보장 불가
```

**2단계: request_hash 기반 (개선된 설계)**

```
hash = SHA-256(paymentKey + paymentItemIds 오름차순 정렬)

아이템 단위 전액 취소만 가능:
  cancelAmount 불필요 (항상 item_amount 전액)
  cancelledAmount 불필요 (ACTIVE/CANCELLED 상태로만 구분)
  paymentItemId만으로 동일 요청 식별 가능

예시:
  A(30만), B(50만) 아이템 취소:
    hash = SHA-256(paymentKey + "A,B")
  재시도: 동일 hash → 멱등 처리
```

**hash 생성 코드:**

```java
// PaymentItem을 DB에서 정렬해서 조회
List<PaymentItem> items = paymentItemRepository
    .findAllByPaymentIdOrderByIdAsc(payment.getId());

Map<Long, PaymentItem> itemMap = items.stream()
    .collect(Collectors.toMap(PaymentItem::getId, i -> i));

// cancelItems는 paymentItemId 정렬
String requestHash = sha256(
    paymentKey +
    cancelItems.stream()
        .sorted(Comparator.comparing(CancelItemRequest::paymentItemId))
        .map(item -> {
            PaymentItem pi = itemMap.get(item.paymentItemId());
            return item.paymentItemId()
                + ":" + item.cancelAmount()
                + ":" + pi.getCancelledAmount();
        })
        .collect(Collectors.joining(","))
);
```

**DB 정렬 활용:**
```
PaymentItem을 DB에서 ORDER BY id ASC로 가져옴
→ index 활용, 추가 정렬 비용 없음
cancelItems만 Service에서 정렬
```

**한계:**
```
멱등키 생성이 DB 상태에 의존
Payment, PaymentItem 조회 후에만 생성 가능
→ 어쩔 수 없는 최선
  (어차피 검증 목적으로 필요한 조회를 활용)
```

### 1-2. 취약점 분석 및 해결

| 케이스 | 취약점 | 해결 |
|--------|--------|------|
| UUID 다른 동일 요청 | 새 요청으로 처리 | request_hash로 기존 건 조회 |
| 동시 요청 | hash 조회 동시 통과 | cancel_request (payment_id, request_hash) UK |
| PROCESSING 중 재요청 | COMPLETED만 체크 | PENDING/PROCESSING도 차단 |
| cancelItems 순서 다름 | hash 달라짐 | paymentItemId 정렬 후 해시 |

### 1-3. FAILED 처리 및 재시도

```
FAILED = 취소가 실제로 안 된 상태 → 재시도 허용

재시도 시 처리:
  request_hash로 cancel_request 조회 → FAILED 발견
  새 INSERT 불가 (UK 충돌)
  → 기존 FAILED 건을 PENDING으로 UPDATE
  → 이후 정상 플로우 재진행

FAILED → PENDING UPDATE 선택 이유:
  DELETE + 새 INSERT:
    DELETE 실패 시 UK 충돌로 재시도 불가
    이력 유실 위험
  PENDING으로 UPDATE:
    UK 충돌 없음
    DELETE 없음 → 실패 걱정 없음
    이력은 cancel_request_history에 보존
```

### 1-4. cancel_request_history 테이블

```sql
CREATE TABLE cancel_request_history (
    id                BIGINT      PRIMARY KEY AUTO_INCREMENT,
    cancel_request_id BIGINT      NOT NULL,
    status            VARCHAR(20) NOT NULL,
    reason            VARCHAR(500) NULL,
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_cancel_request_history_cancel_request_id (cancel_request_id)
);
```

```
상태 변경 시마다 INSERT:
  PENDING 생성
  PROCESSING 변경
  FAILED 변경 (사유 포함)
  FAILED → PENDING 재시도
  COMPLETED 완료
```

### 1-4. Idempotency-Key 헤더 제거

```
request_hash가 UUID 역할을 대체
클라이언트가 UUID 생성/관리 불필요
API 단순화

PG사와의 관계:
  PG사: CancelRequest.id를 cancelKey로 활용
  우리: request_hash로 멱등성 보장
  둘이 독립적으로 동작
```

### 1-5. cancel_request 테이블 변경

```sql
ALTER TABLE cancel_request
  ADD COLUMN request_hash VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_cancel_request_hash (payment_id, request_hash);

-- idempotency_key 테이블 제거
DROP TABLE idempotency_key;
```

### 1-6. TX 3에서 제거된 것

```
기존 TX 3:
  PaymentItem 변경
  Payment 변경
  CancelRequest COMPLETED
  Outbox INSERT
  idempotency_key 저장 ← 제거

변경 후 TX 3:
  PaymentItem 변경
  Payment 변경
  CancelRequest COMPLETED
  Outbox INSERT

이유:
  idempotency_key 테이블 제거
  재시도 시 cancel_request COMPLETED 조회 후 응답 생성
  response_body 별도 저장 불필요
```

### 1-7. 재시도 시 응답 생성

```
cancel_request COMPLETED 건:
  cancel_request 데이터로 응답 직접 생성
  cancelAmount, status, completedAt 등 이미 보유
  → response_body 저장 불필요
```

---

## 2. TX 2 취약점 — PENDING 재처리 이중 차감

### 2-1. 문제

```
TX 2 (PENDING → PROCESSING) 실패 시
CancelRequest가 PENDING으로 남지만
used_amount는 이미 차감된 상태

스케줄러가 PENDING 재처리 시:
  PENDING = "risk 호출 전"으로 인식
  → risk 재호출 → 이중 차감
```

### 2-2. 대안

| 방법 | 설명 | 채택 |
|------|------|------|
| cancelRequestId 중복 체크 | risk 서버에서 이미 처리한 건 skip | ✓ |
| TX 2를 TX 1에 합치기 | PENDING 삽입 시 바로 PROCESSING | 설계 의미 파괴 |
| TX 2 단순화 | 단순 UPDATE라 실패 가능성 낮음 | 보조 수단 |

**선택:** cancelRequestId를 validateAndReserveLimit 파라미터로 유지
cancel_usage_history UK로 이중 차감 방어

---

## 3. PG사 pending 처리 전략

```
PG사가 장기간 pending인 경우:
  자동화 한계 인정
  pg_pending_since 컬럼으로 최초 감지 시각 기록
  1시간 초과 시 FAILED + 보상 + 운영팀 알림
  운영팀이 PG사와 직접 소통 후 수동 보정
```

---

## 4. Payment FOR UPDATE vs 낙관적 락

```
문제:
  2번 클릭이 거의 동시에 들어오면
  둘 다 risk, PG사까지 호출 후
  TX 3에서야 충돌 감지 → 비효율

Payment FOR UPDATE 대안:
  Payment 조회 시점에 직렬화
  risk, PG사 호출 전에 차단 가능

선택: 낙관적 락 유지
  동일 결제건 동시 요청은 매우 드문 케이스
  정상 케이스 처리량 우선
  충돌 잦은 환경에서 FOR UPDATE 전환 검토 가능
```

---

## 5. Outbox Pattern 대안

| 방법 | 지연 | 복잡도 | 채택 |
|------|------|--------|------|
| Outbox + 스케줄러 (현재) | 최대 10초 | 낮음 | ✓ |
| CDC (Debezium) | 수ms | 높음 | 수신 서비스 늘어나면 검토 |
| Kafka Transactions | 수ms | 매우 높음 | - |
| Dual Write | - | 낮음 | 원자성 미보장 → 불가 |

```
Outbox 선택 이유:
  추가 인프라 없음
  10초 지연 허용 가능
  단순하고 검증된 패턴
```

---

## 6. Kafka 페이로드 설계 원칙

```
풍부한 페이로드 지향:
  Consumer가 API 조회 없이 처리 가능
  Producer 서버 장애와 무관하게 처리
  새 서비스가 consume해도 페이로드 변경 불필요

예외 — 대용량 데이터 (수십KB, 이미지 포함):
  페이로드에 직접 포함 금지
  S3 링크 또는 최소 식별자만 포함
  Consumer가 필요 시 S3 또는 API로 조회
  예시: { "receiptUrl": "s3://bucket/receipt/cr_abc123.pdf" }

payment.cancelled 페이로드:
  cancelRequestId, paymentKey, merchantId
  cancelledItems (paymentItemId, orderItemId, itemAmount)
  cancelledAt
  → 모든 Consumer가 API 조회 없이 처리 가능

merchant.limit.updated 페이로드:
  merchantId, newLimit, kstDate
  → 파티션 키 merchantId로 순서 보장
  → kstDate: 당일 외 이전 날짜 한도 변경도 가능
  → 자연 멱등 (UPDATE daily_limit = newLimit WHERE kst_date = kstDate)
```

---

## 7. daily_limit 당일 즉시 반영

```
요구사항 변경: 가맹점 한도 변경 시 당일 즉시 반영 필요

채택: Kafka 이벤트
  merchant-limit-service → merchant.limit.updated 발행
  risk-management-service가 consume
  → Redis 갱신 + merchant_cancel_usage 업데이트

선택 이유:
  Redis 직접 접근 → 서비스 간 강한 결합
  캐시 무효화 API → 서비스 간 동기 의존
  Kafka → 이미 인프라 존재, 느슨한 결합

페이로드:
  { "merchantId": 1, "newLimit": 3000000 }

파티션 키 = merchantId:
  같은 가맹점 연속 한도 변경 시 순서 보장
  updatedAt, version 비교 불필요

Consumer 멱등성:
  UPDATE daily_limit = newLimit
  → 몇 번 실행해도 동일한 결과 (자연 멱등)
```

---

## 7. Exactly-once 달성 방법

| 방법 | 복잡도 | 채택 |
|------|--------|------|
| At-least-once + Consumer UK (현재) | 낮음 | ✓ |
| Kafka Transactions | 높음 | - |
| Idempotent Producer만 | 낮음 | 부분 적용 |
| Kafka Streams | 매우 높음 | - |

---

## 8. ShedLock → Redis 분산락 전환

**분산락이 필요한 케이스:**

```
1. DB 락 자체가 부하일 때
   TPS 높아서 DB 커넥션을 아끼고 싶을 때
   FOR UPDATE → DB 커넥션 점유 시간 증가
   분산락 → DB 접근 전에 Redis로 차단
   → DB 커넥션 절약

   여러 DB 샤드를 활용할 때
   샤드 간 FOR UPDATE 불가
   분산락으로 샤드 무관하게 직렬화

2. 데이터가 없는 경우
   UK는 행이 존재해야 충돌 감지 가능
   행이 없는 신규 INSERT 동시 요청:
     둘 다 "없음" 확인 → 둘 다 INSERT 시도
     UK로 하나 차단 (에러 발생)
   분산락:
     lock 획득 후 INSERT
     → UK 에러 없이 깔끔하게 차단

3. Master-Slave 정합성 보장
   분산락 보유 중 코드에서 명시적으로 Master 라우팅
   Replication lag으로 인한 오래된 값 읽기 방지
   (분산락이 자동으로 해주는 게 아님
    분산락 + 라우팅 로직 함께 구현 필요)

4. 주문-재고 동시 요청 보상 비용 절감
   분산락 없이:
     주문 A, B 동시 생성
     이후 재고 부족 확인
     → 한 명은 보상 트랜잭션 (주문 취소)
     → API 호출 2번 비용 + 보상 복잡도

   분산락 있으면:
     하나씩 처리
     재고 부족이면 주문 자체를 막음
     → 보상 트랜잭션 불필요
     → 이런 충돌이 자주 발생하는 케이스에서 유리

5. 분산 스케줄러 중복 실행 방지
   여러 인스턴스에서 스케줄러 동시 실행 차단
   → ShedLock → Redis 분산락으로 전환
```

**비교:**

| | ShedLock | Redis 분산락 |
|--|-------------|------------|
| 인프라 | MySQL (이미 있음) | Redis 필요 |
| 장애 시 | MySQL 장애 = 스케줄러 의미 없음 | Redis 장애 = 스케줄러 중단 |
| DB 샤딩 시 | shedlock 테이블 분리 필요 | 영향 없음 |
| DB 부하 | 스케줄러 실행마다 DB 접근 | Redis 접근 (더 빠름) |

**전환 결정:**

```
Redis 도입 시점에 ShedLock → Redis 분산락으로 전환

이유:
  Redis 이미 도입 → 추가 인프라 없음
  DB 샤딩 시 shedlock 테이블 분리 문제 방지
  AWS ElastiCache Multi-AZ로 고가용성 자동 보장
  전환 비용 매우 낮음 (코드 3줄 + yml 설정)
```

**분산락 한계 — 단일 실패 지점:**

```
Redis 단일 노드:
  Redis 다운 → 락 획득 불가
  → 스케줄러 중단
  → TPS 증가로 분산락 도입 시 결제 취소도 영향

해결 방법:

Redlock (Redis 다중 노드):
  5대 중 3대 이상에서 락 획득 시 유효
  1대 다운돼도 나머지로 동작
  한계:
    Clock Drift: 노드 시계 차이로 TTL 만료 시점 불일치
    GC Pause: JVM GC 동안 TTL 만료
              → 두 클라이언트 동시 락 보유 가능
    Martin Kleppmann이 안전하지 않다고 비판

AWS ElastiCache Multi-AZ (채택):
  Primary 장애 시 자동 failover
  Replica가 Primary로 승격
  Redlock보다 단순하고 현실적
  → 단일 실패 지점 해소

Fencing Token (보완책):
  락 획득 시 단조 증가 토큰 발급
  DB 쓰기 시 토큰 검증 → 이전 토큰이면 거부
  GC Pause, Clock Drift 문제 보완
  구현 복잡도 있음
```

**분산락 실패 시 최후 방어선:**

```
분산락이 실패해도:
  cancel_request (payment_id, request_hash) UK
  PaymentItem 상태 검증
  → 비즈니스 로직에서 최종 차단
  → 분산락은 "앞단 최적화", UK가 최종 보장
```

**확장성 관점 — 분산락이 필요한 시점:**

```
단일 DB (현재):
  UK, FOR UPDATE로 충분
  분산락 불필요 (스케줄러 제외)

DB 샤딩 시 (TPS 5000+):
  cancel_request UK → 샤드 간 보장 안 됨
  → 분산락으로 전환 필요
  merchant_cancel_usage FOR UPDATE → 샤드 간 불가
  → 분산락으로 전환 필요
```

**전환 방법:**

```gradle
implementation 'net.javacrumbs.shedlock:shedlock-provider-redisson'
implementation 'org.redisson:redisson-spring-boot-starter:3.24.3'
```

```java
@Bean
public LockProvider lockProvider(RedissonClient redissonClient) {
    return new RedissonLockProvider(redissonClient);
}
// RedissonClient, RedissonLockProvider는 라이브러리가 구현
// 스케줄러 코드 변경 없음
```

```sql
-- V9__drop_shedlock_table.sql
DROP TABLE IF EXISTS shedlock;
```

---

## 9. FOR UPDATE 한계와 대안

```
문제:
  같은 가맹점에 TPS가 집중되면
  merchant_cancel_usage FOR UPDATE 직렬화
  → 가맹점당 처리량 제한

대안 비교:

Redis 분산 카운터:
  INCRBY used_amount:merchantId 원자적 연산
  락 없이 빠름
  단점: 금융 데이터를 Redis에, 장애 시 정합성 문제

가맹점별 인스턴스 라우팅 (Consistent Hashing):
  같은 가맹점 → 같은 인스턴스
  인스턴스 내 synchronized로 처리
  단점: 인스턴스 다운 시 해당 가맹점 전체 실패
        인스턴스 2~3대에서는 리스크 큼
        인스턴스 10대 이상에서 현실적

낙관적 락:
  한도 초과 케이스에서 재시도해도 동일하게 실패
  → 채택 불가

결론:
  TPS 100, 인스턴스 2대 수준: FOR UPDATE 유지
  트래픽 집중 대형 가맹점 발생 시: Redis 분산 카운터 검토
  인스턴스 10대+: 가맹점별 라우팅 검토
```

**확장성 관점 — FOR UPDATE → 분산락 전환 필요:**

```
DB 샤딩 시 FOR UPDATE의 한계:
  merchantId 기준 샤딩
  같은 가맹점은 같은 샤드 → FOR UPDATE 유지 가능

  근데 여러 DB 샤드에 걸친 동시성 제어가 필요하면:
  → 샤드 간 FOR UPDATE 불가
  → 데드락 위험

  예시:
    유저 1: 샤드 1 락 획득 → 샤드 2 락 대기
    유저 2: 샤드 2 락 획득 → 샤드 1 락 대기
    → 데드락

  분산락으로 전환:
    lock("merchant:" + merchantId + ":" + kstDate)
    Redis 하나로 전체 직렬화
    DB 샤드 수와 무관

분산락 전환 시 추가 위험:
  FOR UPDATE: 락과 커밋이 원자적 → 서버 다운 시 자동 롤백
  분산락: 락 해제와 커밋이 별개 → 서버 다운 시 정합성 위험
  → 보상 트랜잭션으로 보완 (compensation_retry 구조 활용)

전환 시점:
  TPS 1000: Redis 도입 → ShedLock만 분산락 전환
  TPS 5000+: DB 샤딩 → FOR UPDATE도 분산락으로 전환 필수
  전환 용이: TPS 1000에 Redis 이미 도입됨
```

---

## 10. TPS 증가 시 확장 전략

```
TPS 100 (현재):
  단일 MySQL
  Outbox 배치 크기 100 → 1000으로 조정 필요
  인스턴스 2대

TPS 1000:
  Read Replica 도입 (@Transactional(readOnly=true))
  Redis 도입 (daily_limit 캐시)
  ShedLock → Redis 분산락 전환 검토
  Outbox 스케줄러 주기 단축

TPS 5000+:
  merchantId 기반 DB 샤딩
  ShedLock → Redis 분산락 전환 (shedlock 전용 DB 분리)
  가맹점별 인스턴스 라우팅 검토 (Consistent Hashing)

TPS 10000+:
  CDC (Debezium) 도입 → Outbox 스케줄러 대체
  CQRS (읽기/쓰기 분리)
  Kafka 파티션 수 조정 (새 토픽으로 마이그레이션)

병목 발생 순서:
  1. merchant_cancel_usage FOR UPDATE (가맹점 집중 시)
  2. Outbox 스케줄러 처리량 한계
  3. payment DB 쓰기 부하
  4. Kafka Consumer Lag
```

---

## 11. DLQ 설계

```
즉시 DLQ (재시도 불필요):
  데이터 오류 (OrderItem not found)
  역직렬화 실패
  → 재시도해도 동일하게 실패

재시도 후 DLQ (3회 초과):
  일시적 오류 (DB 타임아웃 등)
  → retry 토픽 지수 백오프
  → 3회 초과 → DLQ

DLQ Retention 30일:
  일반 토픽 7일 대비 긴 이유
  원인 파악 + 수동 처리 시간 필요

운영팀 처리:
  데이터 오류 → 수동 보정 후 재발행
  코드 오류 → 배포 후 재발행
  재발행 불가 → 폐기 + 수동 보정
```

---

## 12. DB 샤딩 + Consistent Hashing

### 12-1. 일반 해싱의 문제

```
샤드 4개: merchantId % 4
샤드 5개로 증가: merchantId % 5

대부분의 merchantId가 다른 샤드로 재배치
→ 대규모 데이터 마이그레이션 필요
→ 서비스 중단 위험
→ 프로덕션에서 사실상 불가
```

### 12-2. Consistent Hashing

```
원형 링 (0 ~ 2^32) 위에 샤드 배치
merchantId → hash → 링 위 숫자
→ 시계 방향으로 가장 가까운 샤드에 저장

샤드 추가 시:
  추가된 샤드 인접 구간만 재배치
  나머지는 그대로

예시:
  샤드1(10억), 샤드2(20억), 샤드3(30억), 샤드4(40억)

  샤드5(15억) 추가:
    10억~15억 → 샤드2 그대로
    15억~20억 → 샤드5로 이동 (전체의 1/5)
    나머지 구간 변화 없음
```

### 12-3. 가상 노드

```
샤드가 적으면 데이터 불균등 분배 가능
각 샤드에 여러 가상 노드를 링에 배치
→ 균등 분배 보장
```

### 12-4. 샤딩 라이브러리 비교

| | ShardingSphere | ProxySQL | Vitess |
|--|-------------|---------|--------|
| 위치 | 애플리케이션 | DB 앞단 | DB 앞단 |
| 언어 | Java 전용 | 무관 | 무관 |
| 복잡도 | 낮음 | 중간 | 높음 |
| 규모 | 중소 | 중대 | 대규모 |
| 인프라 추가 | 없음 | 별도 서버 | 별도 클러스터 |
| 채택 | TPS 5000 수준 | TPS 10000+ | 유튜브급 |

### 12-5. ShedLock과 샤딩

```
DB 샤딩 시 shedlock 테이블이 각 샤드에 존재
→ 인스턴스마다 다른 샤드의 shedlock을 바라볼 수 있음
→ 분산락 의미 없어짐

해결:
  shedlock 전용 별도 DB (샤딩 대상 제외)
  또는 Redis 분산락으로 전환
```

---

## 13. 분산락 구현 방법 상세

### 13-1. Redisson 기반 (권장)

```java
RLock lock = redissonClient.getLock("lock:key");
try {
    boolean acquired = lock.tryLock(0, 55, TimeUnit.SECONDS);
    if (!acquired) return;  // 다른 인스턴스 실행 중
    doWork();
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();  // 즉시 해제
    }
}
```

```
내부 동작:
  SET lock:key {uuid} NX PX 55000
  락 해제: Lua 스크립트로 원자적 처리
    내가 건 락인지 확인 후 삭제

장점:
  분산락 전용 기능 내장
  Lua 스크립트로 원자성 보장
  구현 단순

단점:
  Redisson 의존성 추가
```

### 13-2. Lettuce 기반 스핀락

```java
// Spring Data Redis 기본 클라이언트로 직접 구현
while (true) {
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent("lock:key", "value", 55, TimeUnit.SECONDS);

    if (Boolean.TRUE.equals(acquired)) break;

    Thread.sleep(100);  // 100ms 대기 후 재시도
}
try {
    doWork();
} finally {
    redisTemplate.delete("lock:key");
}
```

```
스핀락: 락 획득 실패 시 계속 재시도
  "빙글빙글 돌면서 기다린다"는 의미

문제:
  락 획득할 때까지 CPU 계속 사용
  재시도 간격 짧으면 CPU 낭비
  재시도 간격 길면 불필요한 지연
  락 해제 안전성 직접 구현 필요
```

### 13-3. 비교표

| | Redisson | Lettuce 스핀락 | ShedLock | Named Lock |
|--|---------|-------------|---------|-----------|
| 구현 복잡도 | 낮음 | 높음 | 매우 낮음 | 낮음 |
| CPU 효율 | 좋음 | 나쁨 | 좋음 | 좋음 |
| 추가 인프라 | Redis | Redis | 없음 | 없음 |
| 원자성 보장 | Lua 스크립트 | 직접 구현 | DB UPDATE | DB 세션 |
| 채택 | Redis 도입 시 권장 | 비권장 | 현재 | - |

---

## 14. cancel_request_history 트랜잭션 처리

```
이력 테이블의 역할:
  감사(audit), 추적 목적
  비즈니스 로직에 영향 없음

FAILED + 이력 → 트랜잭션으로 묶음:
  FAILED 상태와 이력이 항상 일치해야 함
  FAILED인데 이력 없으면 원인 추적 불가
  → 원자적으로 처리

COMPLETED + 이력 → 트랜잭션 밖으로 분리:
  이력 저장 실패로 취소 전체 롤백되면
  비즈니스 로직(실제 취소)을 희생하는 것
  → 잘못된 설계

  TX 3 커밋 후 별도로 이력 INSERT
  이력 실패해도 취소는 완료
  스케줄러 재처리 시 이력도 함께 기록됨
```
