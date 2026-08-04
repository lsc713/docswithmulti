# 체크아웃 P3 (주문내역 + 구매자 자가취소) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service에 본인 결제 조회 GET을 추가하고, 취소 인가 pre-check에 USER 소유자 분기를 더해 구매자가 자기 결제를 취소할 수 있게 하고, 스토어프론트에 주문내역+취소 UI를 붙인다.

**Architecture:** 조회는 payment(userId 보유)만으로 완결(게이트웨이 `/v1/payments/**` 라우트 재사용, 변경 없음). 자가취소는 **인가 pre-check**(`CancelAuthorizer`·`CancelAuthorizationService`)만 확장하고 **취소 TX 코어**(`CancelTxWriter`·`CancelPaymentService`·스케줄러·outbox)는 byte-for-byte 무변경 — 취소 실행/멱등/TX/기간/risk는 그대로 통과.

**Tech Stack:** Java 21 · Spring Boot 4 · Spring Data JPA · JUnit5 + Mockito(MockMvc) + Testcontainers · React 19 · Vite · Playwright.

## Global Constraints

- **취소 TX 코어 무변경**: `CancelTxWriter`(saveTx1/2/3) · `CancelPaymentService` · 스케줄러 3종 · `cancel_event_outbox`/발행 · 멱등/dedup — 손대지 않는다. 변경은 인가 pre-check(`CancelAuthorizer`·`CancelAuthorizationService`) + 조회 + 프론트 + 문서에 한정.
- 도메인 레이어(`domain/**`)에 Spring/JPA 어노테이션 금지(POJO).
- 조회/취소 모두 게이트웨이 주입 `X-User-Id`로만 소유 스코프 — 타 유저 결제 접근/취소 불가(404/403).
- 게이트웨이 무변경(`/v1/payments/**` 인증 라우트가 GET/POST 모두 커버, GET은 CSRF 면제).
- 프론트 변경 호출은 `api.js` `req(path,{csrf:true})` 재사용. 어드민(`admin.html`,`src/admin/*`) 무변경. P1/P2 컴포넌트 재사용(무변경).
- 프론트엔드엔 컴포넌트 단위 테스트 러너 없음(oxlint + Playwright E2E).

---

## File Structure

**백엔드 (payment-service)**
- Modify: `application/interfaces/PaymentRepository.java` — `findByUserId`
- Modify: `infrastructure/persistence/PaymentJpaRepository.java` / `PaymentRepositoryImpl.java` — 페이지 조회
- Create: `application/usecase/PaymentHistoryQuery.java`
- Create: `application/service/PaymentHistoryService.java`
- Create: `presentation/controller/PaymentHistoryController.java`
- Create: `presentation/dto/PaymentSummaryResponse.java`, `PaymentDetailResponse.java`
- Modify: `domain/service/CancelAuthorizer.java` — 5-arg + USER 소유 분기
- Modify: `application/service/CancelAuthorizationService.java` — USER payment 로드
- Modify: `application/authz/AuthenticatedUser.java` — 주석(userId 인가 사용)
- Modify(test): `CancelAuthorizerTest`, `CancelAuthorizationServiceTest`
- Test: `PaymentHistoryControllerTest`, (repo) 조회 통합

**프론트 (frontend/src)**
- Modify: `api.js` — getPayments/getPayment/cancelPayment
- Create: `components/OrderHistory.jsx`
- Modify: `components/NavBar.jsx` — 주문내역 링크
- Modify: `App.jsx` — history 뷰
- Modify: `App.css` — history 스타일
- Test: `e2e/history.spec.js`

**문서**
- Modify: `docs/architecture/auth-gateway.html`(role 매트릭스), `CLAUDE.md`(취소 인가 문구)

---

## Task 1: 백엔드 — 본인 결제 조회 GET (TDD)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/PaymentRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java`
- Create: `application/usecase/PaymentHistoryQuery.java`
- Create: `application/service/PaymentHistoryService.java`
- Create: `presentation/controller/PaymentHistoryController.java`
- Create: `presentation/dto/PaymentSummaryResponse.java`, `presentation/dto/PaymentDetailResponse.java`
- Test: `presentation/controller/PaymentHistoryControllerTest.java`

**Interfaces:**
- Consumes: `Payment`(getUserId/getPaymentKey/getTotalAmount/getStatus/getCreatedAt/getOrderId), `PaymentItem`(getId/getItemName/getItemAmount/getStatus), `PaymentItemRepository.findAllByPaymentIdOrderByIdAsc(long)`.
- Produces:
  - `PaymentRepository.findByUserId(long userId, int page, int size)` → `List<Payment>`
  - `PaymentHistoryQuery`: `List<PaymentSummaryResponse> list(long userId, int page, int size)`; `PaymentDetailResponse detail(long userId, String paymentKey)`
  - `GET /v1/payments?page=&size=` → `[PaymentSummaryResponse]`; `GET /v1/payments/{paymentKey}` → `PaymentDetailResponse`(비소유 404)

- [ ] **Step 1: 레포 페이지 조회**

`PaymentRepository.java`에 추가:
```java
    /** 주문내역: 본인 결제 최신순 페이지 조회 (P3). */
    java.util.List<Payment> findByUserId(long userId, int page, int size);
```
`PaymentJpaRepository.java`에 파생 쿼리(기존 스타일):
```java
    java.util.List<PaymentJpaEntity> findByUserIdOrderByIdDesc(Long userId, org.springframework.data.domain.Pageable pageable);
```
`PaymentRepositoryImpl.java`에 구현:
```java
    @Override
    public java.util.List<Payment> findByUserId(long userId, int page, int size) {
        return jpa.findByUserIdOrderByIdDesc(userId, org.springframework.data.domain.PageRequest.of(page, size))
            .stream().map(PaymentJpaEntity::toDomain).toList();
    }
```
(실제 JPA→도메인 매핑 메서드명은 기존 `PaymentRepositoryImpl`의 `toDomain` 관례에 맞춘다.)

- [ ] **Step 2: DTO**

`PaymentSummaryResponse.java`:
```java
package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;
import java.util.List;

public record PaymentSummaryResponse(
    String paymentKey, BigDecimal totalAmount, String status, String createdAt,
    long orderId, List<Item> items
) {
    public record Item(long paymentItemId, String itemName, BigDecimal itemAmount, String status) {}

    public static PaymentSummaryResponse from(Payment p, List<PaymentItem> items) {
        return new PaymentSummaryResponse(
            p.getPaymentKey(), p.getTotalAmount(), p.getStatus().name(),
            p.getCreatedAt().toString(), p.getOrderId(),
            items.stream().map(i -> new Item(i.getId(), i.getItemName(), i.getItemAmount(), i.getStatus().name())).toList());
    }
}
```
`PaymentDetailResponse.java` — 동일 필드(상세는 목록 항목과 같게 두되 별도 타입으로 분리; 필요 시 필드 확장). 최소 구현:
```java
package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import java.math.BigDecimal;
import java.util.List;

public record PaymentDetailResponse(
    String paymentKey, BigDecimal totalAmount, String status, String createdAt,
    long orderId, String pgType, List<PaymentSummaryResponse.Item> items
) {
    public static PaymentDetailResponse from(Payment p, List<PaymentItem> items) {
        return new PaymentDetailResponse(
            p.getPaymentKey(), p.getTotalAmount(), p.getStatus().name(), p.getCreatedAt().toString(),
            p.getOrderId(), p.getPgType(),
            items.stream().map(i -> new PaymentSummaryResponse.Item(i.getId(), i.getItemName(), i.getItemAmount(), i.getStatus().name())).toList());
    }
}
```
(`Payment.getCreatedAt()`가 `LocalDateTime`이면 `.toString()` ISO. getter명은 실제 엔티티 확인.)

- [ ] **Step 3: Query UseCase + Service**

`application/usecase/PaymentHistoryQuery.java`:
```java
package com.example.payment.application.usecase;

import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import java.util.List;

public interface PaymentHistoryQuery {
    List<PaymentSummaryResponse> list(long userId, int page, int size);
    PaymentDetailResponse detail(long userId, String paymentKey);
}
```
`application/service/PaymentHistoryService.java`:
```java
package com.example.payment.application.service;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.PaymentHistoryQuery;
import com.example.payment.domain.entity.Payment;
import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentHistoryService implements PaymentHistoryQuery {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentSummaryResponse> list(long userId, int page, int size) {
        // ponytail: 결제당 items 조회(N+1) — 데모 규모 전제. 규모 시 배치 조회로 교체.
        return paymentRepository.findByUserId(userId, page, size).stream()
            .map(p -> PaymentSummaryResponse.from(p, paymentItemRepository.findAllByPaymentIdOrderByIdAsc(p.getId())))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDetailResponse detail(long userId, String paymentKey) {
        Payment p = paymentRepository.findByPaymentKey(paymentKey)
            .filter(pay -> pay.getUserId() == userId)   // 소유 아니면 존재 은닉(404)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
        return PaymentDetailResponse.from(p, paymentItemRepository.findAllByPaymentIdOrderByIdAsc(p.getId()));
    }
}
```
(`PaymentNotFoundException`이 `application/exception/`에 있고 404로 매핑되는지 확인 — CancelAuthorizationService가 이미 사용 중.)

- [ ] **Step 4: 컨트롤러 실패 테스트**

`PaymentHistoryControllerTest.java`(MockMvc standaloneSetup + GlobalExceptionHandler, `PaymentHistoryQuery` mock — 기존 payment 컨트롤러 테스트 스타일):
- `GET /v1/payments` + X-User-Id → 200 + 배열, `page/size` 기본/전달.
- `GET /v1/payments/{key}` 본인 → 200 + 상세.
- `GET /v1/payments/{key}` 타 유저(usecase가 PaymentNotFoundException) → 404.
- 각 호출 X-User-Id 전달 verify.

```java
// 핵심 예시:
@Test void list_returns_user_payments() throws Exception {
    when(query.list(7L, 0, 20)).thenReturn(List.of(
        new PaymentSummaryResponse("pay_x", new BigDecimal("29000"), "COMPLETED", "2026-08-04T00:00", 1L,
            List.of(new PaymentSummaryResponse.Item(3L, "티", new BigDecimal("29000"), "ACTIVE")))));
    mvc.perform(get("/v1/payments").header("X-User-Id", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].paymentKey").value("pay_x"))
        .andExpect(jsonPath("$[0].items[0].paymentItemId").value(3));
}
```

- [ ] **Step 5: 컨트롤러**

`presentation/controller/PaymentHistoryController.java`:
```java
package com.example.payment.presentation.controller;

import com.example.payment.application.usecase.PaymentHistoryQuery;
import com.example.payment.presentation.dto.PaymentDetailResponse;
import com.example.payment.presentation.dto.PaymentSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentHistoryController {

    private final PaymentHistoryQuery paymentHistoryQuery;

    @GetMapping
    public List<PaymentSummaryResponse> list(
        @RequestHeader("X-User-Id") long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return paymentHistoryQuery.list(userId, page, size);
    }

    @GetMapping("/{paymentKey}")
    public PaymentDetailResponse detail(
        @RequestHeader("X-User-Id") long userId,
        @PathVariable String paymentKey) {
        return paymentHistoryQuery.detail(userId, paymentKey);
    }
}
```
> 주의: `GET /v1/payments/{paymentKey}`가 기존 `GET /v1/payments/{paymentKey}/exists`(PaymentController)와 충돌하지 않는지 확인(경로 세그먼트 다름 — 충돌 없음). 두 `@RestController`가 같은 `/v1/payments`를 매핑해도 무방.

- [ ] **Step 6: 테스트 + 전체 회귀**

Run: `./gradlew :payment-service:test --tests "*PaymentHistoryControllerTest"` → PASS.
Run: `./gradlew :payment-service:test` → 전체 PASS(기존 취소 코어 테스트 포함, Testcontainers 수 분 — 포그라운드 대기).

- [ ] **Step 7: 커밋**
```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/PaymentRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentJpaRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/PaymentRepositoryImpl.java \
        payment-service/src/main/java/com/example/payment/application/usecase/PaymentHistoryQuery.java \
        payment-service/src/main/java/com/example/payment/application/service/PaymentHistoryService.java \
        payment-service/src/main/java/com/example/payment/presentation/controller/PaymentHistoryController.java \
        payment-service/src/main/java/com/example/payment/presentation/dto/PaymentSummaryResponse.java \
        payment-service/src/main/java/com/example/payment/presentation/dto/PaymentDetailResponse.java \
        payment-service/src/test/java/com/example/payment/presentation/controller/PaymentHistoryControllerTest.java
git commit -m "feat(payment): 본인 결제 조회 GET /v1/payments (주문내역, 소유 스코프)"
```

---

## Task 2: 백엔드 — 구매자 자가취소 인가 (인가 pre-check만, TX 코어 불변)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/domain/service/CancelAuthorizer.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelAuthorizationService.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/authz/AuthenticatedUser.java` (주석만)
- Test: `payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java`
- Test: `payment-service/src/test/java/com/example/payment/application/service/CancelAuthorizationServiceTest.java`

**Interfaces:**
- Produces: `CancelAuthorizer.authorize(String role, Long requestUserId, Long targetUserId, Long headerMerchantId, Long targetMerchantId)` — USER 소유(requestUserId==targetUserId) 허용.

**주의:** `CancelController`·`CancelPaymentService`·`CancelTxWriter`·스케줄러·outbox는 **건드리지 않는다**. `CancelController`는 이미 `AuthenticatedUser(userId, role, merchantId)`를 넘기므로 무변경.

- [ ] **Step 1: CancelAuthorizerTest 갱신(실패 테스트)**

`CancelAuthorizerTest.java`: 기존 6개 테스트의 `authorize(...)` 호출을 새 5-arg 시그니처로 바꾸고(merchant 인자는 뒤로 이동), USER 케이스를 교체/추가:
- ADMIN: `authorize("ADMIN", null, null, null, 999L)` 통과.
- MERCHANT match: `authorize("MERCHANT", null, null, 7L, 7L)` 통과; mismatch/누락 403.
- **USER 소유**: `authorize("USER", 7L, 7L, null, null)` 통과(신규).
- **USER 타인**: `authorize("USER", 7L, 8L, null, null)` 403(신규).
- **USER requestUserId null**: `authorize("USER", null, 7L, null, null)` 403.
- role 누락: `authorize(null, 7L, 7L, null, null)` 403.
(기존 test (5) `user_forbidden_even_if_owner`는 삭제/치환 — 정책 전환.)

- [ ] **Step 2: 실패 확인**
Run: `./gradlew :payment-service:test --tests "*CancelAuthorizerTest"` → FAIL(시그니처 불일치).

- [ ] **Step 3: CancelAuthorizer 구현**

`domain/service/CancelAuthorizer.java` 교체(정책 주석 갱신 + USER 분기):
```java
package com.example.payment.domain.service;

import com.example.payment.common.exception.domain.CancelNotAuthorizedException;

/**
 * 취소 인가 판정 도메인 규칙 (순수 POJO). primitive 만 받는다.
 * 정책:
 *   - ADMIN                          → 전체 허용
 *   - MERCHANT                       → headerMerchantId == targetMerchantId (둘 다 non-null)
 *   - USER + requestUserId == targetUserId (둘 다 non-null) → 본인 결제 취소 허용 (P3, 정책 전환)
 *   - 그 외(role 누락·불일치·소유 불일치·merchantId 누락) → 403
 */
public class CancelAuthorizer {

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
        if ("USER".equals(role)
                && requestUserId != null && targetUserId != null
                && requestUserId.equals(targetUserId)) {
            return;
        }
        throw new CancelNotAuthorizedException();
    }
}
```

- [ ] **Step 4: CancelAuthorizerTest 통과**
Run: `./gradlew :payment-service:test --tests "*CancelAuthorizerTest"` → PASS.

- [ ] **Step 5: CancelAuthorizationService 구현 + 테스트 갱신**

`application/service/CancelAuthorizationService.java` — USER 경로도 payment 로드해 userId 위임(ADMIN은 로드 생략, USER/MERCHANT 로드):
```java
    @Override
    public void authorize(AuthenticatedUser user, String paymentKey) {
        String role = user.role();

        if ("ADMIN".equals(role)) {
            cancelAuthorizer.authorize(role, null, null, null, null);
            return;
        }

        Long headerMerchantId = parseLong(user.merchantId());
        Long requestUserId = parseLong(user.userId());

        // USER·MERCHANT 경로: 대상 payment read-only 1회 로드 (소유/가맹점 확인)
        Long targetUserId = null;
        Long targetMerchantId = null;
        if ("USER".equals(role) || "MERCHANT".equals(role)) {
            Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentNotFoundException(paymentKey));
            targetUserId = payment.getUserId();
            targetMerchantId = payment.getMerchantId();
        }

        cancelAuthorizer.authorize(role, requestUserId, targetUserId, headerMerchantId, targetMerchantId);
    }

    private Long parseLong(String raw) {
        if (raw == null) return null;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return null; }
    }
```
(기존 `parseMerchantId`를 범용 `parseLong`으로 대체하거나 병존. `payment.getUserId()`는 `long` → autobox `Long`.)

`CancelAuthorizationServiceTest.java` 갱신: USER 본인(payment.userId == header userId) → 통과(payment 로드 stub), USER 타인 → 403, ADMIN 로드 생략, MERCHANT 기존. 기존 "USER→403" 단정 테스트를 소유 기반으로 교체.

`application/authz/AuthenticatedUser.java` 주석 수정: "userId 는 인가에 사용하지 않고 감사 로깅 용도" → "userId 는 USER 자가취소 소유 판정에 사용(P3)".

- [ ] **Step 6: 전체 payment-service 테스트(회귀 — TX 코어 그대로 green)**
Run: `./gradlew :payment-service:test`
Expected: PASS. **취소 TX 코어/멱등/스케줄러/outbox 테스트가 무변경으로 green**인지 확인. USER→403을 단정하던 기존 통합/인가 테스트가 있으면 소유 기반으로 갱신(구현자가 전수 확인).

- [ ] **Step 7: 커밋**
```bash
git add payment-service/src/main/java/com/example/payment/domain/service/CancelAuthorizer.java \
        payment-service/src/main/java/com/example/payment/application/service/CancelAuthorizationService.java \
        payment-service/src/main/java/com/example/payment/application/authz/AuthenticatedUser.java \
        payment-service/src/test/java/com/example/payment/domain/service/CancelAuthorizerTest.java \
        payment-service/src/test/java/com/example/payment/application/service/CancelAuthorizationServiceTest.java
git commit -m "feat(payment): 구매자 자가취소 인가 — USER 소유자 분기 (인가 pre-check, TX 코어 불변)"
```

---

## Task 3: 프론트 — api.js 조회·취소

**Files:** Modify `frontend/src/api.js`

- [ ] **Step 1: 추가**
```js
  getPayments:   ()    => req('/v1/payments'),
  getPayment:    (key) => req(`/v1/payments/${key}`),
  cancelPayment: (key, body) => req(`/v1/payments/${key}/cancel`, { method: 'POST', body, csrf: true }),
```

- [ ] **Step 2: 스모크** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/v1/payments` → 401/403(미인증) 또는 000(게이트웨이 down 허용).

- [ ] **Step 3: 커밋** `git add frontend/src/api.js && git commit -m "feat(history-fe): api.js 결제 조회·취소"`

---

## Task 4: 프론트 — 주문내역 화면 + 네비바 + App

**Files:**
- Create: `frontend/src/components/OrderHistory.jsx`
- Modify: `frontend/src/components/NavBar.jsx`, `frontend/src/App.jsx`, `frontend/src/App.css`

- [ ] **Step 1: OrderHistory.jsx**
```jsx
import { useState } from 'react'

const STATUS_KO = { COMPLETED: '결제완료', CANCELLED: '취소됨', PARTIAL_CANCELLED: '부분취소' }

export default function OrderHistory({ payments, onCancel, onBack }) {
  const [busyKey, setBusyKey] = useState(null)
  async function cancel(p) {
    const reason = window.prompt('취소 사유를 입력하세요', '단순 변심')
    if (reason == null) return
    setBusyKey(p.paymentKey)
    try { await onCancel(p.paymentKey, p.items.map(it => ({ paymentItemId: it.paymentItemId })), reason) }
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
                {p.status === 'COMPLETED' && (
                  <button disabled={busyKey === p.paymentKey} onClick={() => cancel(p)}>
                    {busyKey === p.paymentKey ? '취소 중...' : '취소하기'}
                  </button>
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

- [ ] **Step 2: NavBar.jsx — 주문내역 링크**
`me`일 때 `주문내역` 버튼 추가(`onHistory`). props에 `onHistory` 추가:
```jsx
{me && <button onClick={onHistory}>주문내역</button>}
```
(기존 장바구니(n)·이름·로그아웃 구조 유지.)

- [ ] **Step 3: App.jsx — history 뷰**
```jsx
// import OrderHistory from './components/OrderHistory'
// 상태: const [payments, setPayments] = useState([])
const loadPayments = () => api.getPayments().then(setPayments).catch(() => setPayments([]))
// 진입 시: onHistory={() => { loadPayments(); setView({ name: 'history' }) }}
async function handleCancel(key, items, reason) {
  try { await api.cancelPayment(key, { cancelReason: reason, cancelItems: items }) }
  catch (e) { alert(e.message); return }
  await loadPayments()   // 상태 갱신(CANCELLED 반영)
}
// NavBar: onHistory 전달
// 뷰:
{view.name === 'history' && (
  <OrderHistory payments={payments} onCancel={handleCancel} onBack={() => setView({ name: 'home' })} />
)}
```
(P1/P2 뷰 유지. OrderSuccess에 "주문내역 보기" 링크는 선택.)

- [ ] **Step 4: App.css — history 스타일**
```css
.history { max-width: 680px; margin: 0 auto; padding: 16px; }
.history-list { list-style: none; padding: 0; }
.history-item { border: 1px solid #e5e7eb; border-radius: 10px; padding: 14px; margin-bottom: 12px; }
.history-head { display: flex; justify-content: space-between; align-items: center; }
.history-date { color: #6b7280; font-size: 13px; }
.history-key { font-size: 12px; color: #9ca3af; margin: 4px 0; }
.history-items { list-style: none; padding: 0; margin: 8px 0; }
.history-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.badge { padding: 2px 10px; border-radius: 999px; font-size: 12px; }
.badge.COMPLETED { background: #dcfce7; color: #14532d; }
.badge.CANCELLED { background: #fee2e2; color: #7f1d1d; }
.badge.PARTIAL_CANCELLED { background: #fef9c3; color: #713f12; }
```

- [ ] **Step 5: dev 로드 검증** `cd frontend && npx oxlint src/components/OrderHistory.jsx src/components/NavBar.jsx src/App.jsx` → no errors.

- [ ] **Step 6: 커밋**
```bash
git add frontend/src/components/OrderHistory.jsx frontend/src/components/NavBar.jsx frontend/src/App.jsx frontend/src/App.css
git commit -m "feat(history-fe): 주문내역 화면 + 취소하기 + 네비바 + App 배선"
```

---

## Task 5: E2E — 주문내역 + 자가취소 저니

**Files:** Create `frontend/e2e/history.spec.js`

**Prerequisites:** 인프라 + user·product·order·payment(자가취소 인가·조회)·gateway + vite. payment-service는 **이 브랜치 코드**로 기동. 재고 충분(부족 시 `UPDATE product_stock SET available_qty=500;`).

- [ ] **Step 1: 스펙**
```js
import { test, expect } from '@playwright/test'
const BASE = 'http://localhost:5173', GW = 'http://localhost:8000'
const USER = { email: `hist${Date.now()}@example.com`, password: 'password123', name: '내역유저', phone: '010-6666-7777' }

test.beforeAll(async ({ request }) => { await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {}) })

test('주문내역: 구매 → 내역 → 자가취소 → CANCELLED', async ({ page }) => {
  page.on('dialog', d => d.accept('E2E 취소'))          // window.prompt 자동 수락
  await page.goto(BASE)
  await page.click('.navbar-right button')               // 로그인
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 바로구매로 결제 1건 생성
  await page.click('.grid .card:has-text("베이직 티셔츠")')
  await page.waitForSelector('.buy-btn')
  await page.locator('.qty-input:not([max="0"])').first().fill('1')
  await page.click('.buy-btn')
  await page.click('.checkout .pay-btn')
  await expect(page.locator('.order-success h1')).toContainText('결제 완료')

  // 주문내역 → 취소하기 → CANCELLED
  await page.click('text=쇼핑 계속하기')
  await page.click('text=주문내역')
  await expect(page.locator('.history-item').first()).toBeVisible()
  await expect(page.locator('.history-item .badge').first()).toHaveText('결제완료')
  await page.locator('.history-item button:has-text("취소하기")').first().click()
  await expect(page.locator('.history-item .badge').first()).toHaveText('취소됨', { timeout: 15000 })
})
```

- [ ] **Step 2: 실행** `cd frontend && npx playwright test e2e/history.spec.js` → 1 passed. (실패 시 테스트 셀렉터/타이밍만 조정; src/·백엔드 우회 금지.)

- [ ] **Step 3: 커밋** `git add frontend/e2e/history.spec.js && git commit -m "test(history-fe): 주문내역+자가취소 저니 E2E"`

---

## Task 6: 문서 — 정책 전환 반영

**Files:** Modify `docs/architecture/auth-gateway.html`, `CLAUDE.md`

- [ ] **Step 1:** `docs/architecture/auth-gateway.html`의 payment 인가 role 매트릭스(§4 mermaid + note): `USER·누락 → 403`을 `USER → 본인 결제(payment.userId==요청자)만 허용, 그 외 403`으로 갱신.
- [ ] **Step 2:** `CLAUDE.md` v2.0 인증 경계 문구: "payment 취소는 역할 인가(ADMIN=전체, MERCHANT=본인 가맹점, **USER=본인 결제 자가취소**, 그 외 403)"로 정정.
- [ ] **Step 3: 커밋** `git add docs/architecture/auth-gateway.html CLAUDE.md && git commit -m "docs(cancel): USER 자가취소 정책 전환 반영 (role 매트릭스·CLAUDE)"`

---

## Self-Review 결과

- **Spec 커버리지:** 조회 API(Task1)·자가취소 인가(Task2)·api(Task3)·주문내역 UI(Task4)·E2E(Task5)·문서(Task6) — 전 항목 매핑. TX 코어 무변경(Task2 주의문 + 회귀 green).
- **타입 일관성:** `PaymentSummaryResponse.Item{paymentItemId,itemName,itemAmount,status}` ↔ 프론트 `p.items[].paymentItemId` ↔ cancel `cancelItems:[{paymentItemId}]` 일치. `CancelAuthorizer.authorize` 5-arg(role, requestUserId, targetUserId, headerMerchantId, targetMerchantId) — Service 호출·Test 동일.
- **논-골 준수:** 부분취소 UI·주문/배송 미포함. 취소 실행/멱등/TX/스케줄러/outbox 무변경. 게이트웨이 무변경.
