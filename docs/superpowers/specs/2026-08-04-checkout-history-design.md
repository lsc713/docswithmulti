# 체크아웃 P3 — 주문내역 + 구매자 자가취소 설계 (2026-08-04)

체크아웃 단계화의 **P3**(마지막). P1 바로구매·P2 장바구니에 이어 **구매내역 조회 + 구매자 자가취소**를 추가한다. payment가 이미 `user_id`(V1 `idx_payment_user_id`)·paymentKey·orderId·status·createdAt·items를 보관하므로, payment-service에 **조회 GET만 추가하면** 구매내역이 완결된다. 게이트웨이는 이미 `/v1/payments/**`를 인증 라우팅하므로 게이트웨이 변경은 없다.

취소는 이 시스템의 핵심(역방향)이다. P3는 구매자가 자기 결제를 취소할 수 있게 해 정방향(구매)과 역방향(취소)을 한 화면에서 닫는다.

## 결정 사항 (brainstorming)

- **내역 형태**: 목록 + (구매자) 취소 액션.
- **구매자 자가취소 허용**: 현재 정책은 `CancelAuthorizer`에 명시적으로 `USER→403`(문서화된 "USER self-cancel 미채택"). 이를 **소유자 분기로 확장**한다(role=USER이고 payment.userId == 요청 userId → 허용).

## 안전 경계 (매우 중요)

- **취소 TX 코어는 무변경**: `CancelTxWriter`(saveTx1/2/3) · `CancelPaymentService` · 스케줄러 3종 · `cancel_event_outbox`/발행 — byte-for-byte 그대로. 멱등성·TX 경계·취소기간·risk 검증·outbox 전부 불변.
- 변경은 **인가 pre-check** 계층(`CancelAuthorizer`·`CancelAuthorizationService`)에 한정. 이 계층은 v2.0에서 취소 코어 **앞단에** 추가된 인가 게이트로, TX 코어가 아니다.
- 리포에 core byte-for-byte를 강제하는 자동 CI 게이트는 실재하지 않음(문서 관행). 그럼에도 TX 코어는 손대지 않는다.
- 자가취소 요청도 기존 취소 플로우(멱등 dedup·TX1/2/3·risk·취소기간)를 **그대로** 통과한다 — 인가만 통과시킬 뿐.

## 범위 / 논-골

**P3 포함**
- payment 조회: 본인 결제 목록 + 상세 (X-User-Id 스코프, 소유 확인).
- 주문내역 화면: 구매건(일시·paymentKey·총액·상태·항목) 목록 + 상태 뱃지.
- 구매자 자가취소: 본인 COMPLETED 결제 → "취소하기"(사유 입력, **전체 항목 취소**) → 상태 갱신.

**P3 논-골**
- 부분취소 UI(항목 선택) — P3는 전체취소(모든 paymentItem). 부분취소는 백엔드는 지원하나 UI 미노출.
- 배송/환불수단/영수증, 상품↔가맹점 매핑(merchantId=1).
- ADMIN/MERCHANT 취소 UI(기존 어드민/운영 경로 그대로).

## 백엔드 (payment-service)

### 1. 조회 API (신규, X-User-Id 소유 스코프)

- `GET /v1/payments` → 본인 결제 목록. `@RequestHeader("X-User-Id") long userId`로 `payment.user_id = userId` 필터. 페이지네이션(`?page=&size=`). 응답 항목: `paymentKey, totalAmount, status, createdAt, orderId, items:[{paymentItemId, itemName, itemAmount, status}]`.
- `GET /v1/payments/{paymentKey}` → 상세. 조회 후 `payment.userId != userId`이면 404(존재 은닉) — 타 유저 결제 접근 불가.
- 신규 조회 서비스/컨트롤러(또는 기존 PaymentController에 GET 추가). 기존 `PaymentRepository`에 `findByUserId(userId, page)` / `findByPaymentKey`(존재) 활용. 취소 코어·생성 경로 무변경.
- 게이트웨이: `/v1/payments/**` 인증 라우트가 이미 GET을 커버(JwtTrustHeaderFilter → X-User-Id 주입, GET은 CSRF 면제). **게이트웨이 변경 없음.**

### 2. 자가취소 인가 확장 (인가 pre-check만, TX 코어 불변)

- `CancelAuthorizer.authorize(...)` 시그니처 확장 — 요청자 userId + 대상 payment userId(소유)를 받아 **USER 소유자 분기** 추가:
  - `ADMIN` → 허용(기존).
  - `MERCHANT` → headerMerchantId == targetMerchantId(기존).
  - `USER` → `requestUserId != null && requestUserId.equals(targetUserId)` → 허용(신규). 아니면 403.
  - role 누락/불일치 → 403(기존).
  - 정책 주석 갱신(USER는 본인 결제만 취소 허용).
- `CancelAuthorizationService`:
  - USER 경로에서도 `findByPaymentKey`로 payment를 **read-only 1회 로드**(MERCHANT와 동일 패턴)해 `payment.getUserId()`를 targetUserId로 전달. `user.userId()`를 requestUserId로 전달.
  - ADMIN은 기존대로 로드 생략(전체 허용). MERCHANT는 기존대로 merchantId 로드.
  - 서명/호출부 확장. 취소 실행(CancelController → cancelPaymentUseCase)은 무변경 — 인가만 통과.
- **취소 실행 경로**(`CancelController`의 취소 호출, `CancelPaymentService`, `CancelTxWriter`, 스케줄러, outbox)는 무변경.

### 3. 문서 정정

- `CancelAuthorizer` 정책 주석: USER→403 문구를 "USER는 본인 결제(payment.userId==요청 userId)만 취소 허용"으로.
- `docs/architecture/auth-gateway.html` payment 인가 role 매트릭스: USER 분기 반영.
- `CLAUDE.md` 취소 인가 문구(있으면) 갱신.

## 프론트엔드 (스토어프론트)

- `api.js`:
  - `getPayments()` → GET /v1/payments
  - `getPayment(key)` → GET /v1/payments/{key}
  - `cancelPayment(key, body)` → POST /v1/payments/{key}/cancel (csrf). body `{cancelReason, cancelItems:[{paymentItemId}]}`.
- `NavBar.jsx`: 로그인 시 `주문내역` 버튼(`onHistory`).
- `OrderHistory.jsx`(신규): props `payments`, `onCancel(key, items)`, `onBack`.
  - 각 구매건: createdAt · paymentKey · totalAmount · **상태 뱃지**(COMPLETED / CANCELLED / PARTIAL_CANCELLED) · 항목 목록.
  - `status === 'COMPLETED'`인 건에 "취소하기" → 사유 입력(간단 prompt 또는 인풋) → `onCancel(key, allPaymentItemIds)` → 성공 시 목록 새로고침(상태 CANCELLED로).
- `App.jsx`: `history` 뷰 + `payments` 상태. 로그인 시/진입 시 `getPayments()`. `handleCancel(key, items)`: `cancelPayment` 후 `getPayments()` 재조회. OrderSuccess에 "주문내역 보기"(선택).

## 데이터 흐름

```
NavBar 주문내역 → History 뷰 ← GET /v1/payments (본인, 소유 스코프)
  각 구매건(일시·총액·상태·항목)
    └ COMPLETED → 취소하기(사유) → POST /v1/payments/{key}/cancel {cancelReason, cancelItems:[전체]}
         └ (인가: USER 본인 소유 → 허용) → 기존 취소 플로우(멱등·TX·risk·outbox) → status CANCELLED
              └ getPayments() 재조회 → 뱃지 갱신
```

## 테스트

**백엔드 (payment-service)**
- 조회: `GET /v1/payments` 본인 목록 필터·페이지네이션, `GET /v1/payments/{key}` 상세, **타 유저 결제 404**(소유 격리) — 컨트롤러(MockMvc) + 레포.
- 인가(단위): `CancelAuthorizer` — USER 본인(requestUserId==targetUserId) 허용, USER 타인 403, ADMIN/MERCHANT 기존 유지, role 누락 403.
- 인가(application): `CancelAuthorizationService` — USER 경로 payment 로드 후 소유 위임, 타인 403.
- 취소 통합: USER 본인 결제 취소 성공(status 전이), 타인 결제 취소 403. **취소 TX 코어 테스트는 무변경으로 그대로 green**.

**프론트 E2E (Playwright)**
- 구매 → 주문내역 진입 → 목록에 구매건·COMPLETED → 취소하기(사유) → 상태 CANCELLED 표시.

## 트레이드오프 / 메모

- **정책 전환**: 문서화된 "USER→403 self-cancel 미채택"을 사용자 결정으로 **소유자 자가취소 허용**으로 전환. 문서(주석·auth-gateway.html·CLAUDE.md) 동기화 필수.
- **TX 코어 불변 유지**: 변경은 인가 pre-check + 조회 + 프론트에 한정. 취소 실행/멱등/TX/스케줄러/outbox 무변경.
- **전체취소만**: P3 UI는 결제의 모든 paymentItem을 취소. 부분취소는 백엔드 지원하나 P3 범위 밖.
- **취소기간/risk**: 자가취소도 기존 취소기간·risk 검증을 통과해야 함(기간 초과·검증 실패 시 기존 에러 그대로 노출).
