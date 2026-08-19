# Roadmap: 취소 복원 일관성 (cancel-restore) — v1.0

## Overview

결제 취소 시 `payment.cancelled`를 소비하는 두 독립 컨슈머 레그(order-service 주문 상태 동기화, product-service 재고 복원)는 동형이며 RetryRouter가 서로 복제본이다. 이 마일스톤은 두 레그의 조용한 실패(이벤트 증발·막다른 DLQ·자동 수렴 부재)를 제거한다. 접근: 트레이서 우선 수직 슬라이스 — **Phase 1에서 product 레그를 3개 수정(증발 버그·durable DLQ+알림·재구동 스케줄러) 전부로 끝까지 하드닝하고 수렴 e2e로 증명한 검증된 참조 구현을 만든 뒤, Phase 2에서 동일 패턴을 order 레그에 복제**한다. payment 취소 코어(TX1/2/3·outbox 발행)는 두 페이즈 모두 불변(INV-01, git diff 게이트).

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Product 레그 하드닝 (참조 구현)** - 재고 복원 컨슈머에 3개 수정 전부 적용 + 수렴 e2e로 증명
- [ ] **Phase 2: Order 레그 하드닝 (복제)** - 검증된 패턴을 주문 동기화 컨슈머에 동형 복제, 두 레그 동시 수렴

## Phase Details

### Phase 1: Product 레그 하드닝 (참조 구현)
**Goal**: product-service 재고 복원 컨슈머가 이벤트를 잃지 않고, 모든 복원 실패가 durable 테이블 + 알림으로 가시화되며, 일시 장애가 사람 개입 없이 자동 재구동으로 수렴한다 — order 레그에 복제할 검증된 참조 구현을 확보한다.
**Depends on**: Nothing (first phase)
**Requirements**: LOSS-01, DLQ-01, DLQ-02, REDRIVE-01, REDRIVE-02, INV-01
**Success Criteria** (what must be TRUE):
  1. 재발행 send 실패를 주입하면 product 컨슈머가 원본을 ack하지 않아 Kafka가 재전달하고, 이벤트 손실이 0이다 (수정1 — `RetryRouter.route()` 동기 확인, route 성공 시에만 ack).
  2. 핸들러 3회 실패(또는 NonRetryable) 시 product-db `cancel_restore_dlq`에 `leg=STOCK` PENDING 행이 멱등 적재(`cancel_request_id` UK)되고 `OperationAlertPort.alert`가 호출된다.
  3. 스케줄러가 `cancel_restore_dlq` PENDING 행을 재구동하여 성공 시 `RESOLVED`, 이미 처리분은 `processed_cancel_event` 멱등으로 no-op, `attempt_count` 임계 초과 시 `DEAD` + 에스컬레이션 알림으로 전이한다.
  4. product 레그만 DLQ로 빠진 뒤 재구동으로 재고가 최종 복원되는 수렴 e2e(Testcontainers Kafka+MySQL)가 손실 0으로 통과한다.
  5. payment 모듈 git diff(merge-base) = 0 (INV-01)이고, 기존 재고 복원 통합테스트가 무회귀 통과한다.
**Plans**: 1 plan
- [ ] 01-PLAN.md — product 레그 하드닝: durable DLQ+알림 수렴 트레이서 → LOSS-01 send확인/ack → REDRIVE-02 DEAD → INV-01 게이트

### Phase 2: Order 레그 하드닝 (복제)
**Goal**: Phase 1에서 증명된 하드닝 패턴(증발 수정·durable DLQ+알림·재구동 스케줄러)을 order-service 주문 상태 동기화 컨슈머에 동형 복제하여, 두 레그 모두 무손실·전면 가시화·자동 수렴을 달성한다.
**Depends on**: Phase 1
**Requirements**: LOSS-01, DLQ-01, DLQ-02, REDRIVE-01, REDRIVE-02, INV-01 (order 레그 인스턴스)
**Success Criteria** (what must be TRUE):
  1. 재발행 send 실패를 주입하면 order 컨슈머가 원본을 ack하지 않아 재전달되고, 이벤트 손실이 0이다 (수정1, order 레그).
  2. order-db `cancel_restore_dlq`에 `leg=ORDER` PENDING 행이 멱등 적재되고 `OperationAlertPort.alert`가 호출된다.
  3. order DLQ PENDING 행 재구동 → 성공 시 `RESOLVED`, 이미 처리분 멱등 no-op, `attempt_count` 임계 초과 → `DEAD` + 에스컬레이션 알림.
  4. 취소 후 두 레그 동시 수렴 e2e: 한 레그가 DLQ로 빠져도 재구동으로 최종 **주문=CANCELLED ∧ 재고=복원**으로 일치한다.
  5. payment 모듈 git diff = 0 (INV-01)이고, 기존 주문 동기화 통합테스트가 무회귀 통과한다.
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Product 레그 하드닝 | 0/1 | Not started | - |
| 2. Order 레그 하드닝 | 0/TBD | Not started | - |
