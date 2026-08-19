---
milestone: v1.0
milestone_name: 취소 복원 일관성
workstream: cancel-restore
last_updated: 2026-08-01
---

# Requirements — v1.0 취소 복원 일관성 (레그 하드닝, B2)

권위 설계: `docs/superpowers/specs/2026-08-01-cancel-restore-consistency-design.md`
목표: 결제 취소 복원에서 **조용한 실패 제거 + 레그별 최종 수렴 보장**. 접근 1(레그 하드닝).
적용 범위: order·product 두 취소 복원 컨슈머 레그에 **동형 적용**. payment 취소 코어 불변(CANCEL-01).

## v1.0 Requirements

### 이벤트 무손실 (LOSS)
- [ ] **LOSS-01**: 재발행(retry/DLQ) send가 브로커에서 확인될 때까지 원본 이벤트를 ack하지 않는다 — `RetryRouter.route()`가 send를 동기 확인(bounded timeout) 후 실패 시 예외, 컨슈머는 route 성공 시에만 ack. 재발행 실패 시 원본 재전달(멱등 안전). → 이벤트 증발 불가.

### durable DLQ + 알림 (DLQ)
- [ ] **DLQ-01**: 재시도 소진(count≥3) 또는 NonRetryable 실패 시 `cancel_restore_dlq` 테이블에 durable 적재(cancel_request_id UK 멱등, leg=ORDER|STOCK, payload, retry_count, first_failed_at, last_error, status, attempt_count). → "어떤 취소가 복원 실패했나"를 테이블로 조회 가능.
- [ ] **DLQ-02**: DLQ 적재 시 `OperationAlertPort`로 운영 알림 발송(order·product 신규, payment 패턴 복제, 로그 impl). → silent DLQ 종식.

### 자동 재구동 수렴 (REDRIVE)
- [ ] **REDRIVE-01**: 스케줄러가 `cancel_restore_dlq`의 PENDING 행을 주기적으로 재처리(핸들러 재호출, `processed_cancel_event` 멱등이라 이미 처리분 no-op)한다. 성공 시 status=RESOLVED. → 일시 장애로 죽은 레그가 사람 개입 없이 수렴.
- [ ] **REDRIVE-02**: attempt_count가 임계 초과하면 status=DEAD로 전이하고 에스컬레이션 알림을 발송한다. → 영구 실패는 자동 치유 대신 시끄럽게 가시화.

### 취소 코어·도메인 불변 (INV)
- [ ] **INV-01**: payment 모듈(취소 TX1/2/3·outbox 발행) 변경 0(CANCEL-01, git diff 게이트). 재고 release·주문 상태전이 도메인 로직 자체도 불변 — 컨슈머 신뢰성 계층만 추가. 기존 취소 복원·주문 동기화 통합테스트 무회귀.

## Future / Out of Scope (다음 또는 별도)

- **크로스-서비스 리컨실러**(접근 2): 두 레그 완료 상태를 대조·재구동하는 별도 안전망 — 이번은 레그별 자가 수렴만.
- **오케스트레이션 saga**(접근 3): coordinator 상태기계 — 과설계(YAGNI).
- **예약 시점 이동**(B1): 재고 예약을 주문/체크아웃 시점으로 + 예약 만료.
- **retry/DLQ 토픽 레그별 분리**: 관측성 폴리시 — 이번은 `leg` 컬럼으로 구분.
- **공용 messaging 라이브러리 추출**: order·product RetryRouter 복제 통합 — 조기 추상화 지양.

## Traceability

두 레그(product·order)는 동형이라 각 기능 요구는 Phase 1(product 레그)에서 참조 구현으로 확립되고 Phase 2(order 레그)에서 복제 완료된다. INV-01은 두 페이즈 공통 게이트.

| Requirement | Phase | Status |
|-------------|-------|--------|
| LOSS-01 | Phase 1 (product) → Phase 2 (order) | Pending |
| DLQ-01 | Phase 1 (product) → Phase 2 (order) | Pending |
| DLQ-02 | Phase 1 (product) → Phase 2 (order) | Pending |
| REDRIVE-01 | Phase 1 (product) → Phase 2 (order) | Pending |
| REDRIVE-02 | Phase 1 (product) → Phase 2 (order) | Pending |
| INV-01 | Phase 1 + Phase 2 (게이트) | Pending |

Coverage: 6/6 요구 매핑 · 고아 없음.
