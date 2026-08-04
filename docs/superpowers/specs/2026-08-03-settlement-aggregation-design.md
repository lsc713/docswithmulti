# 정산 집계 코어 — 설계 (settlement-service v1.0)

- 날짜: 2026-08-03
- 브랜치: `feat/settlement-aggregation` (worktree `../docswithmulti-settlement`, 단독)
- 선행: 취소 코어 4서비스 + v2.0 인증경계 + v3.0 재고 + product-catalog/attribute + order-link + cancel-restore 모두 main 반영(583ffee).
- 정산 도메인의 **첫 수직 슬라이스**. 다음 슬라이스 = 지급 실행(payout)·요율 차등·명세 발급.

## 1. 목표 / 배경

가맹점 정산(merchant settlement/payout) 백엔드가 **전무**하다. 코드 전수조사 결과:
- 매출 원천 = payment-service의 `payment.total_amount` / `payment_item.item_amount`(BigDecimal, `DECIMAL(19,2)`, KRW) — payment DB 안에만 존재.
- 취소 = V8 이후 **아이템 전액취소 모델**(`cancel_request.cancel_amount`, `payment_item.status=CANCELLED`). `payment.cancelled` 이벤트에 merchantId·itemAmount·cancelledAt 완비.
- **매출 확정 이벤트 없음**(payment는 취소만 발행), **수수료·VAT·정산계좌·요율·주기 개념 자체가 코드에 없음**.

목표: 신규 **`settlement-service`**가 완료 결제와 취소를 **가맹점×정산주 단위로 집계**해, 수수료+VAT를 뗀 **net 지급액을 원장(ledger)에 확정**한다. 매출/취소는 **이벤트로 실시간 적재**하고, 주 마감 시 **배치로 payment DB와 대조(리컨실)**해 누락을 보정한 뒤 FINALIZE 한다.

**불변 제약**: payment 취소 코어(TX1/2/3·멱등·스케줄러·cancel outbox·재고 예약/복원)는 변경하지 않는다(CANCEL-01). payment 변경은 (1) 결제 **생성** 경로에 `payment.completed` 아웃박스 INSERT 추가, (2) 정산 리컨실 전용 조회 API 신설 — 두 곳뿐.

## 2. 스코프

**포함**
- 신규 `settlement-service`(8086) — 독립 MySQL, 헥사고날.
- `payment.cancelled`(기존) + `payment.completed`(신설) 구독 → 원장 라인 적재(멱등).
- 가맹점×정산주(KST 월~일) 집계 → `settlement` 원장 헤더.
- 단일 요율 기반 **수수료 + VAT(수수료의 10%) + net** 산출.
- 주 마감 배치 **리컨실러**(Redisson 스케줄러): payment DB 대조·누락 보정 → FINALIZE.
- 원장 조회 API(가맹점별 정산 내역).
- payment-service: `payment.completed` 아웃박스 발행 + 리컨실 전용 조회 API.

**범위 밖 (다음 슬라이스 → 각 별도)**
- **지급 실행(payout)**: 은행이체·상태머신(FINALIZED→PAID)·이체 결과 반영.
- **요율 차등**: 가맹점/카테고리/기간별 다른 요율, 요율 이력(effective-dated).
- **정산 명세서(statement) 발급** + PDF/다운로드.
- **취소 수수료 환급**: 취소 시 이미 뗀 수수료 되돌림(이번은 취소 **거래액**만 net에서 차감).
- **주기 다양화**: 일별/월별 정산(이번은 주별 고정).
- **조정·정정(adjustment/reversal)** 원장.
- **가맹점 정산계좌** 관리.

## 3. 데이터 모델 (settlement_db, Flyway V1)

```sql
-- 가맹점 정산 설정(요율 원본). merchant-limit-service의 merchant.id를 관례 참조(cross-DB FK 없음).
CREATE TABLE merchant_settlement_config (
    merchant_id BIGINT       NOT NULL,
    fee_rate    DECIMAL(5,4) NOT NULL,          -- 예: 0.0330 = 3.3%
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 원장 헤더: 가맹점 × 정산주(KST 월~일) 유일.
CREATE TABLE settlement (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_id   BIGINT       NOT NULL,
    period_start  DATE         NOT NULL,          -- 정산주 월요일(KST)
    period_end    DATE         NOT NULL,          -- 정산주 일요일(KST)
    gross_amount  DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 완료 매출 합
    cancel_amount DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 취소 거래액 합
    fee_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 수수료(확정 시 계산)
    vat_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 수수료 VAT(확정 시 계산)
    net_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,   -- 지급 예정액(확정 시 계산)
    status        VARCHAR(20)  NOT NULL,             -- OPEN | FINALIZED
    finalized_at  DATETIME(3)  NULL,
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_merchant_period (merchant_id, period_start),
    KEY idx_settlement_status_period (status, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 원장 라인: 감사추적 + 멱등 단위. 이벤트/리컨실이 여기에 append.
CREATE TABLE settlement_line (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT       NOT NULL,
    type          VARCHAR(10)  NOT NULL,             -- SALE | CANCEL
    payment_key   VARCHAR(100) NOT NULL,
    amount        DECIMAL(19,2) NOT NULL,            -- 양수(부호는 type이 결정)
    event_id      VARCHAR(120) NOT NULL,             -- 멱등키(아래 §4)
    occurred_at   DATETIME(3)  NOT NULL,             -- 매출/취소 발생시각(정산주 귀속 기준)
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_line_event (event_id),  -- 중복 적재 차단
    KEY idx_settlement_line_settlement (settlement_id),
    CONSTRAINT fk_settlement_line_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 컨슈머 멱등(이벤트 재수신 no-op). 라인 UK와 이중 가드.
CREATE TABLE processed_settlement_event (
    event_id     VARCHAR(120) NOT NULL,
    processed_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 요율은 settlement가 **자체 소유** — 모듈간 DB 직접접근 금지 원칙상 merchant-limit 테이블을 읽지 않고, `merchant_id`만 관례 참조(FK 없음). 요율 seed는 Flyway seed 또는 관리 API(P2).
- `settlement_line.event_id` UK가 이벤트 중복·리컨실 재적재를 **DB 레벨에서** 차단(핵심 멱등 가드).

## 4. 도메인 규칙

### 정산주 귀속
- 정산주 = **KST 월요일 00:00:00 ~ 일요일 23:59:59.999**. 결제/취소를 `occurred_at`(SALE=결제 completedAt, CANCEL=cancelledAt)의 정산주로 귀속.
- `period_start` = 그 주 월요일(KST). 원장 헤더는 `(merchant_id, period_start)`로 유일 → 라인 적재 시 헤더 upsert(없으면 OPEN 생성).

### 멱등키(`event_id`)
- SALE: `sale:{paymentKey}` — 결제 1건 = 매출 1라인.
- CANCEL: `cancel:{cancelRequestId}` — 취소요청 1건 = 취소 1라인(payload의 cancelRequestId 사용).
- 이벤트 재수신·리컨실 재적재 시 `event_id` UK 충돌 → no-op(INSERT ... 무시 or 선체크). `processed_settlement_event`로 컨슈머 레벨 이중 가드.

### 집계
- `gross_amount` = 그 주 SALE 라인 amount 합. `cancel_amount` = CANCEL 라인 amount 합.
- 라인 적재마다 헤더 gross/cancel 증분 갱신(원자 UPDATE). status=FINALIZED 헤더에는 **적재 금지**(마감 후 도착분은 리컨실 예외로 로깅 — §Phase 3).

### 수수료·세금·net (FINALIZE 시점 확정)
- `fee = round(gross_amount × fee_rate, 2, HALF_UP)`
- `vat = round(fee × 0.10, 2, HALF_UP)`   (수수료에 대한 부가세)
- `net = gross_amount − cancel_amount − fee − vat`
- 반올림 규약: **BigDecimal, scale 2, RoundingMode.HALF_UP**(스키마가 `DECIMAL(19,2)` 소수 허용 KRW → 반올림 지점을 fee/vat 계산에 고정). fee_rate 없으면(config 미설정) FINALIZE 보류 + 알림.
- *가정: 취소는 거래액(cancel_amount)만 net에서 차감. 이미 뗀 수수료 환급은 범위 밖.*

### 상태 전이
- **OPEN**: 주 진행 중. 이벤트/리컨실 라인 계속 적재, gross/cancel 증분.
- **OPEN → FINALIZED**: 주 마감 + 리컨실 통과 후 배치가 fee/vat/net 확정하고 전이. 이후 **불변**(금액·라인 append 금지).
- 지급 실행(FINALIZED → PAID)은 범위 밖.

## 5. API 계약

### settlement-service
- `GET /v1/settlements?merchantId={id}&status={OPEN|FINALIZED}` → 가맹점 정산 내역 목록(원장 헤더).
- `GET /v1/settlements/{id}` → 헤더 + 라인 명세.
- (P2) `PUT /v1/settlements/config/{merchantId}` `{feeRate}` → 요율 설정(관리). *가정: ADMIN 인가는 게이트웨이 신뢰헤더 패턴 답습(배포 시 NetworkPolicy 게이트 — 후속).*

### payment-service (신설)
- **이벤트** `payment.completed` — 결제 생성 TX에서 아웃박스 INSERT. payload:
  ```jsonc
  { "paymentKey": "...", "merchantId": 42, "totalAmount": 39000,
    "items": [ {"paymentItemId": 1, "itemAmount": 39000} ],
    "completedAt": "2026-08-03T10:00:00.000" }   // = payment.created_at
  ```
- **조회** `GET /v1/payments/settlement?merchantId={id}&from={iso}&to={iso}` → 리컨실용. 기간 내 완료 결제 + 취소를 반환(`{paymentKey, merchantId, totalAmount, status, createdAt, cancels:[{cancelRequestId, cancelAmount, completedAt}]}`). 모듈간 DB 직접접근 금지 → HTTP pull.

## 6. 이벤트 흐름 (settlement 수집)

```
[결제 생성]  payment 생성 TX ─(원자)─▶ payment_event_outbox INSERT(payment.completed)
                                          └─ outbox publisher ─▶ Kafka payment.completed
                                                                    └─▶ settlement SaleConsumer ─▶ SALE 라인 적재(멱등)

[취소 완료]  기존 TX3 ─▶ cancel_event_outbox ─▶ Kafka payment.cancelled (불변)
                                                  └─▶ settlement CancelConsumer ─▶ CANCEL 라인 적재(멱등)

[주 마감]    Redisson 스케줄러 ─▶ 지난 주 OPEN 원장 대상
              ├─ payment GET /v1/payments/settlement (기간 대조)
              ├─ 이벤트 누락분 SALE/CANCEL 라인 보정 적재(event_id UK로 중복 무시)
              ├─ gross/cancel 재검증 → fee/vat/net 확정
              └─ FINALIZED 전이 (+ 불일치 시 OperationAlert)
```

- settlement의 payment.cancelled 컨슈머는 order/product의 기존 컨슈머 패턴(멱등 + MANUAL ack) 답습. cancel-restore의 durable DLQ/재구동까지는 이번 범위 밖(단순 재시도 + 로깅, 리컨실이 최종 backstop).

## 7. payment-service 변경 상세 (CANCEL-01 준수)

- **아웃박스 신설**: `payment_event_outbox` 테이블(cancel_event_outbox 패턴 복제) + `payment.completed` 발행 publisher. 결제 **생성** 서비스 TX에 outbox INSERT 추가 — 취소 TX1/2/3와 무관한 별도 경로.
- **조회 API 신설**: `PaymentSettlementController` (읽기 전용).
- **불변(git diff 게이트)**: 취소 서비스(`CancelService`/`CancelTxWriter`/스케줄러 3종)·`cancel_event_outbox`·멱등(`cancel_request`)·재고 예약/복원 경로 **diff 0**. 신규 파일 + 결제 생성 경로 1곳 outbox INSERT만 허용.

## 8. 불변식 / 가드

- **CANCEL-01**: payment 취소 코어 diff 0(merge-base 게이트, 단일따옴표 pathspec). 재고·복원 경로 무회귀(기존 통합테스트).
- **정산 멱등**: 같은 이벤트 2회 → 라인 1개(event_id UK). 리컨실 재적재 → 중복 0.
- **원장 정합**: gross/cancel 헤더 = 소속 라인 합(리컨실이 검증). FINALIZED 후 금액 불변.
- **금액 규약**: 전 계산 BigDecimal scale 2 HALF_UP. 통화 KRW 단일(스키마 소수 허용 유지).
- 마이그레이션: settlement V1 신설, payment는 차기 버전(V19+) 아웃박스 테이블만 추가(V1~기존 무변경).

## 9. 테스트 전략 (Testcontainers MySQL + Kafka)

- 이벤트 적재 멱등: 같은 payment.completed/cancelled 2회 → 라인 1개.
- 정산주 귀속: KST 주 경계(일요일 23:59 vs 월요일 00:00) 결제가 올바른 주로.
- 집계: 매출 3건 − 취소 1건 → gross/cancel 정확.
- 수수료·net: fee=round(gross×rate), vat=round(fee×0.1), net=gross−cancel−fee−vat, HALF_UP 경계값.
- 리컨실: 이벤트 유실(컨슈머 skip) → 배치가 payment DB 대조로 보정 → FINALIZE 후 정합.
- FINALIZED 불변: 마감 후 도착 이벤트 → OPEN 헤더 없음 → 예외 라인/알림, 확정액 불변.
- payment 무회귀: 취소·재고 통합테스트 그린 + CANCEL-01 git diff 0.

## 10. 열린 질문 (계획 단계 확정)

- 리컨실 배치 주기·주 마감 오프셋(예: 매일 01:00 KST에 D-1까지 마감된 주 처리 / 월요일 정산주 마감 후 몇 시간 뒤).
- payment.completed 발행 모드(outbox 정식 vs 기존 cancel처럼 mode 토글 재사용 여부).
- 리컨실 조회 API 페이징/기간 상한(가맹점당 주 거래량 가정).
- 요율 seed 경로(Flyway seed vs 관리 API 선주입) + 요율 미설정 가맹점 FINALIZE 정책(보류 vs 0% 처리).
- settlement의 payment.cancelled 소비를 위해 cancelRequestId·merchantId·itemAmount가 payload에 이미 있음(확인됨) — 별도 order_id 필요 여부.
