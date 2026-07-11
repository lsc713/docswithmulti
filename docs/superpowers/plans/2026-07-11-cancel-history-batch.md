# 취소 이력 배치 (커밋 6→4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 취소 1건의 이력 3 커밋(`cancel_request_history` 상태전이별 `REQUIRES_NEW`)을 1 배치 커밋으로 줄여, 취소당 커밋을 6→4로 낮춘다(커넥션 점유·fsync 감소 → 처리량 천장 상승).

**Architecture:** 이력을 전이 순간마다 ThreadLocal 버퍼에 시각과 함께 적재하고, `cancel()` 종료 시 `finally`에서 한 번에 `REQUIRES_NEW` 트랜잭션으로 일괄 INSERT. 코어 TX(TX1/2/3)와 복구 서비스는 무변경.

**Tech Stack:** Java 21 · Spring(@Component, @Transactional REQUIRES_NEW) · Spring Data JPA(saveAll) · JUnit 5 + Mockito · Testcontainers

## Global Constraints

- **코어 TX 경계(CancelTxWriter saveTx1/2/3) 변경 금지** — 외부 호출로 분리돼 있어 병합 금지(HTTP-in-TX 시한폭탄).
- **이력은 코어 TX 밖 + best-effort** — `@Transactional(REQUIRES_NEW)`, 실패 시 예외 삼킴·로그, 비즈니스 영향 0 (CLAUDE.md 불변식).
- **self-invocation 금지** — `REQUIRES_NEW`는 레포지토리 impl 메서드에 두고 다른 빈이 프록시로 호출(기존 `record()`와 동일 구조).
- **복구 서비스(Pending/ProcessingRecoveryService) 손대지 않음** — 단건 `record()` 유지.
- **전이 시각 보존** — 배치해도 각 이력의 `created_at`은 전이가 일어난 시각(add 시점 캡처)이어야 함.
- 도메인 레이어에 Spring/JPA 금지(값객체 `CancelHistoryEntry`는 순수 record).

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `application/interfaces/CancelHistoryEntry.java` | 버퍼 항목 값객체(id/status/reason/occurredAt) | 신규 |
| `application/interfaces/CancelRequestHistoryRepository.java` | `recordAll` 추가 | 수정 |
| `infrastructure/persistence/CancelRequestHistoryJpaEntity.java` | createdAt 오버로드 + getter | 수정 |
| `infrastructure/persistence/CancelRequestHistoryRepositoryImpl.java` | `recordAll` 구현(REQUIRES_NEW, saveAll) | 수정 |
| `application/service/CancelHistoryRecorder.java` | ThreadLocal 버퍼 + flush | 신규 |
| `application/service/CancelPaymentService.java` | recordHistory→recorder.add, cancel() try/finally flush | 수정 |

---

## Task 1: 값객체 + 배치 레포지토리 (recordAll)

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelHistoryEntry.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestHistoryRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryJpaEntity.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryRepositoryImpl.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryRepositoryImplTest.java`

**Interfaces:**
- Produces: `record CancelHistoryEntry(long cancelRequestId, CancelStatus status, String reason, Instant occurredAt)`; `CancelRequestHistoryRepository.recordAll(List<CancelHistoryEntry>)`; `CancelRequestHistoryJpaEntity.of(long,String,String,Instant)` + getters `getCancelRequestId/getStatus/getReason/getCreatedAt`.

- [ ] **Step 1: 값객체 생성**

`CancelHistoryEntry.java`:
```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;

/** 이력 배치 기록용 버퍼 항목. occurredAt은 상태전이 순간에 캡처된 시각. */
public record CancelHistoryEntry(
    long cancelRequestId, CancelStatus status, String reason, Instant occurredAt
) {}
```

- [ ] **Step 2: 엔티티에 오버로드 + getter 추가**

`CancelRequestHistoryJpaEntity.java` — 기존 `of(long,String,String)`를 새 오버로드에 위임하도록 바꾸고, 4-인자 오버로드와 getter를 추가. 파일 전체를 아래로 교체:
```java
package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cancel_request_history")
public class CancelRequestHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CancelRequestHistoryJpaEntity() {}

    public static CancelRequestHistoryJpaEntity of(long cancelRequestId, String status, String reason) {
        return of(cancelRequestId, status, reason, Instant.now());
    }

    public static CancelRequestHistoryJpaEntity of(
        long cancelRequestId, String status, String reason, Instant createdAt
    ) {
        CancelRequestHistoryJpaEntity e = new CancelRequestHistoryJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.status = status;
        e.reason = reason;
        e.createdAt = createdAt;
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: 레포지토리 인터페이스에 recordAll 추가**

`CancelRequestHistoryRepository.java` 전체를 교체:
```java
package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;
import java.util.List;

public interface CancelRequestHistoryRepository {
    /** TX 밖에서 별도 INSERT (단건). 실패해도 비즈니스 로직에 영향 없음. */
    void record(long cancelRequestId, CancelStatus status, String reason);

    /** 여러 이력을 단일 트랜잭션(REQUIRES_NEW)으로 일괄 INSERT — 커밋 1개. */
    void recordAll(List<CancelHistoryEntry> entries);
}
```

- [ ] **Step 4: 실패 테스트 작성 (recordAll 매핑)**

`CancelRequestHistoryRepositoryImplTest.java` 생성:
```java
package com.example.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelRequestHistoryRepositoryImplTest {

    @Mock CancelRequestHistoryJpaRepository jpaRepository;
    @InjectMocks CancelRequestHistoryRepositoryImpl sut;
    @Captor ArgumentCaptor<List<CancelRequestHistoryJpaEntity>> captor;

    @Test
    void recordAll_maps_entries_preserving_occurredAt_and_saves_once() {
        Instant t1 = Instant.parse("2026-07-11T00:00:01Z");
        Instant t2 = Instant.parse("2026-07-11T00:00:02Z");
        sut.recordAll(List.of(
            new CancelHistoryEntry(7L, CancelStatus.PENDING, null, t1),
            new CancelHistoryEntry(7L, CancelStatus.COMPLETED, "done", t2)
        ));

        verify(jpaRepository).saveAll(captor.capture());
        List<CancelRequestHistoryJpaEntity> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(rows.get(0).getCreatedAt()).isEqualTo(t1);
        assertThat(rows.get(1).getStatus()).isEqualTo("COMPLETED");
        assertThat(rows.get(1).getReason()).isEqualTo("done");
        assertThat(rows.get(1).getCreatedAt()).isEqualTo(t2);
    }
}
```

- [ ] **Step 5: 테스트 실행 → 실패 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.infrastructure.persistence.CancelRequestHistoryRepositoryImplTest'`
Expected: 컴파일 실패 또는 FAIL — `recordAll`가 impl에 아직 없음.

- [ ] **Step 6: recordAll 구현**

`CancelRequestHistoryRepositoryImpl.java` 전체를 교체:
```java
package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이력 기록은 항상 별도 트랜잭션으로 실행 (REQUIRES_NEW).
 * 실패해도 비즈니스 TX에 영향을 주지 않는다.
 */
@Repository
public class CancelRequestHistoryRepositoryImpl implements CancelRequestHistoryRepository {

    private final CancelRequestHistoryJpaRepository jpaRepository;

    public CancelRequestHistoryRepositoryImpl(CancelRequestHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long cancelRequestId, CancelStatus status, String reason) {
        jpaRepository.save(
            CancelRequestHistoryJpaEntity.of(cancelRequestId, status.name(), reason)
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAll(List<CancelHistoryEntry> entries) {
        jpaRepository.saveAll(
            entries.stream()
                .map(e -> CancelRequestHistoryJpaEntity.of(
                    e.cancelRequestId(), e.status().name(), e.reason(), e.occurredAt()))
                .toList()
        );
    }
}
```

- [ ] **Step 7: 테스트 실행 → 통과 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.infrastructure.persistence.CancelRequestHistoryRepositoryImplTest'`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelHistoryEntry.java \
        payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestHistoryRepository.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryJpaEntity.java \
        payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryRepositoryImpl.java \
        payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestHistoryRepositoryImplTest.java
git commit -m "feat(cancel): 이력 배치 기록 recordAll(REQUIRES_NEW) + 전이시각 보존 오버로드"
```

---

## Task 2: CancelHistoryRecorder (ThreadLocal 버퍼 + flush)

**Files:**
- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelHistoryRecorder.java`
- Test: `payment-service/src/test/java/com/example/payment/application/service/CancelHistoryRecorderTest.java`

**Interfaces:**
- Consumes: `CancelRequestHistoryRepository.recordAll(List<CancelHistoryEntry>)` (Task 1).
- Produces: `CancelHistoryRecorder.add(long cancelRequestId, CancelStatus status, String reason)`; `CancelHistoryRecorder.flush()`.

- [ ] **Step 1: 실패 테스트 작성**

`CancelHistoryRecorderTest.java` 생성:
```java
package com.example.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelHistoryRecorderTest {

    @Mock CancelRequestHistoryRepository repository;
    @Captor ArgumentCaptor<List<CancelHistoryEntry>> captor;

    @Test
    void flush_writes_buffered_entries_once_then_clears() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.add(1L, CancelStatus.PENDING, null);
        recorder.add(1L, CancelStatus.PROCESSING, null);
        recorder.add(1L, CancelStatus.COMPLETED, null);

        recorder.flush();

        verify(repository, times(1)).recordAll(captor.capture());
        assertThat(captor.getValue()).extracting(CancelHistoryEntry::status)
            .containsExactly(CancelStatus.PENDING, CancelStatus.PROCESSING, CancelStatus.COMPLETED);

        // 두 번째 flush는 버퍼가 비어 recordAll을 호출하지 않는다 (버퍼 정리 확인)
        recorder.flush();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void flush_with_empty_buffer_does_not_call_repository() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.flush();
        verifyNoInteractions(repository);
    }

    @Test
    void flush_swallows_repository_exception() {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        doThrow(new RuntimeException("db down")).when(repository).recordAll(anyList());
        recorder.add(1L, CancelStatus.PENDING, null);

        recorder.flush(); // 예외 전파 없이 반환해야 한다 (best-effort)

        // 버퍼는 정리됐다 — 이후 flush는 no-op
        recorder.flush();
        verify(repository, times(1)).recordAll(anyList());
    }

    @Test
    void buffers_are_isolated_per_thread() throws Exception {
        CancelHistoryRecorder recorder = new CancelHistoryRecorder(repository);
        recorder.add(1L, CancelStatus.PENDING, null);

        Thread other = new Thread(() -> recorder.add(2L, CancelStatus.COMPLETED, null));
        other.start();
        other.join();

        recorder.flush(); // 현재 스레드 버퍼(1건)만 flush
        verify(repository).recordAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).cancelRequestId()).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.application.service.CancelHistoryRecorderTest'`
Expected: 컴파일 실패 — `CancelHistoryRecorder` 없음.

- [ ] **Step 3: CancelHistoryRecorder 구현**

`CancelHistoryRecorder.java` 생성:
```java
package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelHistoryEntry;
import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 취소 1건 동안 이력을 ThreadLocal 버퍼에 모아 종료 시 한 번에 기록한다(커밋 1개).
 * add는 전이 순간의 시각을 캡처만 하고(메모리), flush가 REQUIRES_NEW 배치 INSERT를 위임한다.
 * 실패는 삼킨다(best-effort) — 비즈니스에 영향 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelHistoryRecorder {

    private final CancelRequestHistoryRepository historyRepository;
    private final ThreadLocal<List<CancelHistoryEntry>> buffer =
        ThreadLocal.withInitial(ArrayList::new);

    public void add(long cancelRequestId, CancelStatus status, String reason) {
        buffer.get().add(new CancelHistoryEntry(cancelRequestId, status, reason, Instant.now()));
    }

    public void flush() {
        List<CancelHistoryEntry> entries = buffer.get();
        try {
            if (!entries.isEmpty()) {
                historyRepository.recordAll(List.copyOf(entries));
            }
        } catch (Exception e) {
            log.warn("이력 배치 기록 실패 (비즈니스 영향 없음). count={}", entries.size(), e);
        } finally {
            buffer.remove();
        }
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.application.service.CancelHistoryRecorderTest'`
Expected: PASS (4개 테스트).

- [ ] **Step 5: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelHistoryRecorder.java \
        payment-service/src/test/java/com/example/payment/application/service/CancelHistoryRecorderTest.java
git commit -m "feat(cancel): CancelHistoryRecorder — ThreadLocal 버퍼 + flush(배치 1커밋)"
```

---

## Task 3: CancelPaymentService 배선 (recordHistory→recorder.add, flush)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java`

**Interfaces:**
- Consumes: `CancelHistoryRecorder.add(long,CancelStatus,String)`, `CancelHistoryRecorder.flush()` (Task 2).

- [ ] **Step 1: 서비스 배선 변경**

`CancelPaymentService.java`에서:

(a) 필드 교체 — 기존
```java
    private final CancelRequestHistoryRepository historyRepository;
```
를 아래로 교체(같은 위치 유지 → 생성자 인자 순서 보존):
```java
    private final CancelHistoryRecorder cancelHistoryRecorder;
```
그리고 이제 미사용이 된 import는 정리(필요 시 `CancelRequestHistoryRepository` import 제거).

(b) `recordHistory` 위임으로 교체 — 기존
```java
    private void recordHistory(Long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("이력 기록 실패 (비즈니스 영향 없음). cancelRequestId={}", cancelRequestId, e);
        }
    }
```
를 아래로 교체:
```java
    private void recordHistory(Long cancelRequestId, CancelStatus status, String reason) {
        cancelHistoryRecorder.add(cancelRequestId, status, reason);
    }
```

(c) `cancel()` 본문을 try/finally로 감싸 flush 보장 — 기존
```java
    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        // Step 1. Payment / PaymentItem 조회
        Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
            .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));

        List<PaymentItem> items =
            paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());

        // Step 2. request_hash 생성 및 멱등성 체크
        String requestHash = RequestHashGenerator.generate(
            command.paymentKey(), command.cancelPaymentItemIds());

        var existing = cancelRequestRepository.findByPaymentIdAndRequestHash(
            payment.getId(), requestHash);

        if (existing.isPresent()) {
            return handleExistingRequest(existing.get(), command, payment, items);
        }

        return executeCancel(payment, items, requestHash, command);
    }
```
를 아래로 교체(로직 동일, try/finally 래핑만 추가):
```java
    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        try {
            // Step 1. Payment / PaymentItem 조회
            Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));

            List<PaymentItem> items =
                paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());

            // Step 2. request_hash 생성 및 멱등성 체크
            String requestHash = RequestHashGenerator.generate(
                command.paymentKey(), command.cancelPaymentItemIds());

            var existing = cancelRequestRepository.findByPaymentIdAndRequestHash(
                payment.getId(), requestHash);

            if (existing.isPresent()) {
                return handleExistingRequest(existing.get(), command, payment, items);
            }

            return executeCancel(payment, items, requestHash, command);
        } finally {
            cancelHistoryRecorder.flush();
        }
    }
```

- [ ] **Step 2: 서비스 테스트 배선 수정**

`CancelPaymentServiceTest.java`에서:

(a) mock 교체 — 기존
```java
    @Mock CancelRequestHistoryRepository historyRepository;
```
를:
```java
    @Mock CancelHistoryRecorder cancelHistoryRecorder;
```
필요 import 추가: `import com.example.payment.application.service.CancelHistoryRecorder;`(동일 패키지면 생략 가능), 미사용 시 `CancelRequestHistoryRepository` import 정리.

(b) 생성자 호출의 `historyRepository` 인자를 `cancelHistoryRecorder`로 교체 (line ~54-56의 `new CancelPaymentService(...)`에서 같은 위치의 인자만 치환).

(c) 이력 실패-삼킴 테스트 제거 — `doThrow(...).when(historyRepository).record(anyLong(), any(), any())`를 스텁하던 테스트 메서드(파일 하단, line ~477 부근)를 **삭제**한다. 그 동작(이력 기록 실패해도 비즈니스 성공)은 이제 `CancelHistoryRecorderTest.flush_swallows_repository_exception`이 커버한다.

- [ ] **Step 3: 배선 검증 테스트 추가**

`CancelPaymentServiceTest.java`의 정상 취소 성공 테스트(risk·pg·saveTx1/2/3를 검증하는 기존 테스트, line ~130 부근)에 아래 검증을 추가:
```java
        // 이력은 버퍼에 3회 적재되고, 종료 시 1회 flush 된다
        verify(cancelHistoryRecorder).add(anyLong(), eq(CancelStatus.PENDING), any());
        verify(cancelHistoryRecorder).add(anyLong(), eq(CancelStatus.PROCESSING), any());
        verify(cancelHistoryRecorder).add(anyLong(), eq(CancelStatus.COMPLETED), any());
        verify(cancelHistoryRecorder).flush();
```
(`import static org.mockito.ArgumentMatchers.eq;`, `import static org.mockito.ArgumentMatchers.any;`, `import static org.mockito.ArgumentMatchers.anyLong;`가 이미 있는지 확인 — 기존 테스트가 사용 중이므로 대개 존재.)

- [ ] **Step 4: 서비스 테스트 실행 → 통과 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.application.service.CancelPaymentServiceTest'`
Expected: PASS. 실패 시 생성자 인자 위치/순서를 서비스 필드 선언 순서와 대조해 맞춘다.

- [ ] **Step 5: 취소 플로우 통합 테스트 회귀 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.integration.CancelFlowIntegrationTest'`
Expected: PASS. 이 테스트는 `cancel()` 반환 후 DB 상태를 검증 — 배치 flush가 `cancel()` 종료 시 실행되므로 반환 시점엔 이력 3행이 이미 커밋돼 있어 기존 단언이 유지된다. 만약 이력 기록 "시점"(TX 사이 중간)을 가정한 단언이 있어 실패하면, 그 단언을 "`cancel()` 반환 후 이력 3행 존재"로 조정한다(배치 설계의 의도된 동작 — 기록은 종료 시 1회).

- [ ] **Step 6: 모듈 전체 회귀 확인**

Run: `./gradlew :payment-service:test`
Expected: PASS (복구 서비스 테스트 포함 — 단건 `record()` 경로 무변경).

- [ ] **Step 7: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java \
        payment-service/src/test/java/com/example/payment/application/service/CancelPaymentServiceTest.java
git commit -m "feat(cancel): CancelPaymentService가 이력을 recorder 버퍼로 모아 종료 시 1커밋 flush"
```

---

## Self-Review

- **Spec coverage:** CancelHistoryEntry(T1) ✓ / recordAll REQUIRES_NEW 1커밋(T1) ✓ / 엔티티 createdAt 오버로드·전이시각 보존(T1) ✓ / CancelHistoryRecorder ThreadLocal+flush best-effort(T2) ✓ / 단건 record 유지·복구 무변경(T1 유지, T3 Step6) ✓ / 서비스 recordHistory→add + cancel try/finally flush(T3) ✓ / self-invocation 없음(recorder→repo 프록시) ✓ / 코어 TX 무변경(T3는 cancel()만 래핑) ✓.
- **Placeholder scan:** 모든 코드/테스트 전문 포함, TBD 없음.
- **Type consistency:** `CancelHistoryEntry(long,CancelStatus,String,Instant)` · `recordAll(List<CancelHistoryEntry>)` · `add(long,CancelStatus,String)` · `flush()` · 엔티티 `of(long,String,String,Instant)`/getters — 전 Task 동일 표기. 서비스 필드명 `cancelHistoryRecorder` T3 일관.
- **검증(OTel):** 스펙의 "다음 런 OTel 켜서 커밋4·rps 재측정"은 코드 범위 밖(실측 운영) — 이 플랜은 코드까지. 메모리 open loop로 이미 기록됨.
