---
milestone: v1.0
milestone_name: 정산 지급 실행 payout
workstream: payout
last_updated: 2026-08-04
---

# Requirements — v1.0 정산 지급 실행 payout (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-04-settlement-payout-design.md`
목표: FINALIZED 정산의 net을 가맹점 계좌로 실제 지급. 관리자 승인 → 목 은행 이체 제출 → **웹훅(1차)+폴(backstop)** 확인으로 PAID/FAILED 수렴. 실패 재시도.
불변: payment/order/product/merchant 무접촉(settlement-only). settlement FINALIZED 헤더 불변(payout 별도 엔티티). Flyway V2만.

## v1.0 Requirements

### 정산계좌 (ACCT)
- [ ] **ACCT-01**: 가맹점 지급 계좌를 설정한다 — `PUT /v1/settlements/payout-account/{merchantId}` `{bankCode, accountNumber, holderName}` upsert(활성). 빈 값·형식 위반 → 400.
- [ ] **ACCT-02**: 가맹점 지급 계좌를 조회한다 — `GET /v1/settlements/payout-account/{merchantId}` (없으면 404).

### 지급 승인·제출 (PAY)
- [ ] **PAY-01**: 관리자가 FINALIZED 정산을 승인해 payout을 생성·제출한다 — `POST /v1/settlements/{id}/payout`. 가드: FINALIZED ∧ active 계좌 ∧ net>0 ∧ payout 없음. 통과 시 payout PROCESSING INSERT(amount=net 스냅샷, transfer_ref=id) + `BankTransferPort.submit`.
- [ ] **PAY-02**: 승인 가드 거부 — 미FINALIZED/계좌없음·비활성/net≤0 → 400, 이미 payout 존재(settlement_id UK) → 409(기존 반환). **이중지급 차단**.
- [ ] **PAY-03**: payout 상태를 조회한다 — `GET /v1/settlements/{id}/payout` (없으면 404).

### 이체 결과 확인 (CONFIRM)
- [ ] **CONFIRM-01**: 이체 결과 웹훅을 수신한다 — `POST /v1/payouts/callback` `{transferRef, result, signature}`. 서명검증(공유시크릿) 불일치 → 401·상태불변. 통과 → `applyResult`.
- [ ] **CONFIRM-02**: 폴 backstop 스케줄러 — Redisson 락, grace 초과 PROCESSING payout만 `BankTransferPort.getStatus` → 종단이면 `applyResult`(웹훅 유실 복구).
- [ ] **CONFIRM-03**: 공유 초크포인트 `applyResult` — status-guarded 원자 UPDATE `WHERE transfer_ref=? AND status='PROCESSING'` → PAID(paid_at)/FAILED. 0행이면 no-op. **웹훅·폴·중복콜백 순서무관 수렴·멱등**.

### 실패 재시도 (RETRY)
- [ ] **RETRY-01**: FAILED payout을 폴 스케줄러가 재제출 — attempt_count<max이면 `submit` 재호출(transfer_ref 동일, 목 은행 dedup) + PROCESSING 복귀·attempt++. max 초과 → 종단 FAILED + `OperationAlertPort` 알림 1회(재알림 억제).

### 외부 목 (MOCK)
- [ ] **MOCK-01**: `BankTransferPort`(submit/getStatus) + `@Profile("local") MockBankTransferClient`(제출 accepted·상태 시나리오 반환) + 실 HTTP 스텁. payment PgCancelPort/MockPgCancelClient 패턴.

### 불변 (INV)
- [ ] **INV-01**: payment 취소 코어 및 payment/order/product/merchant 전 모듈 diff 0. 변경이 `settlement-service/`(+`.planning/`·`docs/`) 국한. settlement 헤더/기존 원장·리컨실 로직 불변. Flyway V2만 추가(V1 무변경). 기존 정산 통합테스트 무회귀. merge-base git diff 게이트.

## v2 / Out of Scope (payout v3 → 각 별도)

| Feature | Reason |
|---------|--------|
| 보류/유보(chargeback reserve)·부분 지급 | 첫 슬라이스는 전액 단일 지급 |
| 실은행 연동·실 서명 스킴(HMAC/mTLS)·실 웹훅 재시도 | 목 수준 검증 |
| 지급 취소·반환(payout reversal) | 지급 후 정정 도메인 별도 |
| 정산 명세서 발급·정기지급 스케줄(요일 지정) | 문서화·스케줄 슬라이스 |
| 요율 차등/이력 | 정산 v1 잔여, 별개 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| ACCT-01 | Phase 1 | Pending |
| ACCT-02 | Phase 1 | Pending |
| PAY-01 | Phase 1 | Pending |
| PAY-03 | Phase 1 | Pending |
| CONFIRM-01 | Phase 1 | Pending |
| CONFIRM-02 | Phase 1 | Pending |
| CONFIRM-03 | Phase 1 | Pending |
| MOCK-01 | Phase 1 | Pending |
| INV-01 | Phase 1 | Phase 1 ✓ (INV01_PASS + 501/501; Phase 2 re-validates) |
| PAY-02 | Phase 2 | Pending |
| RETRY-01 | Phase 2 | Pending |

**Coverage: 11/11 requirements mapped ✓** — INV-01은 Phase 1 앵커, Phase 2 재검증. PAY-02(중복승인 409·이중지급) 멱등 엣지와 RETRY-01(실패·재시도·DEAD)은 Phase 2 하드닝. Phase 1은 happy-path 종단(승인→제출→확인→PAID) tracer.
