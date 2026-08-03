---
milestone: v1.0
milestone_name: 정산 집계 코어
workstream: settlement
last_updated: 2026-08-03
---

# Requirements — v1.0 정산 집계 코어 (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-03-settlement-aggregation-design.md`
목표: 신규 `settlement-service`(8086)가 완료 결제·취소를 **가맹점×정산주(KST 월~일)** 로 집계해 **수수료+VAT를 뗀 net 지급액을 원장에 확정**. 매출/취소는 이벤트 실시간 적재 + 주 마감 배치 리컨실로 누락 보정.
불변: payment 취소 코어(TX1/2/3·멱등·스케줄러·cancel outbox·재고 예약/복원) diff 0 (CANCEL-01). payment 변경은 `payment.completed` 아웃박스(생성경로) + 리컨실 조회 API 두 곳뿐.

## v1.0 Requirements

### 정산 서비스 골격 + 취소 적재 (SETUP / CANCEL)
- [x] **SETUP-01**: 신규 `settlement-service`(8086) 독립 MySQL·헥사고날 골격 + Flyway V1(4테이블: merchant_settlement_config, settlement, settlement_line, processed_settlement_event).
- [x] **CANCEL-01**: `payment.cancelled`(기존 이벤트) 구독 → CANCEL 라인 적재. 멱등키 `cancel:{cancelRequestId}`, `settlement_line.event_id` UK로 중복 차단. 재수신 no-op.
- [x] **CANCEL-02**: CANCEL 라인 적재 시 소속 정산주 헤더(merchant×period_start) upsert(없으면 OPEN 생성) + `cancel_amount` 원자 증분.

### 매출 이벤트 + 수수료·net 산출 (SALE / FEE)
- [ ] **SALE-01**: payment-service가 결제 **생성** TX에서 `payment_event_outbox`에 `payment.completed` 원자 INSERT + 발행(cancel outbox 패턴 복제). payload: paymentKey·merchantId·totalAmount·items·completedAt.
- [ ] **SALE-02**: settlement가 `payment.completed` 구독 → SALE 라인 적재. 멱등키 `sale:{paymentKey}`, event_id UK 중복 차단. 정산주 헤더 upsert + `gross_amount` 원자 증분.
- [ ] **FEE-01**: 요율 원본 `merchant_settlement_config`(merchant_id PK, fee_rate, active) — settlement 자체 소유(cross-DB FK 없이 merchant_id 관례참조). 요율 조회/설정 경로.
- [ ] **FEE-02**: net 산출 규약 — `fee=round(gross×fee_rate,2,HALF_UP)`, `vat=round(fee×0.10,2,HALF_UP)`, `net=gross−cancel−fee−vat`. BigDecimal scale 2 HALF_UP. 요율 미설정 시 FINALIZE 보류.

### 배치 리컨실 + 확정 (RECON)
- [ ] **RECON-01**: Redisson 분산락 스케줄러 — 주 마감된 OPEN 원장 대상, payment `GET /v1/payments/settlement?merchantId&from&to`로 기간 대조 → 이벤트 누락 SALE/CANCEL 라인 보정 적재(event_id UK로 중복 무시).
- [ ] **RECON-02**: 리컨실 후 gross/cancel 재검증 → fee/vat/net 확정 → **OPEN→FINALIZED** 전이(finalized_at). FINALIZED 헤더는 금액·라인 불변. 불일치 시 `OperationAlertPort` 알림.
- [ ] **RECON-03**: payment-service 리컨실 전용 조회 API `GET /v1/payments/settlement` 신설(읽기 전용) — 기간 내 완료 결제 + 취소 반환. 모듈간 DB 직접접근 금지 → HTTP pull.

### 조회 (QUERY)
- [x] **QUERY-01**: `GET /v1/settlements?merchantId&status` → 가맹점 정산 내역 목록(원장 헤더). `GET /v1/settlements/{id}` → 헤더 + 라인 명세.

### 불변 (INV)
- [ ] **INV-01**: payment 취소 코어(CancelService·CancelTxWriter·스케줄러 3종·cancel_event_outbox·cancel_request 멱등·재고 예약/복원 경로) diff 0. payment 변경은 신규 파일 + 결제 생성 경로 1곳 outbox INSERT + 신규 조회 API만. 기존 취소·재고 통합테스트 무회귀. git diff 게이트.

## v2 / Out of Scope (다음 슬라이스 → 각 별도)

| Feature | Reason |
|---------|--------|
| 지급 실행(payout) 은행이체·FINALIZED→PAID 상태머신 | 첫 슬라이스는 net **확정**까지. 실이체는 외부연동 별도 도메인 |
| 요율 차등(가맹점/카테고리/기간별)·effective-dated 요율 이력 | 이번은 단일 요율 고정 |
| 정산 명세서(statement) 발급·PDF | 원장 확정 후 문서화 슬라이스 |
| 취소 수수료 환급(이미 뗀 수수료 되돌림) | 이번은 취소 **거래액**만 net 차감 |
| 정산 주기 다양화(일/월별) | 이번은 주별 고정. 일별 원장 위 롤업으로 확장 가능 |
| 조정·정정(adjustment/reversal) 원장 | 사후 정정 도메인 별도 |
| 가맹점 정산계좌 관리 | payout 슬라이스와 함께 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| SETUP-01 | Phase 1 | Complete (01-01) |
| CANCEL-01 | Phase 1 | Complete (01-01) |
| CANCEL-02 | Phase 1 | Complete (01-01) |
| QUERY-01 | Phase 1 | Complete (01-01) |
| INV-01 | Phase 1 | Pending (01-02) |
| SALE-01 | Phase 2 | Pending |
| SALE-02 | Phase 2 | Pending |
| FEE-01 | Phase 2 | Pending |
| FEE-02 | Phase 2 | Pending |
| RECON-01 | Phase 3 | Pending |
| RECON-02 | Phase 3 | Pending |
| RECON-03 | Phase 3 | Pending |

**Coverage: 12/12 requirements mapped ✓** — INV-01은 Phase 1 앵커, Phase 2/3 재검증. QUERY-01은 Phase 1 tracer에 포함(취소만 조회), Phase 2/3에서 매출·net 필드 확장.
