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

**2단계: request_hash 기반 (개선된 설계)**

```
paymentKey + cancelItems(paymentItemId 정렬) → SHA-256 해시

UUID가 달라도 같은 내용이면 같은 hash
→ 기존 COMPLETED CancelRequest 조회
→ 기존 응답 반환
→ 완벽한 멱등성 달성
```

### 1-2. 취약점 분석 및 해결

| 케이스 | 취약점 | 해결 |
|--------|--------|------|
| UUID 다른 동일 요청 | 새 요청으로 처리 | request_hash로 기존 건 조회 |
| 동시 요청 | hash 조회 동시 통과 | cancel_request (payment_id, request_hash) UK |
| PROCESSING 중 재요청 | COMPLETED만 체크 | PENDING/PROCESSING도 차단 |
| cancelItems 순서 다름 | hash 달라짐 | paymentItemId 정렬 후 해시 |

### 1-3. FAILED 처리

```
FAILED = 취소 안 된 상태
→ 재시도 허용 (신규 처리)

COMPLETED = 취소 완료
→ 기존 응답 반환

PENDING/PROCESSING = 처리 중
→ "처리 중" 응답 반환
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
  ADD COLUMN request_hash VARCHAR(64) NULL,
  ADD UNIQUE KEY uk_cancel_request_hash (payment_id, request_hash);
```

### 1-6. cancel_request에 통합 가능한가

```
idempotency_key 별도 테이블 → 제거
cancel_request.request_hash로 통합

이유:
  request_hash가 cancel_request의 고유 식별자 역할
  별도 테이블 불필요
  단, 다른 API(결제, 환불)에도 멱등성 필요하면
  별도 테이블 고려
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

## 6. daily_limit 당일 즉시 반영

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

## 8. ShedLock vs Redis 분산락

| | ShedLock (현재) | Redis 분산락 |
|--|-------------|------------|
| 인프라 | MySQL (이미 있음) | Redis 필요 |
| 장애 시 | MySQL 장애 = 스케줄러 의미 없음 | Redis 장애 = 스케줄러 중단 |
| 전환 | RedisLockProvider 교체 지원 | - |

**선택:** ShedLock — Redis 미도입 시 추가 인프라 없이 동일 효과

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
