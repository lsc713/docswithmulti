# 취소 승인 워크플로우 P3 — 스토어프론트 요청 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 구매자(USER)의 취소를 즉시 취소에서 **요청 제출**로 전환한다 — USER 직접취소를 막고, 스토어프론트 주문내역에서 취소를 요청하며 요청/반려 상태를 표시한다.

**Architecture:** 백엔드는 `CancelAuthorizer`의 USER 분기를 제거(직접취소 403)하고 `GET /v1/payments`에 `cancelRequestStatus`를 추가한다. 스토어프론트는 "취소하기"를 "취소 요청"(POST cancel-requests, P1)으로 바꾸고 상태 뱃지를 표시한다. 취소 실행 코어와 승인 경로(P1/P2)는 무변경.

**Tech Stack:** payment-service(Java 21/Spring Boot 4) · frontend(React 19 + Vite, oxlint, Playwright).

## Global Constraints

- **취소 실행 코어 불변** — `CancelPaymentService.cancel`·`CancelTxWriter`·스케줄러·outbox·멱등·`cancel_request`/`payment`/`payment_item` 무변경. 변경은 인가 pre-check(`CancelAuthorizer`/`CancelAuthorizationService`)와 조회 DTO뿐.
- **승인 경로 불변** — `CancelApprovalService`/`ApprovalAuthorizer`/`CancelApprovalController`는 무접촉(`CancelApprovalRepository`에 조회 메서드 1개 추가만 허용).
- **domain 레이어 Spring/JPA 금지**(POJO).
- **프론트 태스크 완료 기준**: `cd frontend && npm run lint`(oxlint) clean + `npm run build`(vite) 성공.
- 요청은 결제 전체 단위(P1과 동일). 부분취소 요청 UI 없음.

---

## File Structure

**수정 (payment-service)**
- `domain/service/CancelAuthorizer.java` — USER 분기 제거
- `application/service/CancelAuthorizationService.java` — USER 로드 정리
- `application/interfaces/CancelApprovalRepository.java` — `findLatestByPaymentId` 추가
- `infrastructure/persistence/CancelApprovalJpaRepository.java` + `CancelApprovalRepositoryImpl.java` — 구현
- `presentation/dto/PaymentSummaryResponse.java` — `cancelRequestStatus` 필드
- `application/service/PaymentHistoryService.java` — 상태 도출
- 관련 테스트

**수정 (frontend/src)**
- `api.js` — `requestCancel` 추가
- `App.jsx` — `handleRequestCancel`
- `components/OrderHistory.jsx` — 요청 버튼 + 상태 뱃지
- `e2e/cancel-request.spec.js` — 신규 E2E

---

### Task 1: USER 직접취소 인가 제거

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/service/CancelAuthorizer.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelAuthorizationService.java`
- Test: `payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java` (+ CancelAuthorizationService 테스트가 있으면 갱신)

**Interfaces:**
- `CancelAuthorizer.authorize(String role, Long requestUserId, Long targetUserId, Long headerMerchantId, Long targetMerchantId)` 시그니처 유지. USER는 이제 항상 403.

- [ ] **Step 1: 테스트를 USER→403 기대로 갱신(실패 유도)**

`CancelAuthorizerTest`에서:
- (5) `user_owner_authorized`(현재 `authorize("USER", 7L, 7L, null, null)`가 통과 기대) → **403 기대로 변경**:
```java
@Test
@DisplayName("(5) USER 는 직접취소 불가 → 403 (P3: 승인 요청 흐름으로 전환)")
void user_direct_cancel_forbidden() {
    assertForbidden(() -> authorizer.authorize("USER", 7L, 7L, null, null));
}
```
- (5b)`user_non_owner_forbidden`, (5c)`user_missing_request_user_id_forbidden`는 이미 403 기대 — 유지.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :payment-service:test --tests '*CancelAuthorizerTest'`
Expected: FAIL ((5) 케이스가 여전히 통과 → 기대 불일치)

- [ ] **Step 3: CancelAuthorizer USER 분기 제거**

```java
public void authorize(String role, Long requestUserId, Long targetUserId,
                      Long headerMerchantId, Long targetMerchantId) {
    if ("ADMIN".equals(role)) {
        return;
    }
    if ("MERCHANT".equals(role)
            && headerMerchantId != null && targetMerchantId != null
            && headerMerchantId.equals(targetMerchantId)) {
        return;
    }
    throw new CancelNotAuthorizedException();
}
```
- javadoc에서 USER 항목 삭제(“USER + 본인 결제 → 허용” 줄 제거).

- [ ] **Step 4: CancelAuthorizationService USER 로드 정리**

USER는 어차피 403이므로 payment 로드를 MERCHANT로만 한정:
```java
Long targetUserId = null;
Long targetMerchantId = null;
if ("MERCHANT".equals(role)) {
    Payment payment = paymentRepository.findByPaymentKey(paymentKey)
        .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
    targetUserId = payment.getUserId();
    targetMerchantId = payment.getMerchantId();
}
cancelAuthorizer.authorize(role, requestUserId, targetUserId, headerMerchantId, targetMerchantId);
```
- javadoc의 “USER 경로도 로드” 문구를 “MERCHANT 경로만 로드”로 정정. `requestUserId`/`parseLong`는 유지(시그니처 호환).
> USER가 존재하지 않는 paymentKey로 직접취소를 시도하면, 이제 payment 로드 없이 곧바로 403(PaymentNotFound 404가 아니라 인가 403) — 존재 은닉 관점에서 오히려 안전. 기존 USER 관련 서비스 테스트가 있으면 이 동작으로 갱신.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :payment-service:test --tests '*CancelAuthorizerTest' --tests '*CancelAuthorizationService*'`
Expected: PASS

- [ ] **Step 6: 회귀 확인**

Run: `./gradlew :payment-service:test`
Expected: 전체 PASS (취소 코어·승인 흐름 무변경 회귀).

- [ ] **Step 7: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/domain/service/CancelAuthorizer.java \
        payment-service/src/main/java/com/example/payment/application/service/CancelAuthorizationService.java \
        payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java
git commit -m "feat(cancel-approval): USER 직접취소 인가 제거 (요청 흐름으로 전환)"
```

---

### Task 2: 내역 응답에 cancelRequestStatus 추가

**Files:**
- Modify: `application/interfaces/CancelApprovalRepository.java`
- Modify: `infrastructure/persistence/CancelApprovalJpaRepository.java`, `CancelApprovalRepositoryImpl.java`
- Modify: `presentation/dto/PaymentSummaryResponse.java`
- Modify: `application/service/PaymentHistoryService.java`
- Test: `PaymentHistoryService` 단위 테스트 + `PaymentHistoryController` IT(있으면), `CancelApprovalRepository` IT

**Interfaces:**
- Produces: `CancelApprovalRepository.findLatestByPaymentId(long)→Optional<CancelApproval>`; `PaymentSummaryResponse`에 `String cancelRequestStatus`(nullable, 끝에 추가); `from(Payment, List<PaymentItem>, String cancelRequestStatus)`.

- [ ] **Step 1: 리포지토리 최신조회 메서드 테스트(실패 유도)**

`CancelApprovalRepository` Testcontainers IT에 추가(P1 IT 패턴 재사용):
```java
// 같은 payment에 REQUESTED 저장 → findLatestByPaymentId 가 그 건 반환
// 이후 REJECTED(새 행) 저장 → findLatestByPaymentId 가 REJECTED(최신 id) 반환
// 승인건 없는 payment → empty
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalRepositoryIT'`
Expected: FAIL (메서드 없음)

- [ ] **Step 3: 포트 + Spring Data + Impl**

```java
// CancelApprovalRepository.java
Optional<CancelApproval> findLatestByPaymentId(long paymentId);
```
```java
// CancelApprovalJpaRepository.java
Optional<CancelApprovalJpaEntity> findFirstByPaymentIdOrderByIdDesc(long paymentId);
```
```java
// CancelApprovalRepositoryImpl.java
@Override public Optional<CancelApproval> findLatestByPaymentId(long paymentId) {
    return jpa.findFirstByPaymentIdOrderByIdDesc(paymentId).map(CancelApprovalJpaEntity::toDomain);
}
```

- [ ] **Step 4: PaymentHistoryService 단위 테스트(실패 유도)**

```java
// 3 케이스: 최신 REQUESTED → summary.cancelRequestStatus == "REQUESTED"
//           최신 REJECTED  → "REJECTED"
//           승인건 없음     → null
// (Mockito: cancelApprovalRepository.findLatestByPaymentId stub)
```

- [ ] **Step 5: 실패 확인**

Run: `./gradlew :payment-service:test --tests '*PaymentHistoryServiceTest'`
Expected: FAIL

- [ ] **Step 6: DTO + 서비스 구현**

```java
// PaymentSummaryResponse.java — 필드 추가(끝)
public record PaymentSummaryResponse(
    String paymentKey, BigDecimal totalAmount, String status, String createdAt,
    long orderId, List<Item> items, String cancelRequestStatus
) {
    public record Item(long paymentItemId, String itemName, BigDecimal itemAmount, String status) {}

    public static PaymentSummaryResponse from(Payment p, List<PaymentItem> items, String cancelRequestStatus) {
        return new PaymentSummaryResponse(
            p.getPaymentKey(), p.getTotalAmount(), p.getStatus().name(),
            p.getCreatedAt().toString(), p.getOrderId(),
            items.stream().map(i -> new Item(i.getId(), i.getItemName(), i.getItemAmount(), i.getStatus().name())).toList(),
            cancelRequestStatus);
    }
}
```
```java
// PaymentHistoryService.list — payment별 최신 승인건으로 상태 도출
return paymentRepository.findByUserId(userId, page, size).stream()
    .map(p -> {
        String crs = cancelApprovalRepository.findLatestByPaymentId(p.getId())
            .map(a -> switch (a.getStatus()) {
                case REQUESTED -> "REQUESTED";
                case REJECTED -> "REJECTED";
                default -> null;   // APPROVED → payment.status 가 CANCELLED 로 이미 표현
            })
            .orElse(null);
        return PaymentSummaryResponse.from(p, paymentItemRepository.findAllByPaymentIdOrderByIdAsc(p.getId()), crs);
    })
    .toList();
```
- `PaymentHistoryService`에 `CancelApprovalRepository` 의존 주입(생성자). `import com.example.payment.domain.entity.CancelApprovalStatus` 불필요(switch on enum).

- [ ] **Step 7: 테스트 통과 + 회귀**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalRepositoryIT' --tests '*PaymentHistoryServiceTest'`
Expected: PASS
Run: `./gradlew :payment-service:test`
Expected: 전체 PASS.
> 주의: `PaymentSummaryResponse.from` 시그니처 변경으로 기존 호출부/테스트가 깨질 수 있음 — 전부 새 인자에 맞춰 갱신(컨트롤러 IT의 jsonPath에 `cancelRequestStatus` 확인 추가).

- [ ] **Step 8: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelApprovalRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelApproval*.java \
        payment-service/src/main/java/com/example/payment/presentation/dto/PaymentSummaryResponse.java \
        payment-service/src/main/java/com/example/payment/application/service/PaymentHistoryService.java \
        payment-service/src/test/java/com/example/payment/
git commit -m "feat(cancel-approval): 내역 응답에 cancelRequestStatus 추가"
```

---

### Task 3: 스토어프론트 — 취소 요청 전환 + 상태 뱃지

**Files:**
- Modify: `frontend/src/api.js`
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/components/OrderHistory.jsx`

**Interfaces:**
- Consumes: `GET /v1/payments` 응답 각 항목에 `cancelRequestStatus`(Task 2), `POST /v1/payments/{key}/cancel-requests`(P1).

- [ ] **Step 1: api.js에 requestCancel 추가**

```js
  requestCancel: (key, reason) =>
    req(`/v1/payments/${key}/cancel-requests`, { method: 'POST', body: { reason }, csrf: true }),
```
- 기존 `cancelPayment`(직접취소)는 남기되 스토어프론트에서 호출 제거(ADMIN/MERCHANT 데모 잔존).

- [ ] **Step 2: App.jsx handleCancel → handleRequestCancel**

```jsx
  async function handleRequestCancel(key, reason) {
    try { await api.requestCancel(key, reason) }
    catch (e) { alert(e.message); return }
    await loadPayments()   // cancelRequestStatus 갱신
  }
```
- `<OrderHistory ... onCancel={handleCancel} />` → `onRequestCancel={handleRequestCancel}` (기존 `handleCancel` 제거).

- [ ] **Step 3: OrderHistory 요청 버튼 + 상태 뱃지**

```jsx
import { useState } from 'react'

const STATUS_KO = { COMPLETED: '결제완료', CANCELLED: '취소됨', PARTIAL_CANCELLED: '부분취소' }
const CRS_KO = { REQUESTED: '취소 요청됨', REJECTED: '취소 반려됨' }

export default function OrderHistory({ payments, onRequestCancel, onBack }) {
  const [busyKey, setBusyKey] = useState(null)
  async function request(p, label) {
    const reason = window.prompt(`${label} 사유를 입력하세요`, '단순 변심')
    if (reason == null) return
    setBusyKey(p.paymentKey)
    try { await onRequestCancel(p.paymentKey, reason) }
    finally { setBusyKey(null) }
  }
  return (
    <main className="history">
      <button onClick={onBack}>뒤로</button>
      <h1>주문내역</h1>
      {payments.length === 0 ? <p>구매 내역이 없습니다.</p> : (
        <ul className="history-list">
          {payments.map(p => (
            <li key={p.paymentKey} className="history-item">
              <div className="history-head">
                <span className="history-date">{new Date(p.createdAt).toLocaleString()}</span>
                <span className={`badge ${p.status}`}>{STATUS_KO[p.status] ?? p.status}</span>
              </div>
              <div className="history-key">{p.paymentKey}</div>
              <ul className="history-items">
                {p.items.map(it => <li key={it.paymentItemId}>{it.itemName} — ₩{Number(it.itemAmount).toLocaleString()}</li>)}
              </ul>
              <div className="history-foot">
                <strong>₩{Number(p.totalAmount).toLocaleString()}</strong>
                {p.status === 'COMPLETED' && p.cancelRequestStatus == null && (
                  <button disabled={busyKey === p.paymentKey} onClick={() => request(p, '취소 요청')}>
                    {busyKey === p.paymentKey ? '요청 중...' : '취소 요청'}
                  </button>
                )}
                {p.status === 'COMPLETED' && p.cancelRequestStatus === 'REQUESTED' && (
                  <span className="crs-badge requested">{CRS_KO.REQUESTED}</span>
                )}
                {p.status === 'COMPLETED' && p.cancelRequestStatus === 'REJECTED' && (
                  <>
                    <span className="crs-badge rejected">{CRS_KO.REJECTED}</span>
                    <button disabled={busyKey === p.paymentKey} onClick={() => request(p, '다시 요청')}>
                      {busyKey === p.paymentKey ? '요청 중...' : '다시 요청'}
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
```
> `crs-badge` 등 신규 클래스는 기존 스토어프론트 CSS(App.css의 `.badge` 스타일 등)와 조화되게 최소 규칙 추가 또는 기존 클래스 재사용. 스타일 없어도 기능엔 무영향.

- [ ] **Step 4: lint + build**

Run: `cd frontend && npm run lint && npm run build`
Expected: oxlint clean, vite build 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api.js frontend/src/App.jsx frontend/src/components/OrderHistory.jsx frontend/src/App.css
git commit -m "feat(cancel-approval): 스토어프론트 취소 요청 전환 + 상태 뱃지"
```

---

### Task 4: Playwright E2E

**Files:**
- Create: `frontend/e2e/cancel-request.spec.js`

**Interfaces:**
- Consumes: 실 스택(기존 e2e 패턴). `frontend/e2e/history.spec.js`·`checkout.spec.js`의 로그인/구매 헬퍼 재사용.

- [ ] **Step 1: 요청 제출 + 상태 저니 작성**

```
1. 구매자 로그인 → 상품 구매(주문+결제) — 기존 헬퍼 재사용.
2. 주문내역 → COMPLETED 결제에 '취소 요청' 클릭 → prompt 사유(dialog accept) → '취소 요청됨' 뱃지 노출 + '취소 요청' 버튼 사라짐.
3. 재조회(주문내역 재진입)에도 '취소 요청됨' 유지(cancelRequestStatus 서버 반영).
```

- [ ] **Step 2: USER 직접취소 403 회귀**

```
- 로그인 상태에서 page.request 로 POST /v1/payments/{key}/cancel (CSRF 포함) 직접 호출 → 403.
  (스토어프론트는 호출하지 않지만 인가 회귀를 못박음.)
```

- [ ] **Step 3: `--list`로 스펙 유효성(필수 게이트) + 라이브 시도**

Run: `cd frontend && npx playwright test cancel-request --list`
Expected: 3 시나리오 열거, 문법/셀렉터 에러 없음(필수).
Run: `npx playwright test cancel-request` (실 스택 기동 시). 스택 미기동/타 브랜치면 문서화(기존 e2e 관행).

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/cancel-request.spec.js
git commit -m "test(cancel-approval): 스토어프론트 취소 요청 E2E + USER 직접취소 403"
```

---

## Self-Review 체크

- **Spec 커버리지**: USER 직접취소 제거(T1) · cancelRequestStatus(T2) · 스토어프론트 요청 전환+뱃지(T3) · E2E(T4) — 전 항목 태스크 존재.
- **취소 코어/승인 경로 불변**: 변경은 인가 pre-check(T1) + 조회 DTO/리포 메서드(T2) + 프론트(T3). `CancelPaymentService`·`CancelApprovalService`·스케줄러·outbox 무변경. T1/T2 전체 회귀로 확인.
- **타입 일관성**: `PaymentSummaryResponse.from`이 T2에서 3-arg로 바뀌고 호출부(PaymentHistoryService) 갱신. 프론트 `requestCancel(key, reason)`(T3 api) = App/OrderHistory 사용 일치. `cancelRequestStatus` 필드명이 T2 DTO = T3 렌더 일치.
- **회귀 위험**: `PaymentSummaryResponse.from` 시그니처 변경 → 모든 호출·테스트 갱신 필요(T2 Step7 명시).
