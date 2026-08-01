# 취소 복원 일관성 — 레그 하드닝 설계 (갈래 B / B2)

- 날짜: 2026-08-01
- 브랜치: `feat/cancel-restore-consistency` (단독 — payment·order·product 걸치는 cross-cutting, 병렬 안 함)
- 접근: **접근 1 (레그 하드닝)** — 크로스-서비스 리컨실러(접근 2)·오케스트레이션 saga(접근 3)는 범위 밖.

## 1. 목표 / 배경

결제 취소 시 `payment.cancelled`를 **두 독립 컨슈머**가 소비한다:
- order-service — 주문/아이템 상태를 CANCELLED로 동기화
- product-service — 취소된 SKU 재고를 복원

이 둘은 별 서비스·별 DB·별 groupId·별 TX라 **원자성이 없다**. 지금은 한 레그만 성공하고 다른 레그가 실패하면 그 실패가 **조용히 사라지고**(신호 없음), 불일치가 영구화된다(예: 주문=CANCELLED인데 재고는 복원 안 됨).

**목표**: 취소 복원에서 **조용한 실패 제거 + 레그별 최종 수렴 보장**.
- 진짜(순간) 원자성은 분산이라 불가 — 목표는 **보장된 최종 일관성**.
- **payment 취소 코어(TX1/2/3·outbox 발행)는 불변**(CANCEL-01). B2는 **컨슈머 측(order·product)**만 건드린다. payment 발행은 이미 견고한 at-least-once(outbox).

## 2. 코드가 드러낸 갭 (as-built, 파일:라인 근거)

두 레그는 동형(product의 RetryRouter/Consumer는 order의 복제본). 대표로 order 라인 표기, product도 동일 구조.

**갭①: 이벤트 증발 (실버그).** 컨슈머가 재발행 후 **무조건 ack** →  재발행 send가 async 실패하면 이벤트 소실.
- `order-service/.../messaging/PaymentCancelledConsumer.java:54-58` — `catch`에서 `retryRouter.route(record, e)` 직후 **무조건** `ack.acknowledge()`(57).
- `order-service/.../messaging/RetryRouter.java:62`·`:70` — `kafkaTemplate.send(...)`의 future를 **무시**(fire-and-forget). `publishToDlq`의 try/catch(:67-74)는 68행 직렬화(동기)만 잡고 브로커 async 실패는 못 잡음.
- 순서: `route()` → send 비동기 발사 → 컨슈머 ack(원본 오프셋 커밋) → 그 후 send 실패 → 아무도 못 잡음 → retry·DLQ 어디에도 없이 증발.

**갭②: DLQ 막다른 길.** `payment.cancelled.DLQ`를 읽는 `@KafkaListener` 0개, 알림 0개(order·product main에 `OperationAlertPort` 미배선). DLQ 적재 시 로그만 → 사람이 토픽을 수동으로 열기 전엔 silent.

**갭③: 크로스-레그 정합성 장치 없음.** 한 레그만 성공했을 때 불일치를 탐지·복구·알림하는 스케줄러/리컨실 없음. product의 `OrphanReservationRecoveryScheduler`는 다른 시나리오(미커밋 payment의 stale RESERVED, `exists=false`일 때만 release)라 취소 복원 누락은 못 잡음.

**갭④: 관측성 공백.** 두 레그가 retry/DLQ 토픽(`payment.cancelled.retry`·`.DLQ`)을 공유 → DLQ만 봐선 어느 레그가 죽었는지 payload 뜯기 전엔 구분 불가.

**멱등 기반은 이미 있음**: 두 레그 모두 `processed_cancel_event`(cancelRequestId) 선체크 + UK. 재구동(replay) 안전성의 토대.

## 3. 설계 (order·product 각 레그에 동형 적용 — 조기 공용 추상화 안 함)

### 수정 1 — 증발 버그 (갭①)
`RetryRouter.route()`가 send를 **동기 확인**하고, 실패 시 예외를 던진다. 컨슈머는 **route 성공 시에만 ack**; 실패하면 **ack하지 않는다** → Kafka가 원본을 재전달(at-least-once 보존, 멱등이라 안전).
- `route()`: `kafkaTemplate.send(...).get(timeout)`로 브로커 ack 대기(bounded). 실패 → 예외 전파.
- 컨슈머 catch: `route()`가 예외 없이 반환하면 ack; 예외면 ack 생략(로그 + 재전달에 맡김).
- `ponytail:` 천장 — 브로커 지속 장애 시 원본이 count 미증가로 재전달 반복(손실보다 나음). 완전 견고화(consumer-side outbox)는 YAGNI.

### 수정 2 — durable DLQ + 알림 (갭②·④)
재시도 소진(count≥3) 또는 NonRetryable → **DLQ 테이블에 적재 + 알림**.
- 신규 테이블 `cancel_restore_dlq`(모듈별): `cancel_request_id`, `leg`(ORDER|STOCK, 관측성 — 갭④), `payload`, `retry_count`, `first_failed_at`, `last_error`, `status`(PENDING|RESOLVED|DEAD), `attempt_count`, `created_at`, `updated_at`. UK `(cancel_request_id)` — 같은 취소는 한 행(멱등).
- `OperationAlertPort` 추가(order·product엔 없음 → payment 패턴 복제, 기본 로그 impl). DLQ 적재 시 `alert(...)`.
- 기존 `payment.cancelled.DLQ` 토픽은 유지(전송로)하되 **durable 진실은 테이블**. (레그별 토픽 분리는 범위 밖 — `leg` 컬럼으로 구분.)

### 수정 3 — 자동 재구동 스케줄러 (갭③)
`cancel_restore_dlq`의 `PENDING`을 주기적으로 **핸들러(`ProcessCancelled*Service`) 재호출**.
- 멱등(`processed_cancel_event`) 덕에 이미 처리분은 no-op, 실패분만 재적용.
- 성공 → `RESOLVED`. `attempt_count` 초과(임계 §8) → `DEAD` + 에스컬레이션 알림.
- Redisson 분산락(기존 스케줄러 3종 관행), 백오프.
- → 죽은 레그가 사람 개입 없이 수렴 시도, 안 되면 시끄럽게(테이블 DEAD + 알림) 남음.

## 4. 데이터 모델
order-db·product-db 각각 `cancel_restore_dlq` 테이블 신설. 모듈별 독립 DB 원칙 유지 (Flyway: order 차기 V, product 차기 V — 실제 버전은 계획 단계에서 각 모듈 최신 확인).

## 5. 불변식 / 가드
- **CANCEL-01**: payment 모듈 diff 0(발행·TX 코어 무변경). git diff(merge-base) 게이트.
- **멱등 replay 안전**: 재구동은 `processed_cancel_event` 선체크 통과분만 실제 적용 → 이미 처리된 건 no-op.
- 도메인 로직(재고 release / 주문 상태전이) 자체는 불변 — **컨슈머 신뢰성 계층만 추가**.
- order·product 무결성: 각자 자기 DB만(크로스-서비스 조회 없음 = 접근 1 유지).

## 6. 보장 / 비보장 (정직한 천장)
- **보장**: 이벤트 무손실 · 모든 실패 가시화(테이블 + 알림) · 일시 장애는 멱등 재구동으로 자동 수렴.
- **비보장**: 순간 일관성(잠깐의 불일치 창 존재) · 영구 실패의 자동 치유(→ DEAD + 사람 알림) · 크로스-레그 즉시 대조(접근 2).

## 7. 테스트 전략 (Testcontainers Kafka + MySQL)
- 수정1: 재발행 send 실패 주입 → 원본 ack 안 됨 → 재전달로 결국 처리/DLQ 적재(**손실 0**) 검증.
- 수정2: 핸들러 3회 실패 → `cancel_restore_dlq` PENDING 적재 + `OperationAlertPort.alert` 호출 검증. `leg` 값 정확.
- 수정3: DLQ PENDING → 스케줄러가 성공 재처리 → RESOLVED, 이미 처리분 멱등 no-op, 임계 초과 → DEAD + 에스컬레이션 검증.
- 수렴 e2e: 한 레그만 DLQ로 빠진 뒤 재구동으로 최종 일치(주문=취소 ∧ 재고=복원) 검증.
- CANCEL-01 무회귀 + 기존 취소 복원/주문 동기화 IT 통과.

## 8. 열린 질문 (계획 단계에서 확정)
- 재구동 스케줄러 주기(예: 30s, 기존 compensation-retry와 정렬?) 및 백오프 곡선.
- `DEAD` 전이 `attempt_count` 임계(예: 5) 및 에스컬레이션 알림 채널(현재는 로그 impl — 실제 채널은 배포 관심사).
- send 동기 확인 timeout 값.
- `cancel_restore_dlq`를 order/product 공통 스키마로 둘지(복제) vs 최소 차이 허용.
- 기존 `payment.cancelled.retry`/`.DLQ` 토픽 공유 유지(현行) vs 이번에 정리(→ 범위 밖로 잠정).
