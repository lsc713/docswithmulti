# 취소 승인 워크플로우 P1 — 백엔드 승인 코어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** payment-service에 취소 **승인 워크플로우** 백엔드를 추가한다 — USER가 사유와 함께 취소를 요청하고, ADMIN/MERCHANT가 검토 후 승인/반려한다. 승인은 기존 취소 실행을 그대로 호출한다.

**Architecture:** 승인은 실행 앞단의 새 레이어. 신규 `cancel_approval` 엔티티(REQUESTED→APPROVED/REJECTED)가 승인 생명주기를 담고, 승인 시 기존 `CancelPaymentUseCase.cancel(CancelPaymentCommand)`를 호출한다. 취소 코어(TX/멱등/스케줄러/outbox)는 무변경 — 승인 서비스는 오늘 클라이언트가 하던 호출을 대신하는 새 호출자일 뿐이다.

**Tech Stack:** Java 21 · Spring Boot 4 / Spring Security 7 · Spring Data JPA · Flyway(MySQL) · JUnit 5 + Mockito + Testcontainers. 게이트웨이 Spring Cloud Gateway.

## Global Constraints

- **취소 코어 byte-for-byte 불변**: `CancelTxWriter`, `CancelPaymentService.cancel`, 스케줄러 3종(pending/processing/compensation-recovery), `cancel_event_outbox`, 멱등(request_hash/dedup_key), `cancel_request`/`payment`/`payment_item` 테이블·로직에 **어떤 변경도 금지**. 신규 코드는 전부 실행 앞단 + 신규 테이블.
- **domain 레이어에 Spring/JPA 어노테이션 금지** (POJO만).
- **Flyway는 새 버전만** — 적용된 마이그레이션 수정 금지. payment-service 최신은 V19 → 신규 **V20**.
- **hex 레이어 준수**: domain/entity · application/interfaces·service·usecase · infrastructure/persistence·config · presentation/controller·dto.
- **RepositoryImpl은 plain 클래스**(어노테이션 없음) + `PersistenceConfig`에 `@Bean` 등록.
- **인가**: 승인/반려는 `ADMIN`(전체)·`MERCHANT`(본인 가맹점 = payment.merchantId == X-Merchant-Id)·`USER`는 403. 요청 생성은 payment 소유 USER(payment.userId == X-User-Id). 기존 `CancelAuthorizer`(직접취소 전용)는 무변경 — 승인용 인가는 별도 헬퍼.
- **v1 범위**: 요청은 **결제 전체 취소** 단위(모든 payment item). 부분취소 승인·자동승인 규칙은 논-골.

---

## File Structure

**신규 (payment-service, `src/main/java/com/example/payment/`)**
- `domain/entity/CancelApprovalStatus.java` — enum REQUESTED/APPROVED/REJECTED
- `domain/entity/CancelApproval.java` — POJO + 상태 전이 규칙
- `application/interfaces/CancelApprovalRepository.java` — 포트
- `application/usecase/CancelApprovalUseCase.java` — 유스케이스 인터페이스
- `application/service/CancelApprovalService.java` — 구현 (request/list/approve/reject)
- `application/authz/ApprovalAuthorizer.java` — 승인자/요청자 인가 판정(POJO)
- `application/service/CancelApprovalCommand.java` — request 커맨드(선택, 인라인 가능)
- `infrastructure/persistence/CancelApprovalJpaEntity.java`
- `infrastructure/persistence/CancelApprovalJpaRepository.java` — Spring Data
- `infrastructure/persistence/CancelApprovalRepositoryImpl.java`
- `presentation/controller/CancelApprovalController.java`
- `presentation/dto/CancelRequestCreateRequest.java`, `CancelRejectRequest.java`, `CancelApprovalResponse.java`, `CancelApprovalListResponse.java`
- `src/main/resources/db/migration/V20__create_cancel_approval.sql`

**수정**
- `infrastructure/config/PersistenceConfig.java` — `cancelApprovalRepository` @Bean 추가

**신규 (api-gateway)**
- `RouteConfig` 수정 — `/v1/cancel-requests/**` + `POST /v1/payments/{key}/cancel-requests` 인증 라우트
- `GatewayRoutingIT` 수정 — 신규 경로 테스트

**테스트**
- `src/test/java/.../application/service/CancelApprovalServiceTest.java`
- `src/test/java/.../domain/entity/CancelApprovalTest.java`
- `src/test/java/.../application/authz/ApprovalAuthorizerTest.java`
- `src/test/java/.../infrastructure/persistence/CancelApprovalRepositoryIT.java`
- `src/test/java/.../presentation/controller/CancelApprovalControllerIT.java`
- `src/test/java/.../integration/CancelApprovalFlowIT.java`

---

### Task 1: 마이그레이션 + 도메인 엔티티 + 상태 enum

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V20__create_cancel_approval.sql`
- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelApprovalStatus.java`
- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelApproval.java`
- Test: `payment-service/src/test/java/com/example/payment/domain/entity/CancelApprovalTest.java`

**Interfaces:**
- Produces: `CancelApprovalStatus{REQUESTED, APPROVED, REJECTED}`; `CancelApproval` with getters `getId, getPaymentId, getPaymentKey, getRequesterUserId, getReason, getStatus, getDecidedByUserId, getDecidedRole, getDecisionReason, getCancelRequestId, getCreatedAt, getUpdatedAt`; factory `CancelApproval.request(paymentId, paymentKey, requesterUserId, reason)`; mutators `approve(deciderUserId, deciderRole, cancelRequestId)` and `reject(deciderUserId, deciderRole, decisionReason)` that throw `IllegalStateException` when status != REQUESTED.

- [ ] **Step 1: Write the failing test**

```java
// CancelApprovalTest.java
package com.example.payment.domain.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancelApprovalTest {
    private CancelApproval requested() {
        return CancelApproval.request(10L, "pay_key_1", 7L, "단순 변심");
    }

    @Test
    void request_starts_in_REQUESTED() {
        CancelApproval a = requested();
        assertEquals(CancelApprovalStatus.REQUESTED, a.getStatus());
        assertEquals(7L, a.getRequesterUserId());
        assertEquals("단순 변심", a.getReason());
        assertNull(a.getCancelRequestId());
    }

    @Test
    void approve_transitions_to_APPROVED_and_links_cancelRequest() {
        CancelApproval a = requested();
        a.approve(99L, "ADMIN", 555L);
        assertEquals(CancelApprovalStatus.APPROVED, a.getStatus());
        assertEquals(99L, a.getDecidedByUserId());
        assertEquals("ADMIN", a.getDecidedRole());
        assertEquals(555L, a.getCancelRequestId());
    }

    @Test
    void reject_transitions_to_REJECTED_with_reason() {
        CancelApproval a = requested();
        a.reject(99L, "MERCHANT", "재고 이미 출고");
        assertEquals(CancelApprovalStatus.REJECTED, a.getStatus());
        assertEquals("재고 이미 출고", a.getDecisionReason());
    }

    @Test
    void cannot_re_decide_after_terminal() {
        CancelApproval a = requested();
        a.approve(99L, "ADMIN", 555L);
        assertThrows(IllegalStateException.class, () -> a.reject(1L, "ADMIN", "x"));
        assertThrows(IllegalStateException.class, () -> a.approve(1L, "ADMIN", 1L));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.domain.entity.CancelApprovalTest'`
Expected: FAIL (compile error — CancelApproval 없음)

- [ ] **Step 3: Write the enum + domain POJO**

```java
// CancelApprovalStatus.java
package com.example.payment.domain.entity;

public enum CancelApprovalStatus {
    REQUESTED, APPROVED, REJECTED;

    public boolean isTerminal() { return this == APPROVED || this == REJECTED; }
}
```

```java
// CancelApproval.java  — POJO, no Spring/JPA
package com.example.payment.domain.entity;

import java.time.Instant;

public class CancelApproval {
    private Long id;
    private long paymentId;
    private String paymentKey;
    private long requesterUserId;
    private String reason;
    private CancelApprovalStatus status;
    private Long decidedByUserId;
    private String decidedRole;
    private String decisionReason;
    private Long cancelRequestId;
    private Instant createdAt;
    private Instant updatedAt;

    protected CancelApproval() {}

    public static CancelApproval request(long paymentId, String paymentKey, long requesterUserId, String reason) {
        CancelApproval a = new CancelApproval();
        a.paymentId = paymentId;
        a.paymentKey = paymentKey;
        a.requesterUserId = requesterUserId;
        a.reason = reason;
        a.status = CancelApprovalStatus.REQUESTED;
        return a;
    }

    /** 영속 로드용 재구성 (RepositoryImpl에서 사용) */
    public static CancelApproval reconstitute(Long id, long paymentId, String paymentKey, long requesterUserId,
            String reason, CancelApprovalStatus status, Long decidedByUserId, String decidedRole,
            String decisionReason, Long cancelRequestId, Instant createdAt, Instant updatedAt) {
        CancelApproval a = new CancelApproval();
        a.id = id; a.paymentId = paymentId; a.paymentKey = paymentKey; a.requesterUserId = requesterUserId;
        a.reason = reason; a.status = status; a.decidedByUserId = decidedByUserId; a.decidedRole = decidedRole;
        a.decisionReason = decisionReason; a.cancelRequestId = cancelRequestId;
        a.createdAt = createdAt; a.updatedAt = updatedAt;
        return a;
    }

    public void approve(long deciderUserId, String deciderRole, long cancelRequestId) {
        requireRequested();
        this.status = CancelApprovalStatus.APPROVED;
        this.decidedByUserId = deciderUserId;
        this.decidedRole = deciderRole;
        this.cancelRequestId = cancelRequestId;
    }

    public void reject(long deciderUserId, String deciderRole, String decisionReason) {
        requireRequested();
        this.status = CancelApprovalStatus.REJECTED;
        this.decidedByUserId = deciderUserId;
        this.decidedRole = deciderRole;
        this.decisionReason = decisionReason;
    }

    private void requireRequested() {
        if (status != CancelApprovalStatus.REQUESTED) {
            throw new IllegalStateException("이미 결정된 승인 요청입니다: " + status);
        }
    }

    public Long getId() { return id; }
    public long getPaymentId() { return paymentId; }
    public String getPaymentKey() { return paymentKey; }
    public long getRequesterUserId() { return requesterUserId; }
    public String getReason() { return reason; }
    public CancelApprovalStatus getStatus() { return status; }
    public Long getDecidedByUserId() { return decidedByUserId; }
    public String getDecidedRole() { return decidedRole; }
    public String getDecisionReason() { return decisionReason; }
    public Long getCancelRequestId() { return cancelRequestId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: Write the migration**

```sql
-- V20__create_cancel_approval.sql
CREATE TABLE cancel_approval (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  payment_id         BIGINT       NOT NULL,
  payment_key        VARCHAR(64)  NOT NULL,
  requester_user_id  BIGINT       NOT NULL,
  reason             VARCHAR(500) NOT NULL,
  status             VARCHAR(20)  NOT NULL,
  decided_by_user_id BIGINT       NULL,
  decided_role       VARCHAR(20)  NULL,
  decision_reason    VARCHAR(500) NULL,
  cancel_request_id  BIGINT       NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_cancel_approval_payment (payment_id),
  KEY idx_cancel_approval_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.domain.entity.CancelApprovalTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/resources/db/migration/V20__create_cancel_approval.sql \
        payment-service/src/main/java/com/example/payment/domain/entity/CancelApproval*.java \
        payment-service/src/test/java/com/example/payment/domain/entity/CancelApprovalTest.java
git commit -m "feat(cancel-approval): V20 + CancelApproval 도메인 엔티티/상태 enum"
```

---

### Task 2: 영속 레이어 (포트 + JPA + Impl + Bean)

**Files:**
- Create: `application/interfaces/CancelApprovalRepository.java`
- Create: `infrastructure/persistence/CancelApprovalJpaEntity.java`
- Create: `infrastructure/persistence/CancelApprovalJpaRepository.java`
- Create: `infrastructure/persistence/CancelApprovalRepositoryImpl.java`
- Modify: `infrastructure/config/PersistenceConfig.java`
- Test: `infrastructure/persistence/CancelApprovalRepositoryIT.java`

**Interfaces:**
- Consumes: `CancelApproval`, `CancelApprovalStatus` (Task 1).
- Produces:
  ```java
  interface CancelApprovalRepository {
      CancelApproval save(CancelApproval approval);
      Optional<CancelApproval> findById(long id);
      Optional<CancelApproval> findActiveRequestedByPaymentId(long paymentId); // status=REQUESTED
      List<CancelApproval> findByStatus(CancelApprovalStatus status);          // 전체(ADMIN)
      List<CancelApproval> findByStatusAndMerchantIds(CancelApprovalStatus status, List<Long> paymentIds); // 스코프
  }
  ```
  > 스코프 필터는 서비스가 merchant 소유 payment_id 집합을 넘겨 조회하거나, 간단히 전체 조회 후 서비스에서 필터한다. **v1은 서비스 필터**로 단순화: 포트는 `findByStatus(status)`만 두고 MERCHANT 스코프는 서비스가 payment merchantId로 필터. (아래 시그니처는 `save/findById/findActiveRequestedByPaymentId/findByStatus`만.)

- [ ] **Step 1: Write the failing repository IT**

기존 `CancelRequestRepository` Testcontainers IT를 템플릿으로 삼는다(같은 패키지의 다른 `*RepositoryIT`/통합 테스트에서 `@DataJpaTest` 또는 Testcontainers 설정 방식을 그대로 따를 것).

```java
// CancelApprovalRepositoryIT.java (Testcontainers MySQL, 기존 통합 테스트 베이스 재사용)
// 검증:
//  - save 후 findById 로 왕복(모든 필드 보존)
//  - REQUESTED 저장 → findActiveRequestedByPaymentId 로 조회됨
//  - 같은 payment 를 approve(APPROVED) 로 저장 → findActiveRequestedByPaymentId 는 empty
//  - findByStatus(REQUESTED) 가 REQUESTED 만 반환
@Test void save_and_find_roundtrip() { /* ... */ }
@Test void findActiveRequested_only_REQUESTED() { /* ... */ }
@Test void findByStatus_filters() { /* ... */ }
```

> 구현자 노트: 기존 payment-service 통합 테스트(`integration/` 하위)의 Testcontainers/Flyway 부트스트랩 방식을 그대로 사용. 새 프레임워크 도입 금지.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalRepositoryIT'`
Expected: FAIL (CancelApprovalRepository/JpaEntity 없음)

- [ ] **Step 3: Write port + JPA entity + Spring Data repo**

```java
// CancelApprovalRepository.java (application/interfaces)
package com.example.payment.application.interfaces;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import java.util.List;
import java.util.Optional;

public interface CancelApprovalRepository {
    CancelApproval save(CancelApproval approval);
    Optional<CancelApproval> findById(long id);
    Optional<CancelApproval> findActiveRequestedByPaymentId(long paymentId);
    List<CancelApproval> findByStatus(CancelApprovalStatus status);
}
```

```java
// CancelApprovalJpaEntity.java (infrastructure/persistence) — @Entity 매핑, toDomain/fromDomain
// 컬럼: id, payment_id, payment_key, requester_user_id, reason, status(EnumType.STRING),
//        decided_by_user_id, decided_role, decision_reason, cancel_request_id, created_at, updated_at
// @PrePersist 로 created_at/updated_at = Instant.now(), @PreUpdate 로 updated_at 갱신.
// toDomain(): CancelApproval.reconstitute(...) 사용.
// static fromDomain(CancelApproval): 신규(id==null)면 새 엔티티, 아니면 필드 반영.
```

```java
// CancelApprovalJpaRepository.java (Spring Data)
package com.example.payment.infrastructure.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CancelApprovalJpaRepository extends JpaRepository<CancelApprovalJpaEntity, Long> {
    Optional<CancelApprovalJpaEntity> findFirstByPaymentIdAndStatus(long paymentId, String status);
    List<CancelApprovalJpaEntity> findByStatus(String status);
}
```

```java
// CancelApprovalRepositoryImpl.java — plain 클래스(어노테이션 없음)
package com.example.payment.infrastructure.persistence;
import com.example.payment.application.interfaces.CancelApprovalRepository;
import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import java.util.List;
import java.util.Optional;

public class CancelApprovalRepositoryImpl implements CancelApprovalRepository {
    private final CancelApprovalJpaRepository jpa;
    public CancelApprovalRepositoryImpl(CancelApprovalJpaRepository jpa) { this.jpa = jpa; }

    @Override public CancelApproval save(CancelApproval a) {
        return jpa.save(CancelApprovalJpaEntity.fromDomain(a)).toDomain();
    }
    @Override public Optional<CancelApproval> findById(long id) {
        return jpa.findById(id).map(CancelApprovalJpaEntity::toDomain);
    }
    @Override public Optional<CancelApproval> findActiveRequestedByPaymentId(long paymentId) {
        return jpa.findFirstByPaymentIdAndStatus(paymentId, CancelApprovalStatus.REQUESTED.name())
                  .map(CancelApprovalJpaEntity::toDomain);
    }
    @Override public List<CancelApproval> findByStatus(CancelApprovalStatus status) {
        return jpa.findByStatus(status.name()).stream().map(CancelApprovalJpaEntity::toDomain).toList();
    }
}
```

- [ ] **Step 4: Register bean in PersistenceConfig**

```java
// PersistenceConfig.java — 기존 @Bean 패턴과 동일하게 추가
@Bean
public CancelApprovalRepository cancelApprovalRepository(CancelApprovalJpaRepository jpaRepository) {
    return new CancelApprovalRepositoryImpl(jpaRepository);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalRepositoryIT'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelApprovalRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelApproval*.java \
        payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java \
        payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelApprovalRepositoryIT.java
git commit -m "feat(cancel-approval): 영속 레이어 + PersistenceConfig bean"
```

---

### Task 3: 승인/요청 인가 헬퍼

**Files:**
- Create: `application/authz/ApprovalAuthorizer.java`
- Test: `application/authz/ApprovalAuthorizerTest.java`

**Interfaces:**
- Consumes: `AuthenticatedUser(String userId, String role, String merchantId)` (기존).
- Produces:
  ```java
  class ApprovalAuthorizer {
      // 승인/반려 가능 여부. 실패 시 도메인 인가 예외 throw.
      void authorizeDecision(AuthenticatedUser user, long targetMerchantId);
      // USER 요청 생성: 소유자 확인. 실패 시 throw.
      void authorizeRequest(AuthenticatedUser user, long paymentOwnerUserId);
  }
  ```
  던지는 예외는 기존 취소 인가가 쓰는 예외와 동일 타입(예: `CancelAuthorizationException` 또는 `application/exception` 하위 인가 예외)을 재사용해 GlobalExceptionHandler가 403으로 변환하도록 한다. 기존 `CancelAuthorizer`/`CancelAuthorizationService`가 던지는 예외 클래스를 그대로 사용.

- [ ] **Step 1: Write the failing test**

```java
// ApprovalAuthorizerTest.java
package com.example.payment.application.authz;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalAuthorizerTest {
    private final ApprovalAuthorizer authz = new ApprovalAuthorizer();

    @Test void admin_can_decide_any() {
        authz.authorizeDecision(new AuthenticatedUser("1", "ADMIN", null), 999L); // no throw
    }
    @Test void merchant_can_decide_own_merchant() {
        authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", "42"), 42L); // no throw
    }
    @Test void merchant_cannot_decide_other_merchant() {
        assertThrows(RuntimeException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("1", "MERCHANT", "42"), 7L));
    }
    @Test void user_cannot_decide() {
        assertThrows(RuntimeException.class, () ->
            authz.authorizeDecision(new AuthenticatedUser("5", "USER", null), 42L));
    }
    @Test void request_owner_ok() {
        authz.authorizeRequest(new AuthenticatedUser("7", "USER", null), 7L); // no throw
    }
    @Test void request_non_owner_rejected() {
        assertThrows(RuntimeException.class, () ->
            authz.authorizeRequest(new AuthenticatedUser("8", "USER", null), 7L));
    }
}
```
> 구현자 노트: `assertThrows(RuntimeException.class, ...)`는 기존 인가 예외의 슈퍼타입에 맞춰 실제 예외 타입으로 좁힐 것.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :payment-service:test --tests '*ApprovalAuthorizerTest'`
Expected: FAIL (ApprovalAuthorizer 없음)

- [ ] **Step 3: Implement ApprovalAuthorizer**

```java
package com.example.payment.application.authz;

// 기존 CancelAuthorizer 가 쓰는 인가 예외를 import 해서 사용.
public class ApprovalAuthorizer {

    public void authorizeDecision(AuthenticatedUser user, long targetMerchantId) {
        String role = user.role();
        if ("ADMIN".equals(role)) return;
        if ("MERCHANT".equals(role) && user.merchantId() != null
                && Long.valueOf(user.merchantId()).equals(targetMerchantId)) return;
        throw /* 기존 인가 예외 */;
    }

    public void authorizeRequest(AuthenticatedUser user, long paymentOwnerUserId) {
        if (user.userId() != null && Long.valueOf(user.userId()).equals(paymentOwnerUserId)) return;
        throw /* 기존 인가 예외 */;
    }
}
```
> merchantId/userId 파싱은 기존 `CancelAuthorizationService`의 파싱 헬퍼(`parseLong`)와 동일 방식으로. malformed → 인가 실패 처리.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :payment-service:test --tests '*ApprovalAuthorizerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/authz/ApprovalAuthorizer.java \
        payment-service/src/test/java/com/example/payment/application/authz/ApprovalAuthorizerTest.java
git commit -m "feat(cancel-approval): 승인/요청 인가 헬퍼"
```

---

### Task 4: CancelApprovalService (request / list / approve / reject)

**Files:**
- Create: `application/usecase/CancelApprovalUseCase.java`
- Create: `application/service/CancelApprovalService.java`
- Test: `application/service/CancelApprovalServiceTest.java`

**Interfaces:**
- Consumes: `CancelApprovalRepository` (T2), `ApprovalAuthorizer` (T3), `PaymentRepository`, `PaymentItemRepository`, `CancelPaymentUseCase`(기존 `cancel(CancelPaymentCommand) → CancelRequest`), `AuthenticatedUser`.
- Produces:
  ```java
  interface CancelApprovalUseCase {
      CancelApproval request(String paymentKey, AuthenticatedUser user, String reason);
      List<CancelApproval> list(AuthenticatedUser user, CancelApprovalStatus status);
      CancelApproval approve(long approvalId, AuthenticatedUser user);
      CancelApproval reject(long approvalId, AuthenticatedUser user, String decisionReason);
  }
  ```
  동작:
  - `request`: `paymentRepository.findByPaymentKey` → 없으면 `PaymentNotFoundException`. `authz.authorizeRequest(user, payment.getUserId())`. `findActiveRequestedByPaymentId(payment.getId())` 있으면 `DuplicateCancelRequestException`(409). `save(CancelApproval.request(payment.getId(), paymentKey, payment.getUserId(), reason))`.
  - `list`: ADMIN → `findByStatus(status)`. MERCHANT → `findByStatus(status)` 후 각 approval의 payment.merchantId == user.merchantId 인 것만 필터(payment 조회). USER → 인가 예외.
  - `approve`: `findById` 없으면 404. `authz.authorizeDecision(user, payment.getMerchantId())`(payment 로드). 상태 REQUESTED 아니면 409(`IllegalStateException` → 409 매핑). payment item 전체 id = `paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId())`의 id 목록. `CancelRequest cr = cancelPaymentUseCase.cancel(new CancelPaymentCommand(paymentKey, approval.getReason(), itemIds, null))`. `approval.approve(parseLong(user.userId()), user.role(), cr.getId())` → save.
  - `reject`: `findById` 없으면 404. `authz.authorizeDecision`. REQUESTED 확인. `approval.reject(...)` → save.

- [ ] **Step 1: Write the failing test (Mockito)**

```java
// CancelApprovalServiceTest.java — 핵심 케이스
// mocks: CancelApprovalRepository, ApprovalAuthorizer(spy 또는 real), PaymentRepository,
//        PaymentItemRepository, CancelPaymentUseCase
@Test void request_creates_REQUESTED_when_owner_and_no_active() { /* verify save REQUESTED */ }
@Test void request_duplicate_active_throws_409() { /* findActiveRequested returns present → DuplicateCancelRequestException */ }
@Test void request_non_owner_throws() { /* authz.authorizeRequest throws */ }
@Test void approve_calls_cancel_and_links_cancelRequestId() {
    // given REQUESTED approval + payment + items[1,2]; cancelPaymentUseCase.cancel returns CancelRequest(id=555)
    // when approve(ADMIN)
    // then verify cancel() called with command{paymentKey, reason, [1,2], null}
    //      and saved approval APPROVED with cancelRequestId=555
}
@Test void approve_non_requested_throws_409() { /* APPROVED approval → IllegalStateException */ }
@Test void approve_merchant_other_merchant_throws() { /* authz.authorizeDecision throws */ }
@Test void approve_user_throws() { /* USER → authz throws, cancel() never called */ }
@Test void reject_sets_REJECTED_and_does_not_call_cancel() {
    // verify cancelPaymentUseCase.cancel NEVER invoked; approval REJECTED with reason
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalServiceTest'`
Expected: FAIL

- [ ] **Step 3: Implement UseCase + Service**

```java
// CancelApprovalUseCase.java — 위 인터페이스 그대로.
// CancelApprovalService.java — @Service @RequiredArgsConstructor, 위 동작 구현.
//   중복 요청 예외: application/exception/DuplicateCancelRequestException (신규, 409 매핑).
//   404: 기존 PaymentNotFoundException / 신규 CancelApprovalNotFoundException.
//   itemIds: findAllByPaymentIdOrderByIdAsc(payment.getId()).stream().map(PaymentItem::getId).toList().
//   parseLong: user.userId() 파싱(기존 방식 재사용).
```
> GlobalExceptionHandler에 `DuplicateCancelRequestException`→409, `IllegalStateException`(재결정)→409, `CancelApprovalNotFoundException`→404 매핑 추가. 기존 핸들러 스타일을 따를 것.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalServiceTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/usecase/CancelApprovalUseCase.java \
        payment-service/src/main/java/com/example/payment/application/service/CancelApprovalService.java \
        payment-service/src/main/java/com/example/payment/application/exception/ \
        payment-service/src/test/java/com/example/payment/application/service/CancelApprovalServiceTest.java
git commit -m "feat(cancel-approval): 요청/승인/반려 서비스 (기존 cancel() 게이트)"
```

---

### Task 5: Controller + DTO + 예외 매핑

**Files:**
- Create: `presentation/controller/CancelApprovalController.java`
- Create: `presentation/dto/CancelRequestCreateRequest.java`, `CancelRejectRequest.java`, `CancelApprovalResponse.java`, `CancelApprovalListResponse.java`
- Modify: `presentation/exception/GlobalExceptionHandler`(신규 예외 매핑 — Task 4에서 넣지 않았다면)
- Test: `presentation/controller/CancelApprovalControllerIT.java` (MockMvc)

**Interfaces:**
- Consumes: `CancelApprovalUseCase` (T4), `AuthenticatedUser`.
- Endpoints (모두 X-User-Role/Id/Merchant-Id 헤더 → AuthenticatedUser):
  - `POST /v1/payments/{paymentKey}/cancel-requests` body `{reason}` → 201 `CancelApprovalResponse{id,status,paymentKey}`.
  - `GET /v1/cancel-requests?status=REQUESTED` → 200 `CancelApprovalListResponse{items:[...]}`.
  - `POST /v1/cancel-requests/{id}/approve` → 200 `CancelApprovalResponse{id,status,cancelRequestId}`.
  - `POST /v1/cancel-requests/{id}/reject` body `{decisionReason}` → 200 `CancelApprovalResponse{id,status}`.

- [ ] **Step 1: Write the failing MockMvc IT**

```java
// CancelApprovalControllerIT.java — @WebMvcTest 또는 기존 컨트롤러 IT 스타일
// (기존 CancelController/PaymentHistoryController 테스트의 셋업 방식 재사용)
// 검증:
//  - POST cancel-requests: X-User-Id 매핑, 201, status=REQUESTED (useCase mock)
//  - GET cancel-requests?status=REQUESTED: 200, items 매핑, ADMIN/MERCHANT 헤더 전달
//  - POST {id}/approve: 200, cancelRequestId 노출
//  - POST {id}/reject: decisionReason 바디 → 200
//  - 중복요청 예외 → 409, 미존재 → 404, 인가예외 → 403 (핸들러 통합)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalControllerIT'`
Expected: FAIL

- [ ] **Step 3: Implement controller + DTOs**

```java
// CancelApprovalController.java
@RestController
@RequiredArgsConstructor
public class CancelApprovalController {
    private final CancelApprovalUseCase useCase;

    @PostMapping("/v1/payments/{paymentKey}/cancel-requests")
    public ResponseEntity<CancelApprovalResponse> request(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelRequestCreateRequest body,
        @RequestHeader(value="X-User-Role", required=false) String role,
        @RequestHeader(value="X-User-Id", required=false) String userId,
        @RequestHeader(value="X-Merchant-Id", required=false) String merchantId) {
        var user = new AuthenticatedUser(userId, role, merchantId);
        var a = useCase.request(paymentKey, user, body.reason());
        return ResponseEntity.status(201).body(CancelApprovalResponse.of(a));
    }

    @GetMapping("/v1/cancel-requests")
    public CancelApprovalListResponse list(
        @RequestParam(defaultValue="REQUESTED") CancelApprovalStatus status,
        @RequestHeader(value="X-User-Role", required=false) String role,
        @RequestHeader(value="X-User-Id", required=false) String userId,
        @RequestHeader(value="X-Merchant-Id", required=false) String merchantId) {
        var user = new AuthenticatedUser(userId, role, merchantId);
        return CancelApprovalListResponse.of(useCase.list(user, status));
    }

    @PostMapping("/v1/cancel-requests/{id}/approve")
    public CancelApprovalResponse approve(@PathVariable long id, /* 헤더 3개 → user */) {
        return CancelApprovalResponse.of(useCase.approve(id, user));
    }

    @PostMapping("/v1/cancel-requests/{id}/reject")
    public CancelApprovalResponse reject(@PathVariable long id,
        @RequestBody @Valid CancelRejectRequest body, /* 헤더 3개 → user */) {
        return CancelApprovalResponse.of(useCase.reject(id, user, body.decisionReason()));
    }
}
```

```java
// DTOs
public record CancelRequestCreateRequest(@NotBlank String reason) {}
public record CancelRejectRequest(@NotBlank String decisionReason) {}
public record CancelApprovalResponse(long id, String paymentKey, String status,
        Long cancelRequestId, String reason, String decisionReason) {
    public static CancelApprovalResponse of(CancelApproval a) { /* map */ }
}
public record CancelApprovalListResponse(List<CancelApprovalResponse> items) {
    public static CancelApprovalListResponse of(List<CancelApproval> l) { /* map */ }
}
```
> GlobalExceptionHandler에 신규 예외→상태코드 매핑이 없으면 여기서 추가(409/404). 인가 예외는 기존 매핑(403) 재사용.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalControllerIT'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/presentation/ \
        payment-service/src/test/java/com/example/payment/presentation/controller/CancelApprovalControllerIT.java
git commit -m "feat(cancel-approval): REST 컨트롤러 + DTO + 예외 매핑"
```

---

### Task 6: 종단간 통합 테스트 (승인 → 실제 취소 실행 / 반려 → 불변)

**Files:**
- Create: `integration/CancelApprovalFlowIT.java`

**Interfaces:**
- Consumes: 전체 스프링 컨텍스트(Testcontainers MySQL) + risk/PG 스텁. 기존 취소 통합 테스트(`integration/` 하위, 예: 취소 성공 플로우 IT)의 `RiskManagementPort`/`PgCancelPort` 스텁·부트스트랩 방식을 그대로 재사용.

- [ ] **Step 1: Write the E2E test**

```java
// CancelApprovalFlowIT.java
// given: COMPLETED payment(userId=7, merchantId=42) + payment_items 시드(기존 취소 IT의 시드 방식 재사용)
// 시나리오 A (승인 → 실행):
//   1. POST /v1/payments/{key}/cancel-requests (X-User-Id=7) → 201 REQUESTED
//   2. POST /v1/cancel-requests/{id}/approve (X-User-Role=ADMIN) → 200 APPROVED, cancelRequestId != null
//   3. assert: cancel_request 행 생성됨(COMPLETED) + payment 상태 CANCELLED + cancel_approval.cancel_request_id 링크
// 시나리오 B (반려 → 불변):
//   1. POST cancel-requests (X-User-Id=7) → REQUESTED
//   2. POST {id}/reject (ADMIN, decisionReason) → REJECTED
//   3. assert: payment 여전히 COMPLETED, cancel_request 없음
//   4. 재요청: POST cancel-requests (X-User-Id=7) → 다시 201 REQUESTED (재요청 허용)
// 시나리오 C (스코프): merchantId=99 MERCHANT 헤더로 approve → 403
```
> 취소 코어 무변경 증명: 승인 경로가 기존 cancel 실행을 정확히 통과함을 확인. risk/PG는 기존 IT와 동일 스텁(성공 응답)으로.

- [ ] **Step 2: Run — expect PASS (구현이 이미 앞 태스크에서 완료됨)**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalFlowIT'`
Expected: PASS. 실패 시 앞 태스크 회귀 — 실행 경로/스텁 조정.

- [ ] **Step 3: Full module test (회귀 — 취소 코어 무변경 확인)**

Run: `./gradlew :payment-service:test`
Expected: 전체 PASS (기존 취소/멱등/스케줄러 테스트 모두 green).

- [ ] **Step 4: Commit**

```bash
git add payment-service/src/test/java/com/example/payment/integration/CancelApprovalFlowIT.java
git commit -m "test(cancel-approval): 승인→실행 / 반려→불변 종단간 통합"
```

---

### Task 7: 게이트웨이 라우트 + 라우팅 IT

**Files:**
- Modify: `api-gateway/.../config/RouteConfig.java` (또는 라우트 정의 위치)
- Modify: `api-gateway/.../GatewayRoutingIT.java`

**Interfaces:**
- Consumes: 기존 `JwtTrustHeaderFilter`(JWT 검증 + X-User-* strip/주입) 라우트 패턴.
- Produces: `/v1/cancel-requests/**` + `POST /v1/payments/{key}/cancel-requests` 인증 라우트(payment-service로). 기존 `/v1/payments/**` 라우트와 순서·predicate 충돌 없이.

- [ ] **Step 1: Write failing routing IT**

```java
// GatewayRoutingIT.java 에 추가
// - GET /v1/cancel-requests (no token) → 401
// - GET /v1/cancel-requests (valid JWT) → 라우팅됨 + X-User-Id 주입 확인(기존 테스트 검증 방식 재사용)
// - POST /v1/payments/{key}/cancel-requests (valid JWT) → 라우팅 + CSRF 요건(기존 변경계열 테스트 방식)
```

- [ ] **Step 2: Run — expect FAIL**

Run: `./gradlew :api-gateway:test --tests '*GatewayRoutingIT'`
Expected: FAIL (라우트 없음 → 404/401 불일치)

- [ ] **Step 3: Add route**

```java
// RouteConfig — 기존 인증 라우트와 동일 필터 체인.
// /v1/cancel-requests/** 를 payment-service 로. javadoc 갱신.
// POST /v1/payments/{key}/cancel-requests 는 기존 /v1/payments/** 라우트가 이미 커버하는지 확인 —
//   커버되면 별도 predicate 불필요(cancel-requests 는 payment 서비스 동일 대상). javadoc에 명시.
```
> 확인 포인트: `/v1/payments/**`가 이미 payment-service로 가면 `POST .../cancel-requests`는 자동 커버. `/v1/cancel-requests/**`만 신규 추가하면 됨.

- [ ] **Step 4: Run — expect PASS**

Run: `./gradlew :api-gateway:test --tests '*GatewayRoutingIT'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api-gateway/src/main/java/ api-gateway/src/test/java/
git commit -m "feat(cancel-approval): 게이트웨이 /v1/cancel-requests 인증 라우트 + IT"
```

---

## Self-Review 체크

- **Spec 커버리지**: 요청생성(T4/T5) · 승인큐 조회(T4/T5) · 승인→실행(T4/T6) · 반려+재요청(T4/T6) · 인가 매트릭스(T3/T4/T6) · V20 테이블(T1) · 게이트웨이(T7) — 모두 태스크 존재.
- **취소 코어 불변**: 신규 파일만 추가 + `PersistenceConfig`(bean 1개)·`GlobalExceptionHandler`(매핑)·게이트웨이 라우트만 수정. `CancelPaymentService`/`CancelTxWriter`/스케줄러/outbox/`cancel_request`·`payment` 미변경. T6 Step 3 전체 테스트로 회귀 확인.
- **타입 일관성**: `CancelPaymentCommand(paymentKey, cancelReason, cancelPaymentItemIds, idempotencyKey)`·`cancel()→CancelRequest`·`CancelRequest.getId()`·`AuthenticatedUser(userId,role,merchantId)`·`PaymentItemRepository.findAllByPaymentIdOrderByIdAsc` 모두 실제 시그니처와 일치.
- **전이 상태**: USER 직접취소 제거는 이 계획 논-골(P3에서). P1 후 USER는 직접취소·요청 병존 — 의도됨.
```
