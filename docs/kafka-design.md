# Kafka design

Kafka를 처음 도입하는 입장에서 개념부터 설계 결정까지 담았다.
"왜 이렇게 설정했는가"를 이해하면 운영 중 문제 발생 시 판단이 빠르다.

---

## 1. 핵심 개념

### 브로커, 토픽, 파티션의 관계

```
Kafka 클러스터
└── 브로커 3대 (물리 서버)
    └── 토픽: payment.cancelled (논리 단위)
        └── 파티션 10개 (메시지가 실제 저장되는 단위)
            └── 각 파티션은 3대 브로커에 복제됨
```

브로커는 물리 서버, 파티션은 그 위에 분산 배치되는 논리 단위다.
같은 개념이 아니다.

### 파티션이 브로커에 분산되는 방식

```
파티션 10개 × replication factor 3 = 복제본 30개가 3개 브로커에 분산

브로커 1: P0(Leader), P1(Follower), P2(Follower), P3(Leader) ...
브로커 2: P0(Follower), P1(Leader), P2(Follower), P4(Leader) ...
브로커 3: P0(Follower), P1(Follower), P2(Leader), P5(Leader) ...

Leader: 읽기/쓰기를 실제로 처리하는 파티션
Follower: Leader를 복제만 함. 평소엔 요청 처리 안 함
```

브로커 1대 장애 시 해당 파티션 Leader를 Follower 중 하나가 자동 승계한다.
replication factor=3이면 브로커 2대 장애까지 버틸 수 있다.

### 순서 보장

Kafka는 **파티션 내에서만** 순서를 보장한다.
파티션이 다르면 순서가 섞일 수 있다.

```
파티션 키가 같으면 → 항상 같은 파티션 → 순서 보장
파티션 키가 다르면 → 다른 파티션 가능 → 순서 보장 안 됨

payment_key를 파티션 키로 사용:
  pay_abc → hash → 파티션 3번 (항상)
  pay_xyz → hash → 파티션 7번 (항상)

같은 결제건의 이벤트는 항상 같은 파티션 → 순서 보장
다른 결제건은 다른 파티션으로 분산 → 병렬 처리
```

### Consumer Group

```
주문 모듈 인스턴스 3개 (Consumer Group: order-service)

인스턴스 1 → 파티션 0, 1, 2, 3 담당
인스턴스 2 → 파티션 4, 5, 6    담당
인스턴스 3 → 파티션 7, 8, 9    담당
```

Consumer 인스턴스 수가 파티션 수를 초과하면 초과 인스턴스는 유휴 상태다.
TPS가 늘면 Consumer 인스턴스를 추가하면 되지만
파티션 수가 상한이 된다.

파티션 수를 나중에 늘리면 파티션 키 → 파티션 매핑이 바뀌어
순서 보장이 깨질 수 있다.
**파티션 수는 처음에 넉넉하게 설정하는 이유다.**

### Offset

Kafka는 메시지를 소비해도 삭제하지 않는다.
Consumer가 "어디까지 읽었는지"를 offset으로 관리한다.

```
파티션 3번: [msg0] [msg1] [msg2] [msg3] [msg4]
                                  ↑
                            committed offset = 2
                            다음 읽을 위치 = 3
```

`enable.auto.commit=false`로 설정하면
처리 완료 후 수동으로 offset을 커밋한다.
처리 전 커밋하면 서버 다운 시 메시지 유실이 발생한다.

### At-least-once와 멱등성

```
At-most-once (재처리 없음):
  메시지를 최대 1번만 처리.
  장애 시 메시지 유실 가능.
  → 취소 이벤트 유실 = 주문 상태 미동기화
  → 금융 도메인에서 절대 사용 불가

At-least-once (우리가 선택):
  메시지를 최소 1번 처리.
  장애 시 재처리 가능 → 중복 처리 위험.
  → 애플리케이션 레벨 멱등성으로 해결
    (processed_cancel_event 테이블 UK 제약)
  → 중복 수신해도 실제로는 한 번만 처리됨

Exactly-once:
  정확히 1번만 처리.
  Kafka 트랜잭션 기능 필요.
  성능 오버헤드가 크고 구현 복잡도가 높다.
  → 현재 규모에서 불필요

결론:
  "재처리 자체를 막는 것"보다
  "재처리해도 안전한 구조"가 현실적이고 충분하다.
```

---

## 2. 토픽 설계

### 토픽 목록

| 토픽 | 파티션 수 | Replication Factor | Retention | 파티션 키 | 용도 |
|------|---------|-------------------|-----------|---------|------|
| `payment.cancelled` | 10 | 3 | 7일 | paymentKey | 취소 완료 이벤트 |
| `payment.cancelled.retry` | 10 | 3 | 7일 | paymentKey | Consumer 실패 재시도 |
| `payment.cancelled.DLQ` | 3 | 3 | 30일 | - | 3회 초과 실패 격리 |
| `merchant.limit.updated` | 3 | 3 | 7일 | merchantId | 가맹점 일일 한도 변경 이벤트 |

### 파티션 수 결정 근거

```
현재 TPS: 100
목표 TPS: 10,000

파티션 1개당 처리량: 약 1,000 TPS (보수적 추정)
필요 파티션 수: 10,000 / 1,000 = 10개

파티션 수는 늘리기 어렵기 때문에 목표 TPS 기준으로 설정.
```

### Retention 설정 근거

```
payment.cancelled: 7일
  → 재처리(processing-recovery) + Consumer 지연 감안
  → 7일이면 충분한 재처리 여유

payment.cancelled.DLQ: 30일
  → 수동 처리 대기 시간 고려
  → 원인 분석 + 코드 수정 + 재처리까지 30일 여유
```

---

## 3. 이벤트 스키마

### 직렬화 방식 결정

```
선택지:
  String/JSON  → 사람이 읽을 수 있음, 스키마 강제 없음
  Avro         → 스키마 강제, 용량 작음, Schema Registry 필요
  Protobuf     → Avro와 유사, Google 표준

우리가 선택: String/JSON
이유:
  - Kafka 처음 도입하는 단계에서 Schema Registry 추가 인프라 부담
  - 현재 TPS(100~10,000)에서 JSON 용량이 문제될 수준 아님
  - Kafka UI에서 메시지를 바로 읽을 수 있어 디버깅 편함
  - 스키마 변경은 이 문서의 버전 관리 원칙으로 대응

Key:   String (payment_key)
Value: String (JSON 직렬화)
```

### payment.cancelled 이벤트

```json
{
  "cancelRequestId": "cr_abc123",
  "paymentKey": "pay_xyz",
  "merchantId": 1,
  "cancelledItems": [
    {
      "paymentItemId": 1,
      "orderItemId": 10,
      "itemAmount": 300000
    }
  ],
  "cancelledAt": "2026-04-21T10:00:00.000Z"
}
```

| 필드 | 설명 |
|------|------|
| `cancelRequestId` | Consumer 멱등키로 사용 (processed_cancel_event UK) |
| `paymentKey` | 어떤 결제건인지 |
| `merchantId` | 가맹점 구분 |
| `cancelledItems[].paymentItemId` | payment-service 기준 아이템 식별자 |
| `cancelledItems[].orderItemId` | order-service가 자기 DB에서 OrderItem 찾기 위해 필요 |
| `cancelledItems[].itemAmount` | 취소된 금액 |
| `cancelledAt` | 취소 완료 시각 (UTC) |

> **풍부한 페이로드 원칙**: Consumer가 API 조회 없이 처리 가능하도록 필요한 정보를 페이로드에 포함한다.
> 대용량 데이터(수십KB, 이미지)는 포함 금지. 대신 S3 링크 또는 최소 식별자만 포함.

### merchant.limit.updated 이벤트

```json
{
  "merchantId": 1
}
```

| 필드 | 설명 |
|------|------|
| `merchantId` | 가맹점 ID (파티션 키 겸 유일 필드) |

Consumer (risk-management-service):
- `GET /internal/merchants/{merchantId}/cancel-limit` 조회 → 최신 `daily_limit` 수신
- Redis `daily_limit:merchantId:kstDate` 갱신
- `merchant_cancel_usage` 당일 행이 있으면 `daily_limit` UPDATE

**newLimit / kstDate를 페이로드에서 제거한 이유:**

```
이벤트에 값을 포함하면 Consumer가 stale 값을 캐시에 저장할 위험:
  한도 A → B → C 연속 변경 시
  B 이벤트가 C 이벤트보다 늦게 처리되면
  Redis에 B(구 값)가 남을 수 있음

{ merchantId }만 발행 + API 조회:
  Consumer는 항상 최신 값을 가져옴
  자연 멱등 (같은 merchantId를 여러 번 조회해도 동일한 결과)
```

### 스키마 버전 관리 원칙

```
필드 추가: 하위 호환 가능 → 바로 배포 가능
필드 제거: 하위 호환 불가 → Consumer 먼저 배포 후 Producer 변경
필드 타입 변경: 하위 호환 불가 → 신규 필드 추가 후 구버전 제거 순서
```

---

## 4. Kafka 헤더 설계

메시지 본문은 수정하지 않는다.
재시도 이력은 헤더에 담아 원본 이벤트 무결성을 유지한다.

| 헤더 키 | 값 예시 | 설명 |
|---------|---------|------|
| `retry-count` | `2` | 현재 재시도 횟수 |
| `next-retry-at` | `2026-04-13T10:05:00Z` | 다음 재시도 시각 (UTC) |
| `original-topic` | `payment.cancelled` | 원본 토픽 |
| `first-failed-at` | `2026-04-13T10:00:00Z` | 최초 실패 시각 |
| `last-error` | `Connection timeout` | 마지막 에러 메시지 |

---

## 5. Producer 설계

### acks 옵션 이해

```
acks=0:
  Producer가 브로커에 메시지를 보내고 확인하지 않는다.
  → 가장 빠름
  → 브로커가 받았는지조차 모름 → 유실 가능
  → 로그 수집 등 유실이 허용되는 곳에만 사용

acks=1:
  Leader 브로커에 저장되면 성공으로 간주한다.
  → Leader가 Follower에 복제하기 전 다운되면
     메시지는 유실됨
  → 그 사이 시간이 매우 짧지만 금융에서는 허용 불가

acks=all (우리가 선택):
  Leader + ISR(동기화된 Follower) 전체에 저장되면 성공.
  ISR = In-Sync Replica (Leader와 동기화 상태인 복제본)
  → Leader가 다운돼도 Follower에 이미 복제됨 → 유실 없음
  → 약간 느리지만 취소 이벤트 유실은 주문 미동기화로 이어지므로 필수
```

### OUTBOX 정식 발행 (`cancel.publish.mode` 기본값)

TX3는 DB 상태 변경과 **같은 트랜잭션**으로 `cancel_event_outbox`에 PENDING 행을 INSERT한다. Kafka 발행 자체는 TX 밖 — 별도 폴러(`CancelEventOutboxPublisher`)가 담당한다.

```
발행 흐름:
  1. TX3 안에서
     payment_item 상태 변경
     payment 상태 재계산
     cancel_request → COMPLETED
     cancel_event_outbox INSERT(PENDING)   ← 같은 TX, 원자적. Kafka 호출 없음.
  2. TX3 커밋 성공
     → afterCommit: Redisson wake 발행(cancel-outbox-wake) — 저지연 트리거, 실패해도 무해(poll이 backstop)
  3. CancelEventOutboxPublisher(poll 10s 또는 wake) 가 PENDING 배치를 조회해 발행
     → 성공: markPublished (PUBLISHED)
     → 실패: bumpRetry (재시도) → max-retries 초과 시 markDead(DEAD) + OperationAlertPort 알림

실패 처리:
  outbox INSERT가 TX3에 이미 원자 커밋됐으므로 Kafka 장애가 TX3를 롤백시키지 않는다(DB 상태는 이미 COMPLETED).
  발행 자체가 실패하면 폴러가 max-retries까지 재시도 후 DEAD 전이 + 알림 — 이후 운영 개입 필요.
  PUBLISHED 행은 retention-days 경과 후 purge 스케줄러가 삭제.

INLINE(벤치/학습 전용, 기본 비활성)과의 차이:
  INLINE: TX3 맨 마지막에 kafkaTemplate.send().get(5s) 직접 호출. 발행 실패 → 예외 throw → TX3 롤백
          → cancel_request PROCESSING 유지 → processing-recovery(60초)가 TX3 재실행.
          테이블 추가 없이 일관성 보장되지만, Kafka 응답 대기 동안 DB 커넥션을 점유하고
          DB commit과 Kafka 발행이 하나의 실패 단위로 묶여(dual-write) TX3 스루풋이 Kafka 지연에 종속된다.
  OUTBOX: DB 커밋과 Kafka 발행이 분리 — TX3는 outbox INSERT만으로 즉시 커밋, 발행은 폴러가 비동기로 흡수.
```

> **해소된 버그(OUTBOX 폴러 라이브락)**: 과거 폴러가 앱과 DB 커넥션 풀을 공유해 고부하 시
> `markPublished`가 커넥션 굶음 → 같은 head 배치 재발송 라이브락에 빠졌다(§실측 근거 →
> [`load-test/outbox-poller-livelock.md`](./load-test/outbox-poller-livelock.md)).
> 수정: 폴러 전용 소형 DataSource(`OutboxDataSourceConfig.cancelOutboxDataSource`, 별도 Hikari 풀)로 분리해
> 앱 요청 처리 풀과 경합하지 않도록 했다.

### 설정값

```properties
# 멱등성 보장
# 네트워크 오류로 재시도 시 중복 발행 방지
enable.idempotence=true

# 모든 ISR에 저장 확인 후 성공 처리
acks=all

# 재시도 횟수 제한 없음 (네트워크 일시 장애 대비)
retries=Integer.MAX_VALUE

# 멱등성 활성화 시 최대 5까지 허용
max.in.flight.requests.per.connection=5

# 압축으로 네트워크 비용 절감
compression.type=snappy

# 최대 5ms 기다렸다가 배치로 전송 (처리량 향상)
linger.ms=5
batch.size=16384

# Key/Value 직렬화
key.serializer=org.apache.kafka.common.serialization.StringSerializer
value.serializer=org.apache.kafka.common.serialization.StringSerializer
```

---

## 6. Consumer 설계

### Offset 커밋 전략 — 왜 수동 커밋인가

```
Kafka는 메시지를 읽어도 자동으로 삭제하지 않는다.
Consumer가 "어디까지 처리했는지"를 offset으로 Kafka에 알려야 한다.
이 행위를 "offset 커밋"이라 한다.

자동 커밋 (enable.auto.commit=true):
  Kafka가 일정 시간마다 자동으로 offset을 커밋한다.
  
  문제 상황:
    1. msg3 수신
    2. 자동 커밋 → offset=3 기록
    3. msg3 처리 중 서버 다운
    4. 재시작 후 offset=3부터 시작
    → msg3은 처리 안 됐는데 커밋됨 → 영구 유실

수동 커밋 (enable.auto.commit=false, 우리가 선택):
  처리가 완전히 끝난 후에만 커밋한다.
  
  흐름:
    1. msg3 수신
    2. DB 처리 완료
    3. offset 커밋 → offset=3 기록
    4. 서버 다운돼도 재시작 후 offset=3부터 시작
    → msg3은 이미 processed_cancel_event에 기록됨
    → 재처리해도 멱등성으로 no-op 처리됨
```

### 처리 결과별 offset 커밋 흐름

```
메시지 수신
  ↓
cancelRequestId 중복 체크 (processed_cancel_event)
  ↓
이미 처리됨 → no-op → offset 커밋   ← 빠르게 스킵
  ↓
신규 메시지
  ↓
오류 유형 판별
  ├── 데이터 오류 (OrderItem not found 등)
  │   재시도해도 해결 안 됨
  │   → 즉시 DLQ 발행 → offset 커밋
  │
  └── 일시적 오류 (DB 타임아웃, 네트워크 오류)
      retry-count 확인
      ├── 3회 미만 → retry 토픽 발행 → offset 커밋
      └── 3회 이상 → DLQ 발행 → offset 커밋

성공 시:
  OrderItem 상태 변경
  processed_cancel_event INSERT
  offset 커밋

핵심 원칙:
  offset 커밋은 항상 마지막에.
  성공이든 DLQ 이동이든 처리가 끝난 후에만 커밋.
```

### 멱등 처리

```sql
-- 처리 전 중복 체크
SELECT id FROM processed_cancel_event
WHERE cancel_request_id = ?

-- 처리 후 기록 (UK 제약으로 중복 INSERT 방어)
INSERT INTO processed_cancel_event (cancel_request_id, processed_at)
VALUES (?, NOW(3))
```

### 재시도 대기 시간

| retry-count | 대기 시간 | 비고 |
|------------|---------|------|
| 1회 | 1분 | |
| 2회 | 5분 | |
| 3회 | 10분 | |
| 초과 | DLQ 이동 | 수동 처리 |

### 설정값

```properties
# Consumer Group
group.id=order-service

# 수동 offset 커밋
enable.auto.commit=false

# 트랜잭션 커밋된 메시지만 읽음
isolation.level=read_committed

# 한 번에 읽을 최대 메시지 수
max.poll.records=100

# poll() 호출 간격 최대 시간
# 이 시간 초과 시 Consumer 그룹에서 제외 → 리밸런싱 발생
max.poll.interval.ms=300000

# Key/Value 역직렬화
key.deserializer=org.apache.kafka.common.serialization.StringDeserializer
value.deserializer=org.apache.kafka.common.serialization.StringDeserializer

# Retry Consumer (별도 Consumer Group)
group.id=order-service-retry
max.poll.records=50
```

---

## 7. DLQ 운영 절차

### TPS 규모별 DLQ 전략

```
현재 (TPS 100):
  DLQ 발생 건수가 적음
  → 수동 처리로 충분

TPS 1,000~10,000 규모:
  DLQ 메시지가 많아지면 수동 처리 한계
  → 아래 자동화 전략 도입 검토

자동화 전략:
  1. DLQ Consumer 별도 운영
     일정 시간(예: 1시간) 후 자동으로
     payment.cancelled로 재발행 시도
     → 인프라 일시 장애는 시간이 지나면 자연 해소되는 경우 많음

  2. 오류 유형 분류 자동화
     lastError 패턴 분석
     → 알려진 일시 오류 → 자동 재발행
     → 알려지지 않은 오류 → 운영팀 알림

  3. Dead Letter Queue Dashboard
     Kafka UI에서 DLQ 메시지 현황 실시간 확인
     원클릭 재발행 기능 구성

현재 단계에서는 수동 처리로 시작하고
TPS가 늘면서 DLQ 발생 패턴을 파악한 후 자동화.
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
    "firstFailedAt": "2026-04-13T10:00:00Z",
    "lastFailedAt": "2026-04-13T10:20:00Z",
    "lastError": "OrderItem not found: paymentItemId=99",
    "movedToDlqAt": "2026-04-13T10:20:05Z"
  }
}
```

`originalPartition` + `originalOffset`으로 원본 메시지 위치를 추적한다.

### DLQ 수동 처리 절차

```
1. 알림 수신 (슬랙 등)
2. Kafka UI에서 DLQ 메시지 확인
3. lastError 분석

   코드 버그
   → 수정 배포
   → DLQ 메시지를 payment.cancelled로 재발행
   → retry-count 헤더 0으로 초기화

   데이터 불일치
   → DB 직접 보정
   → DLQ 메시지 skip (offset만 커밋)

   인프라 장애
   → 복구 확인
   → DLQ 메시지를 payment.cancelled로 재발행

4. DLQ 재발행 시 retry-count 헤더 0으로 초기화
```

---

## 8. Kafka UI

Kafka UI (Provectus)를 별도 인스턴스로 운영한다.

### 제공 기능

```
토픽 관리
  토픽 목록 / 생성 / 삭제
  파티션별 메시지 수 / offset 현황

메시지 조회
  토픽별 메시지 실시간 조회
  JSON 포맷 자동 파싱 (String/JSON 직렬화 선택 이유 중 하나)
  특정 offset / timestamp 기준 조회

Consumer Group 모니터링
  Consumer lag 실시간 확인
  파티션별 처리 현황

DLQ 운영
  payment.cancelled.DLQ 메시지 조회
  메시지 내용 확인 후 재발행
```

### Docker Compose 설정

```yaml
services:
  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports:
      - "8989:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka1:9092,kafka2:9092,kafka3:9092
    depends_on:
      - kafka1
      - kafka2
      - kafka3
```

### 접근 제한

```
Kafka UI는 내부망에서만 접근 가능하게 구성한다.
외부 노출 시 메시지 내용(취소 금액, 사용자 ID 등) 유출 위험.
운영 환경에서는 VPN 또는 SSH 터널링으로만 접근.
```

---

## 9. 모니터링 지표

| 지표 | 설명 | 알림 기준 |
|------|------|---------|
| `kafka_consumer_lag` | Consumer가 처리 못한 메시지 수 | 1,000 초과 |
| `dlq_message_count` | DLQ 메시지 수 | 1건 이상 즉시 |
| `processing_cancel_request_count` | PROCESSING 5분 초과 건수 | 1건 이상 시 |
| `retry_topic_lag` | Retry 토픽 적체 수 | 500 초과 |

---

## 10. 장애 시나리오별 대응

| 시나리오 | 발생 상황 | 대응 |
|---------|---------|------|
| OUTBOX 발행 실패(기본) | outbox 행 재시도 누적 | max-retries 초과 시 DEAD 전이 + 알림 → 운영 수동 재처리 |
| (INLINE 모드 한정) TX3 Kafka 발행 실패 | TX3 롤백 → cancel_request PROCESSING | processing-recovery(60초) → PG 조회 → TX3 재실행 |
| Consumer 다운 | 메시지 처리 지연 | 인스턴스 재시작 → 미커밋 offset부터 재처리 |
| 브로커 1대 장애 | 해당 파티션 Leader 변경 | Follower 자동 승계 → 서비스 영향 없음 |
| 브로커 2대 장애 | replication factor=3 한계 | 서비스 중단 → 브로커 복구 필요 |
| DLQ 메시지 발생 | Consumer 3회 실패 | 운영팀 알림 → 수동 처리 |