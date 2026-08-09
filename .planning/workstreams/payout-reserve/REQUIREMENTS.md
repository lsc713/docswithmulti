---
milestone: v1.0
milestone_name: 정산 유보금 payout reserve
workstream: payout-reserve
last_updated: 2026-08-05
---

# Requirements — v1.0 정산 유보금 payout reserve (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-05-settlement-payout-reserve-design.md`
목표: payout 승인 시 net의 일부를 유보(가맹점 요율+누적 상한)해 payout=net−reserve로 줄이고, hold 만기 후 자체 은행이체로 릴리스(웹훅+폴 확인). 시간기반 롤링 홀드백(취소 소진 아님).
불변: payment/order/product/merchant 무접촉(settlement-only). payout 코어(approve non-@Transactional·409 race·applyResult·retry/DEAD) 무변경. Flyway V3.

## v1.0 Requirements

### 유보 정책 (RCFG)
- [ ] **RCFG-01**: 가맹점 유보 정책을 설정한다 — `PUT /v1/settlements/reserve-config/{merchantId}` `{reserveRate, reserveCap, holdDays}` upsert. 유효성(rate 0≤r<1·scale≤4, cap≥0, holdDays≥0) 위반 → 400.
- [ ] **RCFG-02**: 가맹점 유보 정책을 조회한다 — `GET /v1/settlements/reserve-config/{merchantId}` (없으면 404).

### 유보 홀드 (HOLD)
- [ ] **HOLD-01**: payout 승인 시 유보를 차감한다 — `reserve = min(round(net×reserve_rate,2,HALF_UP), max(0, reserve_cap − current_held))`, `current_held = Σ reserve.amount WHERE merchant_id=? AND status IN('HELD','RELEASING')`. `payout.amount = net − reserve`.
- [ ] **HOLD-02**: reserve HELD 행을 생성한다 — `reserve`(settlement_id UK, amount, status='HELD', hold_until=today(KST)+hold_days, transfer_ref='RSV-'+settlementId). reserve=0(config 없음/비활성/rate 0/cap 소진)이면 행 미생성·payout=net(하위호환).
- [ ] **HOLD-03**: 승인 순서 — payout PROCESSING INSERT(409 race 가드) → reserve HELD INSERT → payout submit. approve() **non-@Transactional 유지**(DIVE→409 불변). 기존 payout 409 race·retry 무회귀.
- [ ] **HOLD-04**: 유보 상태를 조회한다 — `GET /v1/settlements/{id}/reserve` (없으면 404). (선택) `GET /v1/merchants/{id}/reserve-balance` = current_held.

### 유보 릴리스 (REL)
- [ ] **REL-01**: 릴리스 스케줄러(Redisson) — `status='HELD' AND hold_until < today(KST)` → claim `HELD→RELEASING`(guarded UPDATE rowcount==1) → `BankTransferPort.submit('RSV-'+settlementId, account, amount)`. active 계좌 없으면 skip+log·HELD 유지.
- [ ] **REL-02**: 릴리스 결과 확인 — 웹훅 `POST /v1/reserves/callback`(서명검증 불일치 401) + 폴 backstop → 공유 `applyReserveResult` status-guarded UPDATE `WHERE transfer_ref=? AND status='RELEASING'` → RELEASED(released_at)/RELEASE_FAILED. 0행 no-op(순서무관 수렴·멱등).
- [ ] **REL-03**: 릴리스 실패 재시도 — RELEASE_FAILED → 스케줄러 재제출(attempt<max, 동일 transfer_ref) → RELEASING 복귀. max 초과 → RELEASE_DEAD + `OperationAlertPort` 알림 1회(재알림 억제). **이중 릴리스 없음**(settlement_id UK + transfer_ref + guarded UPDATE).

### 외부 목 (MOCK)
- [ ] **MOCK-01**: payout `BankTransferPort`(submit/getStatus) + `@Profile("local") MockBankTransferClient` 재사용(대상 RSV- ref). 신규 포트 없음.

### 불변 (INV)
- [ ] **INV-01**: payment/order/product/merchant diff 0. 변경이 `settlement-service/`(+`.planning/`·`docs/`) 국한. payout 코어(approve 409/applyResult/retry) 무변경. Flyway V3만 추가(V1/V2 무변경, payment 마이그레이션 0). 기존 정산·payout 통합테스트 무회귀. merge-base git diff 게이트.

## v2 / Out of Scope (reserve v4 → 각 별도)

| Feature | Reason |
|---------|--------|
| 취소·환불의 reserve 실제 소진(chargeback drawdown) | 이번은 시간기반 홀드백 — 발생 취소가 held를 차감하지 않음 |
| 부분 릴리스 · 만기 전 강제 릴리스/조정 | 전액 만기 릴리스만 |
| 유보 요율 이력(effective-dated) | 정산 요율 차등과 함께 별개 |
| 실은행 연동·실 서명 스킴(HMAC/mTLS) | 목 수준 검증 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| RCFG-01 | Phase 1 | Pending |
| RCFG-02 | Phase 1 | Pending |
| HOLD-01 | Phase 1 | Pending |
| HOLD-02 | Phase 1 | Pending |
| HOLD-03 | Phase 1 | Pending |
| HOLD-04 | Phase 1 | Pending |
| MOCK-01 | Phase 1 | Pending |
| INV-01 | Phase 1 | Pending |
| REL-01 | Phase 2 | Pending |
| REL-02 | Phase 2 | Pending |
| REL-03 | Phase 2 | Pending |

**Coverage: 11/11 requirements mapped ✓** — INV-01은 Phase 1 앵커, Phase 2 재검증. Phase 1 = 유보 정책 + 홀드(승인 차감·HELD 행·조회, 하위호환). Phase 2 = 릴리스(만기→자체이체→웹훅/폴 확인→RELEASED·재시도·DEAD).
