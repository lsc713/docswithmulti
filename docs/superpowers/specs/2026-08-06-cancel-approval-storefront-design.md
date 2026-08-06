# 취소 승인 워크플로우 P3 — 스토어프론트 요청 전환 설계 (2026-08-06)

취소 승인 워크플로우의 **P3(마지막)**. P1(백엔드 승인 코어, #99)·P2(어드민/판매자 승인 큐, #101)에 이어, 구매자(USER) 쪽을 **즉시 취소에서 취소 요청 제출로 전환**한다. 이로써 USER는 사유와 함께 요청하고 ADMIN/MERCHANT가 승인하는 흐름이 종단간 닫힌다.

## 배경 / 정책 전환

- 현재 스토어프론트 OrderHistory: COMPLETED 결제에 "취소하기" → 사유 prompt → `POST /v1/payments/{key}/cancel` **즉시 취소**(체크아웃 P3에서 `CancelAuthorizer`에 USER 자가취소 분기를 추가했었음).
- P3: 이 정책을 **요청→승인**으로 대체한다. USER 직접취소를 막고(403), USER는 `POST /v1/payments/{key}/cancel-requests`(P1)로만 취소를 **요청**한다.

## 범위 / 논-골

**P3 포함**
- 백엔드: `CancelAuthorizer`에서 **USER 분기 제거**(USER 직접취소 → 403 복귀). ADMIN/MERCHANT 직접취소는 유지(auto-approved).
- 백엔드: `GET /v1/payments`(내역) 응답에 결제별 **`cancelRequestStatus`**(null/REQUESTED/REJECTED) 추가 — 재조회에도 요청 상태를 정확히 표시하기 위함.
- 스토어프론트: "취소하기" → **"취소 요청"** 제출(사유). 상태 뱃지(요청됨/반려됨) + 반려 시 재요청.

**P3 논-골**
- 승인자(ADMIN/MERCHANT) UI — P2에서 완료.
- 부분취소 요청 UI(요청은 결제 전체 단위, P1과 동일).
- 요청 취소(철회)·요청 이력 화면.
- 승인 경로 자체 변경 — `CancelApprovalService.approve()`는 무변경(별도 `ApprovalAuthorizer` 사용, `CancelAuthorizer` 제거와 무관).

## 백엔드 (payment-service)

### 1. USER 직접취소 인가 제거 — `CancelAuthorizer`

`domain/service/CancelAuthorizer.authorize(role, requestUserId, targetUserId, headerMerchantId, targetMerchantId)`에서 **USER 분기 삭제**:
```
- if ("USER".equals(role) && requestUserId != null && targetUserId != null
-         && requestUserId.equals(targetUserId)) { return; }
```
→ ADMIN(전체)·MERCHANT(본인 가맹점)만 허용, 그 외(USER 포함) 403. javadoc의 USER 항목 제거.
- `CancelAuthorizationService`: USER 경로용 `targetUserId`(payment.userId) 로드가 있으면 불필요해지므로 정리(authorize가 USER를 어차피 403 처리 — read-only 로드 제거해 단순화). 시그니처는 유지하되 USER 특례 로직만 제거.
- **취소 실행 코어 무변경**: `CancelPaymentService.cancel`·TX·멱등·스케줄러·outbox 무접촉. 변경은 인가 pre-check 레이어뿐.

### 2. 내역 응답에 `cancelRequestStatus` 추가

**`CancelApprovalRepository`에 메서드 추가**:
```java
Optional<CancelApproval> findLatestByPaymentId(long paymentId);  // findFirstByPaymentIdOrderByIdDesc
```
(구현: Spring Data `findFirstByPaymentIdOrderByIdDesc(long)`.)

**`PaymentSummaryResponse`에 필드 추가**: `String cancelRequestStatus`(nullable).
- `from(Payment, List<PaymentItem>, String cancelRequestStatus)`로 시그니처 확장(또는 오버로드).

**`PaymentHistoryService.list`**: 각 payment에 대해 `findLatestByPaymentId(p.getId())`로 최신 승인건 조회 후 상태 도출:
- 최신건 status == REQUESTED → `"REQUESTED"`
- 최신건 status == REJECTED → `"REJECTED"`
- 그 외(없음 / APPROVED=이미 CANCELLED로 payment.status에 반영) → `null`
- 이미 payment당 items를 조회(N+1)하는 기존 패턴과 동일 레벨 — history는 본인 결제 소량 페이지라 수용. (필요 시 후속 batch.)

> 상세 조회 `GET /v1/payments/{key}`(`PaymentDetailResponse`)에도 동일 필드를 추가할지는 **논-골** — 내역 목록만으로 UX 충족. 목록 DTO만 확장.

## 스토어프론트

**`api.js`** — 추가:
- `requestCancel: (key, reason) => req('/v1/payments/' + key + '/cancel-requests', { method: 'POST', body: { reason }, csrf: true })`
- (기존 `cancelPayment`는 ADMIN/MERCHANT 데모용으로 남기거나 미사용 — 스토어프론트에서 호출 제거.)

**`App.jsx`** — `handleCancel` → `handleRequestCancel(key, reason)`: `api.requestCancel(key, reason)` 후 내역 재조회(`getPayments`)로 `cancelRequestStatus` 갱신. 409(중복 요청)·기타 에러 표시.

**`OrderHistory.jsx`** — 결제별 액션을 `status` + `cancelRequestStatus`로 분기:
- `COMPLETED` & `cancelRequestStatus == null` → **"취소 요청"** 버튼(사유 prompt → `onRequestCancel(key, reason)`).
- `COMPLETED` & `REQUESTED` → **"취소 요청됨"** 뱃지(버튼 없음).
- `COMPLETED` & `REJECTED` → **"취소 반려됨"** + **"다시 요청"** 버튼(재요청 허용, P1 정책).
- `CANCELLED`/`PARTIAL_CANCELLED` → 기존과 동일(뱃지만).
- prompt 취소(null) 시 no-op.

## 인증 / 게이트웨이

- `POST /v1/payments/{key}/cancel-requests`는 P1에서 이미 게이트웨이 인증 라우트(`/v1/payments/**` 커버). 무변경.
- 변경계열 → CSRF(`csrf:true`).
- USER는 쿠키 로그인 → 게이트웨이가 `X-User-Id` 주입 → 요청 소유 검증(P1 `ApprovalAuthorizer.authorizeRequest`).

## 데이터 흐름

```
OrderHistory (COMPLETED, cancelRequestStatus=null)
  └ 취소 요청 → 사유 → POST /v1/payments/{key}/cancel-requests
       → REQUESTED 생성 (payment는 COMPLETED 유지)
          └ 내역 재조회 → cancelRequestStatus='REQUESTED' → '취소 요청됨' 표시
               (이후 ADMIN/MERCHANT가 P2 큐에서 승인 → payment CANCELLED / 반려 → REJECTED로 재요청 가능)
```

## 배포 게이트 (P1과 동일)

- payment ingress NetworkPolicy(게이트웨이 파드 전용) 필수 — `X-User-Id`가 요청 소유/승인 인가에 load-bearing. 신규 게이트 없음.

## 테스트

**백엔드 (payment-service)**
- `CancelAuthorizerTest`: USER는 이제 항상 403(기존 USER-허용 케이스를 403 기대로 갱신). ADMIN/MERCHANT 유지.
- `CancelAuthorizationService` 테스트: USER 직접취소 403 갱신.
- `PaymentHistoryService`/컨트롤러 IT: 결제에 REQUESTED 승인건 있으면 `cancelRequestStatus="REQUESTED"`, 반려건이면 `"REJECTED"`, 없으면 `null`.
- 전체 회귀 green(취소 코어·승인 흐름 무변경 확인).

**프론트 E2E (Playwright)**
- 로그인 → 구매 → 주문내역 "취소 요청" → 사유 → '취소 요청됨' 표시(버튼 사라짐). 재조회에도 유지.
- (반려 후) '취소 반려됨' + '다시 요청' 노출 → 재요청 가능.
- USER가 직접취소 API(`POST /v1/payments/{key}/cancel`) 호출 시 403(스토어프론트는 호출하지 않지만 인가 회귀 검증).

## 트레이드오프 / 메모

- **정책 되돌림**: 체크아웃 P3의 USER 자가취소(즉시)를 승인 흐름으로 대체. `CancelAuthorizer` USER 분기 제거는 인가 pre-check 레이어 변경일 뿐 — 취소 실행 코어(TX/멱등/스케줄러/outbox) 불변.
- **cancelRequestStatus 파생**: payment당 최신 cancel_approval 1건으로 도출(REQUESTED/REJECTED/null). APPROVED는 payment.status=CANCELLED로 이미 표현되므로 null 처리.
- **N+1 수용**: 내역 목록이 이미 payment별 items를 조회 — 승인 상태 조회도 동일 레벨. 본인 결제 소량이라 수용, 후속 batch 여지.
- **종단간 완결**: P1(백엔드) → P2(승인자 UI) → P3(요청자 UI). 정방향 구매(체크아웃)와 역방향 취소(요청→승인)가 모두 UI로 닫힘.
