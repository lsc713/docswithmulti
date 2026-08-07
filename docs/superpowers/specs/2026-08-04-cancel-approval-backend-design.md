# 취소 승인 워크플로우 P1 — 백엔드 승인 코어 설계 (2026-08-04)

취소를 **즉시 실행**에서 **요청 → 사유 검토 → 승인 실행** 흐름으로 바꾼다. 이 문서는 그 첫 단계인 **백엔드 승인 코어**(payment-service). 프론트(어드민/판매자 승인 큐, 스토어프론트 요청 전환)는 후속 P2·P3.

## 배경 / 문제

현재 취소는 `POST /v1/payments/{key}/cancel` 호출 즉시 실행 기계(`CancelPaymentService.cancel()` → cancel_request PENDING→PROCESSING→COMPLETED, risk·PG·TX3·outbox)가 동기·비가역으로 돈다. P3에서 USER 자가취소를 즉시 허용했으나, 실제 이커머스는 **고객이 사유와 함께 환불을 요청하고 판매자/어드민이 검토 후 승인**하는 흐름이다. 사람의 검토 게이트가 없다.

## 핵심 원칙 — 승인은 실행 앞단의 새 레이어 (취소 코어 불변)

`CancelStatus`(PENDING/PROCESSING/COMPLETED/FAILED)는 **실행 상태**다. 이를 쪼개지 않는다. 대신 **승인(approval) 생명주기를 별도 엔티티로 실행 앞단에 추가**한다.

```
[신규 cancel_approval — payment-service]              [기존 실행 — byte-for-byte 불변]
USER 취소요청 → REQUESTED ──승인──▶ APPROVED ──▶ CancelPaymentService.cancel(command) 그대로 호출
   (사유 필수)      │                              → cancel_request PENDING→PROCESSING→COMPLETED
                    └──반려──▶ REJECTED             → risk·PG·TX3·멱등·스케줄러·outbox 전부 무변경
                       (반려사유, payment COMPLETED 유지)

ADMIN/MERCHANT 직접취소 ──▶ 승인 레코드 없이 오늘처럼 즉시 cancel() (auto-approved, 무변경)
```

승인 서비스는 승인 시 **오늘 클라이언트가 하던 것과 동일하게** 기존 취소 진입점 `cancel(CancelPaymentCommand)`을 호출할 뿐이다. 실행 경로에 새 분기 0개. `CancelTxWriter`·`CancelPaymentService.cancel`·스케줄러 3종·outbox·멱등 무접촉 — 이것이 취소 코어 불변을 지키는 방식이다.

## 확정된 정책 (brainstorming)

| 항목 | 결정 |
|---|---|
| USER 취소 요청 승인자 | **ADMIN(전체) + MERCHANT(본인 가맹점)**. USER는 요청만. |
| ADMIN/MERCHANT 직접 취소 | **즉시 auto-approved** (= 오늘과 동일, 승인 게이트 미적용) |
| 자동승인 규칙 | **없음** — USER 요청 전부 수동 검토 (규칙은 후속) |
| 반려 | **반려사유 기록 + USER 재요청 허용**, payment는 COMPLETED 유지 |
| 승인 단위 | **요청 통째로** (아이템별 승인 없음) |

## 범위 / 논-골

**P1 포함 (백엔드만)**
- `cancel_approval` 테이블(V20) + 도메인/레이어(hex).
- USER 취소 **요청 생성** API + 승인 큐 **조회** API + **승인/반려** API.
- 승인 → 기존 `cancel()` 호출로 실행. 반려 → 실행 안 함.
- 승인/반려 인가(ADMIN 전체 / MERCHANT 본인 가맹점), 요청 인가(payment 소유 USER).
- 게이트웨이 라우트(신규 경로).
- 단위·통합 테스트.

**P1 논-골**
- 프론트(어드민 승인 큐 UI — P2, 스토어프론트 요청 전환 — P3).
- **부분취소 승인**: v1 요청은 **결제 전체 취소** 단위(현재 P3 프론트도 전체취소). 아이템셋 승인은 후속.
- 자동승인 규칙(기간·금액).
- **USER 직접취소(`POST .../cancel`) 제거는 P3(스토어프론트 전환)에서** — P1 단계에서는 기존 P3 즉시취소 경로를 그대로 두어 main이 단계 사이에 깨지지 않게 한다. USER는 P1 후 직접취소·요청 둘 다 가능(전이 상태, 프론트가 요청으로 넘어갈 때 직접취소 USER 분기 제거).

## 데이터 모델 (payment_db, Flyway V20)

payment-service 최신 Flyway는 V19(`payment_event_outbox`) → 다음 **V20**.

```sql
CREATE TABLE cancel_approval (
  id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payment_id         BIGINT       NOT NULL,
  payment_key        VARCHAR(64)  NOT NULL,
  requester_user_id  BIGINT       NOT NULL,          -- 요청한 USER
  reason             VARCHAR(500) NOT NULL,          -- 취소 사유
  status             VARCHAR(20)  NOT NULL,          -- REQUESTED / APPROVED / REJECTED
  decided_by_user_id BIGINT       NULL,              -- 승인/반려한 주체
  decided_role       VARCHAR(20)  NULL,              -- ADMIN / MERCHANT
  decision_reason    VARCHAR(500) NULL,              -- 반려 사유 (승인 시 NULL)
  cancel_request_id  BIGINT       NULL,              -- 승인 후 실행된 취소 링크
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  KEY idx_cancel_approval_payment (payment_id),
  KEY idx_cancel_approval_status (status)
);
-- 같은 결제에 REQUESTED 중복 방지: 애플리케이션에서 활성 REQUESTED 존재 검사
--   (MySQL 부분 UK 미지원 → 앱 레벨 가드 + idx_cancel_approval_payment 조회).
```

> `cancel_approval`은 payment-service 소유. 취소 코어 테이블(payment/cancel_request/…)과 독립 — 컬럼 추가/변경 없음.

## 레이어 (기존 hex 패턴)

- `domain/entity/CancelApproval` (POJO, JPA 어노테이션 금지) — 상태 전이 규칙(REQUESTED에서만 approve/reject 가능) 포함.
- `domain/entity/CancelApprovalStatus` enum: REQUESTED, APPROVED, REJECTED.
- `application/interfaces/CancelApprovalRepository` 포트: `save`, `findById(long)`, `findActiveRequestedByPaymentId(long)`, `findByStatus(status, merchantScope)`.
- `application/usecase/CancelApprovalUseCase` + `application/service/CancelApprovalService`:
  - `request(paymentKey, requesterUserId, reason)`: payment 조회(소유 검증) → 활성 REQUESTED 있으면 409 → INSERT REQUESTED.
  - `list(approver, statusFilter)`: ADMIN=전체 / MERCHANT=본인 가맹점 payment의 요청만.
  - `approve(id, approver)`: 승인자 인가 → 상태 REQUESTED 확인 → payment 전체 아이템 id 로드 → `CancelPaymentService.cancel(new CancelPaymentCommand(paymentKey, reason, allItemIds, null))` 호출 → 성공 시 APPROVED + `cancel_request_id` 링크.
  - `reject(id, approver, decisionReason)`: 승인자 인가 → REQUESTED 확인 → REJECTED + decision_reason. payment 불변.
- `infrastructure/persistence`: `CancelApprovalJpaEntity`, `CancelApprovalJpaRepository`, `CancelApprovalRepositoryImpl`.
- `presentation/controller/CancelApprovalController` + DTO.

### 인가 (기존 매트릭스 재사용, USER 승인 배제)

- **요청 생성**: 요청자 USER가 payment 소유자여야 함(`payment.userId == X-User-Id`). P3의 소유자 검증과 동일 로직.
- **승인/반려**: **ADMIN=전체, MERCHANT=payment의 merchantId == X-Merchant-Id**. USER는 승인/반려 불가(403). 이는 P3 이전 매트릭스(USER→403)와 동일 — 승인 경로는 P3의 USER-자가 분기를 쓰지 않는다. 별도 승인자 인가 헬퍼로 구현(기존 `CancelAuthorizer`는 직접취소 전용으로 유지, 무변경).

## API (payment-service, 게이트웨이 신뢰헤더 X-User-Id/Role/Merchant-Id)

| 메서드 | 경로 | 인가 | 동작 |
|---|---|---|---|
| POST | `/v1/payments/{paymentKey}/cancel-requests` | 소유 USER | body `{reason}` → REQUESTED 생성. 활성 요청 중복 시 409. → 201 `{id, status:REQUESTED}` |
| GET | `/v1/cancel-requests?status=REQUESTED` | ADMIN/MERCHANT | 승인 큐. MERCHANT는 본인 가맹점만. → `{items:[{id, paymentKey, requesterUserId, reason, status, createdAt}]}` |
| POST | `/v1/cancel-requests/{id}/approve` | ADMIN/MERCHANT | 승인 → 기존 cancel() 실행. → 200 `{id, status:APPROVED, cancelRequestId, paymentStatus}`. 이미 결정된 요청이면 409. |
| POST | `/v1/cancel-requests/{id}/reject` | ADMIN/MERCHANT | body `{decisionReason}` → REJECTED. → 200 `{id, status:REJECTED}`. |

- 변경계열(POST) → 게이트웨이 CsrfFilter 적용(프론트 단계에서 csrf 토큰).
- 승인 실행 실패(취소 기간 초과·risk 거부 등)는 기존 `cancel()`이 던지는 에러를 그대로 전파 — 승인 상태는 REQUESTED 유지(재시도 가능) 또는 실패 표기. **기본: 실행 실패 시 approve는 4xx/5xx 반환, cancel_approval은 REQUESTED로 남긴다**(승인자가 사유 확인 후 재시도). 멱등: 하부 cancel()이 dedup_key로 따닥 차단.

## 게이트웨이

- `/v1/cancel-requests/**` + `POST /v1/payments/{key}/cancel-requests` 인증 라우트 추가(JwtTrustHeaderFilter → JWT 검증 + X-User-* strip/주입). 기존 `/v1/payments/**` 라우트와 무간섭(신규 경로).
- RouteConfig javadoc 갱신 + `GatewayRoutingIT`에 no-token 401 / valid-JWT 라우팅(+X-User-* 주입) 테스트.

## 불변 / 취소 코어

- `CancelTxWriter`, `CancelPaymentService.cancel`, 스케줄러 3종(pending/processing/compensation), `cancel_event_outbox`, 멱등(request_hash/dedup_key), `cancel_request`/`payment`/`payment_item` 테이블·로직 **byte-for-byte 무변경**.
- 신규 코드는 전부 실행 앞단(요청/승인/반려) + 신규 테이블. 승인 = 기존 진입점의 새 호출자.

## 테스트

**단위 (Mockito)**
- CancelApprovalService: 요청 생성(소유 검증·중복 409), 승인(REQUESTED→APPROVED + cancel() 호출 검증 + 링크), 반려(REJECTED + 사유), 비-REQUESTED 재결정 409, MERCHANT 타-가맹점 요청 승인 시도 403, USER 승인 시도 403.
- CancelApproval 도메인: 상태 전이 규칙(APPROVED/REJECTED에서 재전이 불가).

**통합 (Testcontainers, MockMvc)**
- 각 엔드포인트 상태코드 + X-User-* 매핑 + merchant 스코프 격리.
- 승인 → 실제 cancel_request 생성·payment CANCELLED 전이(기존 취소 플로우 통과) end-to-end 1건.
- 반려 → payment COMPLETED 유지, 재요청 가능.

**게이트웨이**: `/v1/cancel-requests` no-token 401 / valid-JWT 라우팅 + X-User-* 주입.

## 단계 로드맵 (이 문서 = P1)

- **P1 백엔드 승인 코어** (본 문서): cancel_approval + 요청/승인/반려 API + 인가 + 게이트웨이 + 테스트.
- **P2 어드민/판매자 승인 큐 UI**: 게이트웨이 라우트(완료) 소비 + 콘솔 큐/승인·반려. MERCHANT 승인 UI 위치는 P2에서 확정.
- **P3 스토어프론트 요청 전환**: OrderHistory 즉시취소 → 요청 제출 + 상태 뱃지. **이 단계에서 USER 직접취소(`POST .../cancel`) 인가 분기 제거**(요청 경로로 일원화).

## 트레이드오프 / 메모

- **승인=동기 실행**: 오늘 cancel()이 동기이므로 approve 호출이 곧 실행. 별도 EXECUTED 상태 불필요 — 실행 결과는 링크된 cancel_request/payment status가 보유.
- **부분취소 v1 제외**: 요청은 결제 전체 취소. 아이템셋 승인은 아이템 테이블/부분 request_hash 매핑이 필요해 후속.
- **전이 기간의 이중 경로**: P1~P3 사이 USER는 직접취소·요청 병존. main 무중단을 위한 의도된 전이 — P3에서 일원화.
- **auto-approved 무레코드**: ADMIN/MERCHANT 직접취소는 승인 레코드를 만들지 않는다(오늘 경로 byte-identical 유지). 이들 취소의 감사는 기존 cancel_request_history가 담당.
