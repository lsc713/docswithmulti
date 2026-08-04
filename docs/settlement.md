# 정산(Settlement) 설계·산식 레퍼런스

> 가맹점 정산(merchant payout) 백엔드. 권위 설계: `docs/superpowers/specs/2026-08-03-settlement-aggregation-design.md`.
> 이 문서는 **산식(계산 규칙) + 데이터 모델 + 로드맵**의 단일 참조점이다. 상세 배경/대안은 spec을 본다.

---

## 1. 개요

신규 **`settlement-service`(8086, 독립 MySQL)** 가 완료 결제·취소를 **가맹점 × 정산주(KST 월~일)** 단위로 집계해, 수수료+VAT를 뗀 **net 지급 예정액**을 원장(ledger)에 확정한다.

- **매출/취소 수집**: Kafka 이벤트 실시간 적재(`payment.completed` 신설 · `payment.cancelled` 기존) + 주 마감 배치 리컨실(payment DB 대조)로 누락 보정.
- **불변**: payment **취소 코어**(TX1/2/3·cancel outbox·스케줄러·재고 예약/복원)는 변경 0. payment 변경은 결제 **생성** 경로의 `payment.completed` 아웃박스 INSERT + 리컨실 조회 API 두 곳뿐.
- **통화/타입**: KRW 단일, 전 금액 `BigDecimal` / DB `DECIMAL(19,2)`. 반올림은 **HALF_UP, scale 2** 고정.

```
payment-service ──payment.completed(신설)──┐
       └────────payment.cancelled(기존)────┼──▶ settlement-service
                                            │      ├ 이벤트 적재(원장 라인, 멱등)
payment GET /v1/payments/settlement─────────┘      ├ 가맹점×정산주 집계(gross/cancel)
       (리컨실 전용 조회, 신설)                     ├ 배치 리컨실(누락 보정)
                                                    └ fee+VAT+net 산출 → FINALIZE
```

---

## 2. 데이터 모델 (settlement_db, Flyway V1)

| 테이블 | 역할 | 핵심 컬럼·제약 |
|--------|------|----------------|
| `merchant_settlement_config` | 가맹점 요율 원본(settlement 자체 소유) | `merchant_id` PK, `fee_rate DECIMAL(5,4)`, `active` |
| `settlement` | 원장 헤더(정산주 1행) | UK `(merchant_id, period_start)`, `gross/cancel/fee/vat/net_amount DECIMAL(19,2)`, `status`(OPEN\|FINALIZED), `finalized_at` |
| `settlement_line` | 원장 라인(감사추적·멱등 단위) | UK `event_id`, FK→settlement, `type`(SALE\|CANCEL), `payment_key`, `amount`, `occurred_at` |
| `processed_settlement_event` | 컨슈머 멱등(이중 가드) | `event_id` PK |

- `merchant_id`는 merchant-limit-service의 `merchant.id`를 **관례 참조**(cross-DB FK 없음 — 모듈간 DB 직접접근 금지 원칙).
- 금액은 라인이 원천, 헤더는 라인 합의 원자 증분 캐시. 리컨실이 헤더=Σ라인 정합을 검증한다.

---

## 3. 산식 (계산 규칙) — 상세

### 3.1 정산주 귀속 (KST 주 경계)

정산주 = **KST 월요일 00:00:00.000 ~ 일요일 23:59:59.999**. 이벤트를 `occurred_at`의 정산주로 귀속한다.

```
occurred_at(UTC Instant)
  └─ .atZone(ZoneId.of("Asia/Seoul"))   // ① UTC→KST 변환을 '날짜 추출보다 먼저'
       └─ .toLocalDate()                 // ② KST 로컬 날짜
            └─ .with(previousOrSame(MONDAY))  // ③ 그 주 월요일 = period_start
period_end = period_start + 6일
```

- `occurred_at`: SALE = 결제 `completedAt`(=payment.created_at, UTC), CANCEL = `cancelledAt`(UTC).
- **함정**: `Instant.toLocalDate()`처럼 UTC 날짜를 먼저 뽑으면 금액이 엉뚱한 주로 샌다. 반드시 KST 변환 후 날짜.

**경계 예시**

| 이벤트 시각(UTC) | = KST | period_start(월) |
|---|---|---|
| `2026-08-02T14:59:59Z` (일 23:59 KST) | 2026-08-02(일) | 2026-07-27 |
| `2026-08-02T15:00:00Z` (월 00:00 KST) | 2026-08-03(월) | **2026-08-03** |

→ UTC 저녁 이벤트가 다음 KST 주로 넘어감. (테스트로 고정)

### 3.2 집계 (gross / cancel)

```
gross_amount  = Σ (SALE  라인 amount)   // amount = payment.totalAmount
cancel_amount = Σ (CANCEL 라인 amount)  // amount = Σ cancelledItems[].itemAmount
```

- **금액 규약**: `totalAmount = Σ itemAmount`. `quantity`는 **재고 소진에만** 쓰이고 금액에 곱해지지 않는다(스토어프론트 실증 확인). 정산은 청구된 `totalAmount`를 gross로 집계.
- 라인 적재는 **멱등**: `settlement_line.event_id` UK + `processed_settlement_event` 이중 가드. 같은 이벤트 2회 → 라인 1개, 헤더 증분 1회(라인 insert가 원자 증분보다 먼저·같은 TX → UK 충돌 시 증분까지 롤백).
- 헤더 증분은 단일 문 `UPDATE ... SET gross_amount = gross_amount + :amount`(앱 read-modify-write 금지).

### 3.3 수수료·부가세·net

```
fee = round(gross_amount × fee_rate, 2, HALF_UP)
vat = round(fee × 0.10,               2, HALF_UP)   // 수수료에 대한 부가세 10%
net = round(gross_amount − cancel_amount − fee − vat, 2, HALF_UP)
```

- **반올림**: `BigDecimal`, `setScale(2, RoundingMode.HALF_UP)`. VAT는 **이미 반올림된 fee**에서 다시 계산(중첩 반올림).
- **fee는 gross 기준**(취소 반영 전). 취소는 `cancel_amount`로 **net에서 거래액만 차감** — 이미 뗀 수수료 환급은 v1.0 범위 밖(§5 참조). 즉 취소된 매출도 수수료는 부과된 채 남는다(단순화, 실무 정산에서 흔한 사후정정 대상).
- **요율 미설정**(config 없음/`active=false`) 가맹점: `fee/vat/net` 확정 **보류**(FINALIZE 유예, 조회 시 null/보류 표기). 크래시 아님.

**계산 예시 A** — 가맹점 42, 정산주 2026-08-03~08-09, `fee_rate=0.0330`(3.3%)

| 항목 | 값 |
|---|---|
| SALE 3건 | 39,000 + 29,000 + 50,000 → **gross = 118,000.00** |
| CANCEL 1건 | 29,000 → **cancel = 29,000.00** |
| fee | round(118,000 × 0.0330) = round(3,894.0000) = **3,894.00** |
| vat | round(3,894.00 × 0.10) = round(389.400) = **389.40** |
| **net** | 118,000 − 29,000 − 3,894.00 − 389.40 = **84,716.60** |

**계산 예시 B** — 반올림 경계, `fee_rate=0.0335`, gross 12,345

| 항목 | 값 |
|---|---|
| fee | 12,345 × 0.0335 = 413.5575 → HALF_UP → **413.56** |
| vat | 413.56 × 0.10 = 41.356 → HALF_UP → **41.36** |
| net(취소 0) | 12,345 − 0 − 413.56 − 41.36 = **11,890.08** |

---

## 4. 이벤트 흐름 / 멱등

```
[결제 생성]  PaymentCreateTxWriter.persist() ─(같은 TX·원자)─▶ payment_event_outbox INSERT(payment.completed)
                                                └─ poll 발행 스케줄러 ─▶ Kafka payment.completed
                                                        └─▶ settlement SALE 컨슈머 ─▶ SALE 라인(event_id=sale:{paymentKey}) + gross 증분

[취소 완료]  기존 TX3 ─▶ cancel_event_outbox ─▶ Kafka payment.cancelled (불변)
                                                  └─▶ settlement CANCEL 컨슈머 ─▶ CANCEL 라인(event_id=cancel:{cancelRequestId}) + cancel 증분

[주 마감]    Redisson 락 스케줄러 ─▶ 지난 주 OPEN 원장 ─▶ payment 조회 대조 ─▶ 누락 라인 보정(event_id UK로 중복 무시) ─▶ fee/vat/net 확정 ─▶ FINALIZED
```

- 멱등키: SALE `sale:{paymentKey}`, CANCEL `cancel:{cancelRequestId}`. 접두사로 충돌 없음.
- `payment.completed`는 **아웃박스 정식**(생성 TX 원자 INSERT + out-of-band poll 발행). dual-write 안전.
- 리컨실이 이벤트 유실의 **최종 backstop** — 유실돼도 주 마감 대조로 수렴.

---

## 5. 불변식 / 가드

- **INV-01 (취소 코어 불변)**: `CancelPaymentService`·`CancelTxWriter`·복구 스케줄러 3종·`cancel_event_outbox`·`cancel_request` 멱등·재고 예약/복원·`OutboxDataSourceConfig`·마이그레이션 V1~V18 = **diff 0**. payment 변경 허용범위: `PaymentCreateTxWriter`(생성 TX outbox INSERT 1줄), 신규 `payment_event_outbox`+발행자, payment `V19`, 리컨실 조회 API. → merge-base git diff denylist/allowlist 게이트로 강제.
- **정산 멱등**: 같은 이벤트 2회 → 라인 1개(event_id UK). 리컨실 재적재 → 중복 0.
- **원장 정합**: 헤더 gross/cancel = 소속 라인 합. FINALIZED 후 금액·라인 불변.
- **금액 규약**: 전 계산 BigDecimal scale 2 HALF_UP. 통화 KRW 단일.

---

## 6. 로드맵 (v1.0 정산 집계 코어)

tracer-first 수직 슬라이스. Phase 1은 기존 이벤트만으로 골격을 관통하며 불변식을 증명하고, Phase 2가 매출+수수료, Phase 3이 리컨실+확정을 얹는다.

| Phase | 내용 | 요구사항 | 상태 |
|---|---|---|---|
| **1. 서비스 골격 + 취소 적재 (tracer)** | settlement-service 신설, 기존 `payment.cancelled` 구독 → CANCEL 라인 멱등 적재, KST 주별 헤더 집계, 조회 API. payment 코드 변경 0. | SETUP-01, CANCEL-01/02, QUERY-01, INV-01 | ✅ **완료** (GOAL ACHIEVED, 9 tests, payment diff 0, 무회귀 431 tests) |
| **2. 매출 이벤트 + 수수료·net** | `payment.completed` 아웃박스(생성 TX 원자 INSERT + poll 발행) 신설, settlement SALE 적재/gross 증분, 요율 기반 fee+VAT+net 산출(compute-on-read). 취소 코어 diff 0 재검증. | SALE-01/02, FEE-01/02 | ✅ **완료** (GOAL ACHIEVED 5/5, 취소 코어 diff 0, 447 tests green) |
| **3. 배치 리컨실 + 확정** | payment 리컨실 조회 API(`GET /v1/payments/settlement`, 읽기전용·독립윈도우) 신설, Redisson 스케줄러가 주 마감(period_end < today−grace) OPEN 원장 대조·record() 보정적재(event_id UK 중복무시) → Σlines 재검증 → fee/vat/net **영속** → status-guarded OPEN→FINALIZED. FINALIZED 후 늦은 이벤트는 공유 record() 가드가 알림. | RECON-01/02/03 | ✅ **완료** (GOAL ACHIEVED 6/6, 456 tests green) |

### 범위 밖 (다음 슬라이스 → 각 별도)

지급 실행(payout·은행이체·FINALIZED→PAID) · 요율 차등/이력(effective-dated) · 정산 명세서(statement) 발급 · **취소 수수료 환급**(§3.3) · 정산 주기 다양화(일/월별) · 조정·정정(adjustment) 원장 · 가맹점 정산계좌 관리.

---

## 7. 참고

- 권위 spec: `docs/superpowers/specs/2026-08-03-settlement-aggregation-design.md`
- 요구사항·로드맵(작업): `.planning/workstreams/settlement/{REQUIREMENTS,ROADMAP}.md`
- 취소 플로우(원천 이벤트): `sysdesign/cancel-design.md`
