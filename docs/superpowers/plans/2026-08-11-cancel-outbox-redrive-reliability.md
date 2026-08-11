# Cancel Outbox Redrive Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Terminate cancel-outbox redrive publish/convergence failures safely, recover crash-stalled work, cap per-instance execution at five, and expose bounded operational evidence without leaking payloads or operator data.

**Architecture:** Extend the existing DB-backed redrive state machine with phase-guarded FAILED transitions and separate recent/deadline scans. All blocking replay and downstream inspection work runs on a queue-free per-instance executor; schedulers only scan and submit. A focused telemetry boundary emits structured lifecycle logs and bounded Micrometer metrics, while failed attempts remain immutable history and a new request always re-inspects current state.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring JDBC, Spring Kafka, Spring TaskExecutor, Jackson 3, Resilience4j, Micrometer, MySQL 8 Testcontainers, Kafka Testcontainers, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Work only in `/Users/juho/Documents/docswithmulti/.worktrees/cancel-outbox-redrive-reliability` on `feature/cancel-outbox-redrive-reliability`.
- Follow strict red-green-refactor: every production behavior must have a test that was observed failing for the intended reason before implementation.
- Preserve the source `cancel_event_outbox` row and exact raw replay key/payload; never deserialize/re-serialize before replay.
- Only the `REQUESTED -> REDRIVING` DB CAS winner may inspect or replay a job.
- Preflight UNKNOWN is `FAILED/PUBLISH` with `PREFLIGHT_UNKNOWN`; post-ACK UNKNOWN waits until the 60-second deadline and then becomes `FAILED/CONVERGENCE`.
- `failPublish` requires `REDRIVING AND result IS NULL`; `failConvergence` requires `REDRIVING AND result IS NOT NULL`.
- ACK-before-state-save uncertainty is never automatically replayed under the same ID; after 60 seconds it becomes `FAILED/PUBLISH/PUBLISH_STATE_UNKNOWN` and an operator may create a new ID.
- Per-instance actual task concurrency defaults to 5; cluster-wide aggregate concurrency is not constrained.
- Executor queue capacity is 0. Rejected submissions leave DB state unchanged for the next poll.
- Never put operator reason, raw payload, payment key, redrive ID, source outbox ID, exception message, or error code in metric tags.
- Logs may contain `redriveId` and `sourceOutboxId` but never operator reason, raw payload, payment key, or exception message.
- Integration tests share MySQL/Kafka containers and one Spring ApplicationContext per test class; never restart them per test method.
- Before each commit run the task-focused tests and `git diff --check`.

---

### Task 1: Phase-guarded failure persistence and deadline queries

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveFailureCode.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java`
- Modify: `payment-service/src/test/java/com/example/payment/domain/entity/CancelOutboxRedriveTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveMigrationIT.java`

**Interfaces:**

- Consumes: existing `CancelOutboxRedrive`, `CancelOutboxRedriveFailureStage`, row mapper, and `(status, started_at, id)` index.
- Produces:

```java
public enum CancelOutboxRedriveFailureCode {
    PREFLIGHT_UNKNOWN,
    KAFKA_TIMEOUT,
    KAFKA_SEND_FAILED,
    PUBLISH_STATE_UNKNOWN,
    CONVERGENCE_TIMEOUT,
    DOWNSTREAM_UNKNOWN,
    INCONSISTENT_DOWNSTREAM_STATE
}

boolean failPublish(long redriveId, String lastError, String beforeState, Instant completedAt);
boolean failConvergence(long redriveId, String lastError, String afterState, Instant completedAt);
List<CancelOutboxRedrive> findExpiredUnpublished(Instant cutoff, int limit);
List<CancelOutboxRedrive> findExpiredPublished(Instant cutoff, int limit);
```

- [ ] **Step 1: Write failing repository tests**

Add tests with literal timestamps and JSON proving:

- `failPublish` fails on REQUESTED and on ACK-bearing REDRIVING, succeeds once on `REDRIVING/result=null`, sets `FAILED/PUBLISH`, `last_error`, `before_state`, `completed_at`, and returns false thereafter.
- `failConvergence` fails before ACK, succeeds once after `recordPublished`, preserves ACK/before state, writes `FAILED/CONVERGENCE`, `after_state`, `last_error`, `completed_at`, and returns false thereafter.
- cutoff `2026-08-11T06:01:00Z` includes rows started exactly at cutoff in expired queries and excludes `cutoff.plusNanos(1000)`.
- unpublished and published expired queries never return each other's phase and exclude terminal rows.
- both queries reject limit 0 and -1 before SQL execution.
- the existing normal `findConverging` query uses strict `started_at > :startedAfter`, so the exact-cutoff row is not returned by both paths.
- MySQL `EXPLAIN` for all three query shapes selects `idx_cancel_outbox_redrive_convergence`.

Add a domain test that every failure code is a stable uppercase enum and maps only through `name()`; do not add free-form constructors.

- [ ] **Step 2: Run tests and verify RED**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveTest' --tests '*CancelOutboxRedriveRepositoryIT' --tests '*CancelOutboxRedriveMigrationIT'
```

Expected: compilation fails because the enum and repository methods do not exist; after adding only test fixtures, the strict cutoff assertion fails against the current `>=` query.

- [ ] **Step 3: Implement the minimal repository contract**

Use conditional updates equivalent to:

```sql
UPDATE cancel_outbox_redrive
   SET status = 'FAILED', failure_stage = 'PUBLISH', last_error = :lastError,
       before_state = :beforeState, completed_at = :completedAt
 WHERE id = :redriveId AND status = 'REDRIVING' AND result IS NULL;

UPDATE cancel_outbox_redrive
   SET status = 'FAILED', failure_stage = 'CONVERGENCE', last_error = :lastError,
       after_state = :afterState, completed_at = :completedAt
 WHERE id = :redriveId AND status = 'REDRIVING' AND result IS NOT NULL;
```

Expired SELECTs return the full aggregate ordered by `started_at, id` with `started_at <= :cutoff`. Change normal convergence to `started_at > :startedAfter`. Reuse `requirePositiveLimit`.

- [ ] **Step 4: Run GREEN verification**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveTest' --tests '*CancelOutboxRedriveRepositoryIT' --tests '*CancelOutboxRedriveMigrationIT'
git diff --check
```

- [ ] **Step 5: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveFailureCode.java payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java payment-service/src/test/java/com/example/payment/domain/entity/CancelOutboxRedriveTest.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveMigrationIT.java
git commit -m "feat(payment): persist redrive failure phases"
```

### Task 2: Immediate preflight and Kafka publish failure handling

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/exception/CancelEventReplayException.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapter.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapterTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveWorkerTest.java`

**Interfaces:**

- Consumes: Task 1 `failPublish(...)` and `CancelOutboxRedriveFailureCode`.
- Produces:

```java
public final class CancelEventReplayException extends RuntimeException {
    public enum Kind { TIMEOUT, SEND_FAILED }
    public CancelEventReplayException(long cancelRequestId, Kind kind, Throwable cause);
    public Kind kind();
}
```

- [ ] **Step 1: Write failing replay-adapter tests**

Prove timeout maps to `Kind.TIMEOUT`; exceptional completion, cancellation, and interruption map to `Kind.SEND_FAILED`; interruption restores the thread flag. Assert exception messages contain only the numeric cancelRequestId and fixed text, never payload or dependency messages.

- [ ] **Step 2: Write failing worker tests**

Using a fixed Clock, prove:

- preflight UNKNOWN writes `failPublish(id, "PREFLIGHT_UNKNOWN", snapshot, now)`, calls replay zero times, and makes no convergence/published write.
- replay timeout writes `KAFKA_TIMEOUT`; other replay failures write `KAFKA_SEND_FAILED`; both preserve the preflight snapshot.
- a successful broker ACK followed by `recordPublished=false` throws the existing safe state exception, does not call `failPublish`, and calls replay only once.
- an unexpected exception before broker ACK is propagated and leaves the row REDRIVING for stale recovery; it is not guessed into a terminal state.
- every conditional failure write returning false reloads the row: a terminal row is benign CAS contention, while a still-active row raises the existing safe state exception.

- [ ] **Step 3: Run tests and verify RED**

```bash
./gradlew :payment-service:test --tests '*KafkaCancelEventReplayAdapterTest' --tests '*CancelOutboxRedriveWorkerTest'
```

Expected: missing public exception/Kind and UNKNOWN currently performs no terminal write.

- [ ] **Step 4: Implement minimal classification and worker branches**

Move the package-local replay exception out of the adapter. The adapter catches `TimeoutException` separately; all non-timeout failures use `SEND_FAILED`. The worker maps the two kinds to the exact failure-code names and never stores `Throwable.getMessage()`.

- [ ] **Step 5: Run GREEN verification**

```bash
./gradlew :payment-service:test --tests '*KafkaCancelEventReplayAdapterTest' --tests '*CancelOutboxRedriveWorkerTest'
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/exception/CancelEventReplayException.java payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapter.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java payment-service/src/test/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapterTest.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveWorkerTest.java
git commit -m "feat(payment): fail closed on redrive publish errors"
```

### Task 3: Deadline convergence and stale crash recovery workers

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorker.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorker.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorkerTest.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorkerTest.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveAuditJson.java`
- Modify: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveAuditJsonTest.java`

**Interfaces:**

- Consumes: Task 1 failure transitions/queries, existing inspection use case, audit JSON, and Clock.
- Produces:

```java
public void CancelOutboxRedriveDeadlineWorker.check(CancelOutboxRedrive redrive);
public void CancelOutboxRedriveStalePublishWorker.expire(CancelOutboxRedrive redrive);
public String CancelOutboxRedriveAuditJson.unknownInspection();
```

- [ ] **Step 1: Write failing deadline tests**

For an ACK-bearing aggregate, prove literal outcomes:

- ALREADY_APPLIED calls `resolve(id, finalSnapshot, now)` and never fails.
- REDRIVE_REQUIRED calls `failConvergence(id, "CONVERGENCE_TIMEOUT", snapshot, now)`.
- UNKNOWN calls `failConvergence(id, "DOWNSTREAM_UNKNOWN", snapshot, now)`.
- NOT_ELIGIBLE with `INCONSISTENT_DOWNSTREAM_STATE` stores that exact code.
- unexpected inspection exception stores `unknownInspection()` and `DOWNSTREAM_UNKNOWN` without exception text.
- false terminal CAS is benign and does not retry inspection.

- [ ] **Step 2: Write failing stale-publish tests**

Prove `expire(redrive)` calls only `failPublish(id, "PUBLISH_STATE_UNKNOWN", beforeState, now)`; it never invokes inspection, source lookup, or replay. A false CAS is benign.

- [ ] **Step 3: Run tests and verify RED**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveDeadlineWorkerTest' --tests '*CancelOutboxRedriveStalePublishWorkerTest' --tests '*CancelOutboxRedriveAuditJsonTest'
```

Expected: missing worker classes and unknown snapshot method.

- [ ] **Step 4: Implement workers and safe UNKNOWN audit JSON**

`unknownInspection()` must return this exact object contract with explicit nulls and empty evidence:

```json
{"decision":"UNKNOWN","reasonCode":"DOWNSTREAM_UNKNOWN","order":{"status":"UNKNOWN","evidence":[]},"stock":{"status":"UNKNOWN","evidence":[]}}
```

Use existing explicit Jackson records/field annotations; do not serialize exceptions or source data.

- [ ] **Step 5: Run GREEN verification**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveDeadlineWorkerTest' --tests '*CancelOutboxRedriveStalePublishWorkerTest' --tests '*CancelOutboxRedriveAuditJsonTest'
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorker.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorker.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveAuditJson.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorkerTest.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorkerTest.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveAuditJsonTest.java
git commit -m "feat(payment): finalize redrive deadlines"
```

### Task 4: Queue-free executor and asynchronous polling

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/infrastructure/config/CancelOutboxRedriveExecutorConfig.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutor.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveRecoveryPoller.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcher.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePoller.java`
- Modify: `payment-service/src/main/resources/application.yml`
- Modify: `payment-service/src/test/resources/application.yml`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/config/CancelOutboxRedriveExecutorConfigTest.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutorTest.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveRecoveryPollerTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcherTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePollerTest.java`

**Interfaces:**

- Consumes: Tasks 1 and 3 query/worker interfaces.
- Produces:

```java
public boolean CancelOutboxRedriveTaskExecutor.tryExecute(Runnable task);
public int CancelOutboxRedriveTaskExecutor.activeCount();
```

- [ ] **Step 1: Write failing real-executor tests**

Build the real configured executor with `maxConcurrency=5`, `shutdownAwaitSeconds=10`. Use five latch-blocked tasks and prove:

- all five start;
- the sixth `tryExecute` returns false and never runs;
- active count is 5 while blocked;
- after releasing one/all slots, a later submission succeeds;
- maxConcurrency 0/-1 and shutdown wait 0/-1 fail before executor startup;
- executor has queue capacity zero and `cancel-redrive-` thread names through observable execution, not source reflection.

- [ ] **Step 2: Write failing scheduler tests**

Prove dispatcher/recent convergence/recovery pollers only submit Runnables and return without running blocking worker code on the calling thread. Execute captured Runnables separately and assert the correct worker ID/aggregate is invoked.

Recovery poll order and cutoff:

```java
Instant cutoff = clock.instant().minusSeconds(60);
repository.findExpiredUnpublished(cutoff, batchSize);
repository.findExpiredPublished(cutoff, batchSize);
```

Rejected submissions cause no direct worker invocation and no repository state write. One worker exception remains isolated inside its submitted Runnable.

- [ ] **Step 3: Run tests and verify RED**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveExecutorConfigTest' --tests '*CancelOutboxRedriveTaskExecutorTest' --tests '*CancelOutboxRedriveDispatcherTest' --tests '*CancelOutboxRedriveConvergencePollerTest' --tests '*CancelOutboxRedriveRecoveryPollerTest'
```

Expected: missing executor/recovery types; current schedulers invoke workers synchronously.

- [ ] **Step 4: Implement executor and scheduler submission**

Use a named `ThreadPoolTaskExecutor` bean with core=max, `queueCapacity=0`, `AbortPolicy`, `waitForTasksToCompleteOnShutdown=true`, and configured await seconds. `tryExecute` catches only Spring task rejection and returns false.

Properties:

```yaml
cancel.redrive.max-concurrency: ${CANCEL_REDRIVE_MAX_CONCURRENCY:5}
cancel.redrive.shutdown-await-seconds: ${CANCEL_REDRIVE_SHUTDOWN_AWAIT_SECONDS:10}
cancel.redrive.recovery-ms: ${CANCEL_REDRIVE_RECOVERY_MS:2000}
cancel.redrive.recovery-initial-delay-ms: ${CANCEL_REDRIVE_RECOVERY_INITIAL_DELAY_MS:2000}
```

Set recovery initial delay to `86400000` in test resources, matching the existing dispatcher/convergence test isolation pattern.

- [ ] **Step 5: Run GREEN verification**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveExecutorConfigTest' --tests '*CancelOutboxRedriveTaskExecutorTest' --tests '*CancelOutboxRedriveDispatcherTest' --tests '*CancelOutboxRedriveConvergencePollerTest' --tests '*CancelOutboxRedriveRecoveryPollerTest'
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/config/CancelOutboxRedriveExecutorConfig.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutor.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveRecoveryPoller.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcher.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePoller.java payment-service/src/main/resources/application.yml payment-service/src/test/resources/application.yml payment-service/src/test/java/com/example/payment/infrastructure/config/CancelOutboxRedriveExecutorConfigTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutorTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveRecoveryPollerTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcherTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePollerTest.java
git commit -m "feat(payment): bound redrive worker concurrency"
```

### Task 5: Inspection timeouts, structured lifecycle logs, and bounded metrics

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveTelemetry.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveTelemetryTest.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/HttpClientConfig.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClient.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClient.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/config/CancelOutboxInspectionHttpClientConfigTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClientTest.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClientTest.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveService.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorker.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorker.java`
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorker.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutor.java`
- Modify: `payment-service/src/main/resources/application.yml`

**Interfaces:**

- Consumes: all lifecycle CAS outcomes and Task 4 executor.
- Produces focused methods:

```java
void requested(CancelOutboxRedrive redrive);
void claimed(CancelOutboxRedrive redrive);
void publishAcked(CancelOutboxRedrive redrive, ReplayResult ack);
void terminal(CancelOutboxRedrive redrive, CancelOutboxRedriveStatus status,
              CancelOutboxRedriveFailureStage stage, CancelOutboxRedriveFailureCode code);
void executorRejected();
```

- [ ] **Step 1: Write failing timeout configuration tests**

Create the qualified `cancelOutboxInspectionRestTemplate` through real `RestTemplateBuilder` configuration and prove 1000ms connect/read defaults affect a deliberately non-responsive local test server within a bounded test timeout. Prove 0/-1 configuration fails fast. Verify order/stock status clients receive the qualified template while unrelated clients retain the primary default template.

- [ ] **Step 2: Write failing telemetry tests**

Use `SimpleMeterRegistry` and a Logback list appender. For requested, claimed, ACK, resolved, rejected, PUBLISH failure, and CONVERGENCE failure assert key-value fields `event`, `redriveId`, `sourceOutboxId`, `status`, plus stage/code where applicable.

Assert rendered messages and key-value pairs do not contain literal fixtures:

```text
operator secret reason
{"paymentKey":"secret-pay","payload":"secret-payload"}
secret-pay
dependency leaked exception message
```

Metric assertions:

- terminal counter tags are exactly `status` and `failure_stage`;
- tag values are enum names or `none`;
- executor rejected counter has no tags;
- active gauge reads the real executor active count;
- duplicate CAS loser paths do not increment terminal counters.

- [ ] **Step 3: Run tests and verify RED**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxInspectionHttpClientConfigTest' --tests '*OrderCancelStatusHttpClientTest' --tests '*StockRestoreStatusHttpClientTest' --tests '*CancelOutboxRedriveTelemetryTest'
```

Expected: no qualified timeout client or telemetry component exists.

- [ ] **Step 4: Implement focused timeout and telemetry boundaries**

Properties:

```yaml
cancel.redrive.inspection.connect-timeout-ms: ${CANCEL_REDRIVE_INSPECTION_CONNECT_TIMEOUT_MS:1000}
cancel.redrive.inspection.read-timeout-ms: ${CANCEL_REDRIVE_INSPECTION_READ_TIMEOUT_MS:1000}
```

Use SLF4J 2 `atInfo()/atWarn().addKeyValue(...)` APIs. Increment terminal metrics only after a successful DB terminal CAS. Register:

```text
payment.cancel.redrive.terminal.total{status,failure_stage}
payment.cancel.redrive.executor.active
payment.cancel.redrive.executor.rejected.total
```

- [ ] **Step 5: Run focused and wiring verification**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxInspectionHttpClientConfigTest' --tests '*OrderCancelStatusHttpClientTest' --tests '*StockRestoreStatusHttpClientTest' --tests '*CancelOutboxRedriveTelemetryTest' --tests '*CancelOutboxRedriveWorkerTest' --tests '*CancelOutboxRedriveConvergenceWorkerTest' --tests '*CancelOutboxRedriveDeadlineWorkerTest' --tests '*CancelOutboxRedriveStalePublishWorkerTest' --tests '*InternalCancelOutboxControllerTest'
git diff --check
```

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveTelemetry.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveTelemetryTest.java payment-service/src/main/java/com/example/payment/infrastructure/config/HttpClientConfig.java payment-service/src/main/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClient.java payment-service/src/main/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClient.java payment-service/src/test/java/com/example/payment/infrastructure/config/CancelOutboxInspectionHttpClientConfigTest.java payment-service/src/test/java/com/example/payment/infrastructure/http/OrderCancelStatusHttpClientTest.java payment-service/src/test/java/com/example/payment/infrastructure/http/StockRestoreStatusHttpClientTest.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveService.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorker.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveDeadlineWorker.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveStalePublishWorker.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveTaskExecutor.java payment-service/src/main/resources/application.yml
git commit -m "feat(payment): observe redrive reliability"
```

### Task 6: Fault-injection integration, retry history, and runbook

**Files:**

- Modify: `payment-service/src/test/java/com/example/payment/integration/CancelOutboxRedriveWorkerIT.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java`
- Modify: `order-service/src/test/java/com/example/order/integration/CancelRestoreDeadEscalationIntegrationTest.java` only if the existing exact test cannot express the duplicate crash-window fixture without production changes
- Modify: `product-service/src/test/java/com/example/product/integration/CancelRestoreIdempotencyIntegrationTest.java` only if the existing exact test cannot express the duplicate crash-window fixture without production changes
- Modify: `docs/operations/cancel-outbox-redrive.md`

**Interfaces:**

- Consumes: completed Tasks 1-5.
- Produces: end-to-end acceptance evidence and operator procedures; no new production API.

- [ ] **Step 1: Add failing shared-context payment integration scenarios**

Within the existing class-scoped MySQL/Kafka/ApplicationContext:

- Kafka future timeout/exception fault injection produces FAILED/PUBLISH, no ACK result, no convergence selection.
- ACK succeeds but a repository decorator returns false for the first `recordPublished`; assert one Kafka record exists and the DB remains REDRIVING/result-null.
- move `started_at` to exactly `clock.now-60s`, run recovery, assert FAILED/PUBLISH/PUBLISH_STATE_UNKNOWN.
- POST a new reason, assert a new ID and both attempt rows remain queryable.
- new task re-inspects, emits a second record with exactly the same key/payload, records ACK, and reaches RESOLVED after APPLIED inspection.
- source DEAD row remains byte/field equivalent across both attempts.
- 59.999-second ACK row does not fail; exact 60-second NOT_APPLIED/UNKNOWN/INCONSISTENT rows fail CONVERGENCE with literal final snapshots/codes.

Use Kafka end offsets rather than short empty polls for deterministic record-count assertions.

- [ ] **Step 2: Add failing concurrency scenarios**

- Run two worker instances against one REQUESTED row with a latch around replay; exactly one reaches replay and the other CAS-loses.
- Submit six different REQUESTED IDs through the real max-5 executor while the first five workers are latch-blocked; assert five REDRIVING, one REQUESTED, active gauge 5, rejected counter 1. Release, poll again, and assert the sixth starts.

Clean only each test's redrive rows before source rows in `finally`; never broadly delete shared fixtures from NOT_SUPPORTED tests.

- [ ] **Step 3: Run payment integration RED/GREEN cycle**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveWorkerIT' --tests '*CancelOutboxRedriveRepositoryConcurrencyIT'
```

Expected RED before the Task 6 fixtures/wiring are complete; final run must pass with one container/context per integration class.

- [ ] **Step 4: Run downstream idempotency regressions fresh**

```bash
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved' --rerun-tasks
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp' --rerun-tasks
```

If either regression fails, invoke `superpowers:systematic-debugging` before modifying consumer production code.

- [ ] **Step 5: Extend the operator runbook**

Document exact CLI interpretation and safe next actions:

- FAILED/PUBLISH + PREFLIGHT_UNKNOWN
- FAILED/PUBLISH + KAFKA_TIMEOUT/KAFKA_SEND_FAILED
- FAILED/PUBLISH + PUBLISH_STATE_UNKNOWN, including inspect-before-retry warning
- FAILED/CONVERGENCE + CONVERGENCE_TIMEOUT/DOWNSTREAM_UNKNOWN/INCONSISTENT_DOWNSTREAM_STATE
- RESOLVED_ALREADY_APPLIED and REJECTED/INVALID_PAYLOAD
- executor saturation metrics and recovery polling
- ACK-before-state-save crash and duplicate-event idempotency dependency

All curl calls keep connect/max timeouts and the existing 60-second polling deadline. Commands must never print or request payload/payment key.

- [ ] **Step 6: Run Task 6 verification**

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveWorkerIT' --tests '*CancelOutboxRedriveRepositoryConcurrencyIT'
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved' --rerun-tasks
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp' --rerun-tasks
git diff --check
```

- [ ] **Step 7: Commit**

```bash
git add payment-service/src/test/java/com/example/payment/integration/CancelOutboxRedriveWorkerIT.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java docs/operations/cancel-outbox-redrive.md
# Stage either downstream test file only when Task 6 actually changes it.
git commit -m "test(payment): verify redrive failure recovery"
```

## Final Verification

- [ ] **Step 1: Run the full payment suite fresh**

```bash
./gradlew :payment-service:test --rerun-tasks
```

- [ ] **Step 2: Run exact downstream idempotency tests fresh**

```bash
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved' --rerun-tasks
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp' --rerun-tasks
```

- [ ] **Step 3: Verify XML, diff, and branch scope**

```bash
rg -n 'failures="[1-9]|errors="[1-9]' payment-service/build/test-results/test order-service/build/test-results/test product-service/build/test-results/test
git diff --check main...HEAD
git status -sb
git log --oneline main..HEAD
```

Expected: all Gradle commands exit 0, XML search returns no matches, worktree is clean, and the branch contains only the design, plan, implementation, tests, and runbook changes for #108.

- [ ] **Step 4: Request broad final review and publish**

Use `superpowers:requesting-code-review` against the full merge-base diff. Resolve its one allowed final fix wave, rerun affected and full verification, then use `superpowers:finishing-a-development-branch`. The previously selected GitHub workflow is to push and open a draft PR containing `Closes #108`.
