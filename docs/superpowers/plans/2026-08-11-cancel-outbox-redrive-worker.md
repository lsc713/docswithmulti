# Cancel Outbox Redrive Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute this plan task-by-task, with a fresh implementation subagent and two-stage review for each task.

**Goal:** Safely claim requested cancel-outbox redrives, terminate unsafe/already-applied requests without publishing, replay an eligible raw event once with broker ACK evidence, and resolve only after order and stock both converge.

**Architecture:** Persist progress in `cancel_outbox_redrive` and split work into a REQUESTED dispatcher and a REDRIVING convergence poller. Every lifecycle write is a conditional SQL update, so only a CAS winner performs preflight and replay, while convergence polling can be safely repeated. This issue deliberately leaves publish errors, UNKNOWN inspections, post-publish inconsistent states, and 60-second timeouts in REDRIVING for issue #108.

**Tech Stack:** Java 21, Spring Boot 4, Spring JDBC, Spring Kafka, Jackson, MySQL 8 Testcontainers, Kafka Testcontainers, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Work only in `/Users/juho/Documents/docswithmulti/.worktrees/cancel-outbox-redrive-worker` on `feature/cancel-outbox-redrive-worker`.
- Follow red-green-refactor: add the named failing test first, run it and observe the expected failure, then add the smallest production change and rerun.
- Preserve the source `cancel_event_outbox` row and its raw payload exactly; never deserialize/re-serialize before replay.
- Only the successful `REQUESTED -> REDRIVING` CAS winner may call inspection or Kafka replay.
- Do not add `FAILED` transitions, retry/recovery behavior, concurrency pools, or timeout terminal handling; those belong to issue #108.
- Do not log or expose the raw payload or payment key.
- Keep OUTBOX-mode conditions consistent with `CancelOutboxInspectionService`.
- Before every task commit run `git diff --check` and the task-specific tests.

## Task 1: Add atomic redrive lifecycle persistence

**Files:**

- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java`
- Modify: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java`

### Step 1: Write failing repository lifecycle tests

Add MySQL integration tests proving:

- `findRequestedIds(2)` returns the oldest two REQUESTED IDs only.
- two concurrent `tryStart(id, startedAt)` calls produce exactly one `true`, leave status REDRIVING, and persist `started_at`.
- `recordPublished(id, beforeState, result)` succeeds once only when status is REDRIVING and `result IS NULL`.
- `findConverging(startedAfter, limit)` returns only REDRIVING rows with non-null result and `started_at >= startedAfter`, ordered oldest first.
- `resolveAlreadyApplied`, `reject`, and `resolve` each update only a REDRIVING row and return `false` after the first terminal write.
- a fully populated row maps `result`, `last_error`, `before_state`, `after_state`, and all timestamps without loss.

Use compact valid JSON fixtures, for example:

```java
String before = "{\"decision\":\"REDRIVE_REQUIRED\"}";
String ack = "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}";
```

### Step 2: Run the focused tests and confirm RED

Run:

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveRepositoryIT' --tests '*CancelOutboxRedriveRepositoryConcurrencyIT'
```

Expected: compilation fails because the lifecycle repository methods do not exist.

### Step 3: Extend the repository contract

Add these methods:

```java
List<Long> findRequestedIds(int limit);
boolean tryStart(long redriveId, Instant startedAt);
boolean recordPublished(long redriveId, String beforeState, String result);
List<CancelOutboxRedrive> findConverging(Instant startedAfter, int limit);
boolean resolveAlreadyApplied(long redriveId, String beforeState, String afterState,
                               String result, Instant completedAt);
boolean reject(long redriveId, String beforeState, String afterState,
               String lastError, Instant completedAt);
boolean resolve(long redriveId, String afterState, Instant completedAt);
```

Import `java.util.List` in the interface. Keep `findById` as the source of the complete aggregate after writes.

### Step 4: Implement conditional SQL operations

Use these predicates:

- `tryStart`: `WHERE id = :redriveId AND status = 'REQUESTED'`
- `recordPublished`: `WHERE id = :redriveId AND status = 'REDRIVING' AND result IS NULL`
- terminal operations: `WHERE id = :redriveId AND status = 'REDRIVING'`
- `findConverging`: `status = 'REDRIVING' AND result IS NOT NULL AND started_at >= :startedAfter`

Each update returns `jdbc.update(...) == 1`. Terminal operations must set the specified status, snapshot/result/error fields, `completed_at`, and clear `failure_stage`. `resolve` preserves the ACK already stored in `result` and the preflight snapshot already stored in `before_state`.

Use the existing `ROW_MAPPER` and a shared full-column SELECT fragment only if it improves readability without changing query semantics.

### Step 5: Run GREEN verification

Run:

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveRepositoryIT' --tests '*CancelOutboxRedriveRepositoryConcurrencyIT'
git diff --check
```

### Step 6: Commit

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java
git commit -m "feat(payment): add redrive lifecycle CAS persistence"
```

## Task 2: Add stable audit JSON and broker-acknowledged replay port

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventReplayPort.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveAuditJson.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapter.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveAuditJsonTest.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapterTest.java`
- Modify: `payment-service/src/main/resources/application.yml`

### Step 1: Write failing codec and adapter tests

The audit JSON test must prove:

- inspection JSON contains fields in this shape: `decision`, `reasonCode`, `order`, `stock`.
- enum values are strings and null values are retained.
- ACK JSON contains only `topic`, `partition`, and `offset`.
- neither JSON contains source payload or payment key.

The adapter test must mock `KafkaTemplate<String, String>` and prove:

- `send(topic, String.valueOf(cancelRequestId), payload)` receives the exact input payload instance/content once.
- completed `SendResult` metadata becomes `ReplayResult(topic, partition, offset)`.
- timeout, interruption, and exceptional completion produce a dedicated runtime replay exception; interrupted status is restored for `InterruptedException`.

### Step 2: Run tests and confirm RED

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveAuditJsonTest' --tests '*KafkaCancelEventReplayAdapterTest'
```

Expected: compilation fails because the port, codec, and adapter do not exist.

### Step 3: Add the application port and stable JSON codec

Create:

```java
public interface CancelEventReplayPort {
    ReplayResult replay(long cancelRequestId, String payload);

    record ReplayResult(String topic, int partition, long offset) {}
}
```

`CancelOutboxRedriveAuditJson` receives Jackson `ObjectMapper` and exposes:

```java
String inspection(CancelOutboxInspectionUseCase.Result result);
String replay(CancelEventReplayPort.ReplayResult result);
String alreadyAppliedOutcome();
```

Build explicit DTO/record shapes rather than serializing the whole source/aggregate. `alreadyAppliedOutcome()` returns `{"outcome":"ALREADY_APPLIED"}`.

### Step 4: Implement the Kafka adapter

Annotate the adapter as an OUTBOX-mode component. Constructor dependencies/configuration:

```java
KafkaCancelEventReplayAdapter(
    KafkaTemplate<String, String> kafkaTemplate,
    @Value("${kafka.topic.payment-cancelled}") String topic,
    @Value("${cancel.redrive.publish-timeout-ms:5000}") long publishTimeoutMs)
```

Wait for the send future with `publishTimeoutMs` milliseconds and return the broker metadata. Add a package-local or nested `CancelEventReplayException` carrying a safe message with only `cancelRequestId`.

Add configuration:

```yaml
cancel:
  redrive:
    publish-timeout-ms: ${CANCEL_REDRIVE_PUBLISH_TIMEOUT_MS:5000}
```

Preserve the existing `cancel.publish` and `cancel.outbox` structure.

### Step 5: Run GREEN verification

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveAuditJsonTest' --tests '*KafkaCancelEventReplayAdapterTest'
git diff --check
```

### Step 6: Commit

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventReplayPort.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveAuditJson.java payment-service/src/main/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapter.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveAuditJsonTest.java payment-service/src/test/java/com/example/payment/infrastructure/messaging/KafkaCancelEventReplayAdapterTest.java payment-service/src/main/resources/application.yml
git commit -m "feat(payment): add broker acknowledged cancel replay"
```

## Task 3: Implement the CAS-owned preflight worker

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveWorkerTest.java`

### Step 1: Write failing worker tests

Use Mockito and a fixed `Clock`. Cover every decision:

- CAS loser: `tryStart` returns false; verify zero calls to `findById`, source lookup, inspection, replay, and terminal writes.
- ALREADY_APPLIED: no replay; store identical before/after inspection JSON, `{"outcome":"ALREADY_APPLIED"}`, and the fixed completion instant.
- NOT_ELIGIBLE with `INCONSISTENT_DOWNSTREAM_STATE`: no replay; store REJECTED with the reason-code name in `lastError` and identical before/after snapshots.
- REDRIVE_REQUIRED: fetch the redrive aggregate and source row, call replay exactly once with raw `cancelRequestId` and raw payload, then call `recordPublished` with preflight JSON and ACK JSON.
- UNKNOWN: no replay and no repository write after `tryStart`; status remains REDRIVING for #108.
- inspection/source/replay exception: propagate to the scheduler boundary without a terminal write or a second replay.

Also prove the worker uses the aggregate's `sourceOutboxId` for inspection/source lookup, not the redrive ID.

### Step 2: Run and confirm RED

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveWorkerTest'
```

Expected: compilation fails because the worker does not exist.

### Step 3: Implement the worker

Constructor dependencies:

```java
CancelOutboxRedriveWorker(
    CancelOutboxRedriveRepository repository,
    CancelOutboxInspectionUseCase inspection,
    CancelOutboxSourcePort sourcePort,
    CancelEventReplayPort replayPort,
    CancelOutboxRedriveAuditJson auditJson,
    Clock clock)
```

Expose `public void start(long redriveId)`. The first statement with observable dependencies must be `tryStart(redriveId, clock.instant())`; return immediately on false.

After CAS success:

1. Load the redrive aggregate or throw `CancelOutboxRedriveNotFoundException`.
2. Inspect `sourceOutboxId` and encode the inspection snapshot.
3. Switch exhaustively on `decision`.
4. Load the source row only inside REDRIVE_REQUIRED, replay once, encode ACK, then `recordPublished`.

If a conditional write unexpectedly returns false, throw `IllegalStateException` with the redrive ID only; do not retry or replay.

### Step 4: Run GREEN verification

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveWorkerTest'
git diff --check
```

### Step 5: Commit

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveWorker.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveWorkerTest.java
git commit -m "feat(payment): execute safe cancel redrive preflight"
```

## Task 4: Add dispatcher and convergence polling

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorker.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcher.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePoller.java`
- Create: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorkerTest.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcherTest.java`
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePollerTest.java`
- Modify: `payment-service/src/main/resources/application.yml`

### Step 1: Write failing convergence tests

`CancelOutboxRedriveConvergenceWorkerTest` must prove:

- ALREADY_APPLIED writes RESOLVED once with the final snapshot and completion time.
- REDRIVE_REQUIRED (including one APPLIED and one NOT_APPLIED leg) writes nothing.
- UNKNOWN and NOT_ELIGIBLE write nothing in #107.
- it never depends on or invokes `CancelEventReplayPort`.
- a false resolve CAS is treated as benign convergence contention, with no retry.

`CancelOutboxRedriveConvergencePollerTest` must prove it queries with `clock.instant().minusSeconds(60)` and configured batch size, and isolates one job's exception so later rows are still processed.

### Step 2: Write failing dispatcher tests

`CancelOutboxRedriveDispatcherTest` must prove:

- it calls `findRequestedIds(configuredBatchSize)` and dispatches returned IDs in order.
- one worker exception does not prevent later IDs from running.
- empty results perform no worker calls.

Do not test Spring's wall-clock scheduling; verify the `@Scheduled` configuration by reflection or a small context test only if needed.

### Step 3: Run and confirm RED

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveConvergenceWorkerTest' --tests '*CancelOutboxRedriveDispatcherTest' --tests '*CancelOutboxRedriveConvergencePollerTest'
```

### Step 4: Implement convergence and schedulers

`CancelOutboxRedriveConvergenceWorker.check(redrive)` calls inspection for `redrive.getSourceOutboxId()`. It resolves only on ALREADY_APPLIED:

```java
repository.resolve(redrive.getId(), auditJson.inspection(result), clock.instant());
```

The dispatcher is OUTBOX-mode only and exposes:

```java
@Scheduled(fixedDelayString = "${cancel.redrive.dispatch-ms:1000}")
public void dispatch()
```

The convergence poller is OUTBOX-mode only and exposes:

```java
@Scheduled(fixedDelayString = "${cancel.redrive.convergence-ms:2000}")
public void poll()
```

Both use sequential loops with per-ID safe logging and exception isolation. Logs may include redrive ID but never snapshots, reason text, payload, or payment key.

Add configuration without changing `cancel.outbox.*`:

```yaml
cancel:
  redrive:
    dispatch-ms: ${CANCEL_REDRIVE_DISPATCH_MS:1000}
    convergence-ms: ${CANCEL_REDRIVE_CONVERGENCE_MS:2000}
    observation-seconds: ${CANCEL_REDRIVE_OBSERVATION_SECONDS:60}
    batch-size: ${CANCEL_REDRIVE_BATCH_SIZE:100}
```

### Step 5: Run GREEN verification

```bash
./gradlew :payment-service:test --tests '*CancelOutboxRedriveConvergenceWorkerTest' --tests '*CancelOutboxRedriveDispatcherTest' --tests '*CancelOutboxRedriveConvergencePollerTest'
git diff --check
```

### Step 6: Commit

```bash
git add payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorker.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcher.java payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePoller.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveConvergenceWorkerTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveDispatcherTest.java payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelOutboxRedriveConvergencePollerTest.java payment-service/src/main/resources/application.yml
git commit -m "feat(payment): poll cancel redrive convergence"
```

## Task 5: Verify the end-to-end contract and document operator flow

**Files:**

- Modify: `payment-service/build.gradle`
- Modify: `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveResponse.java`
- Modify: `payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java` only if mapper injection is required
- Modify or create: `payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java`
- Create: `payment-service/src/test/java/com/example/payment/integration/CancelOutboxRedriveWorkerIT.java`
- Create: `docs/operations/cancel-outbox-redrive.md`

### Step 1: Write the failing HTTP JSON contract test

For `GET /internal/cancel-outbox/redrives/{redriveId}`, seed or mock a redrive containing JSON strings and assert:

```java
jsonPath("$.result.topic").value("payment.cancelled");
jsonPath("$.result.partition").value(0);
jsonPath("$.beforeState.order.status").value("NOT_APPLIED");
jsonPath("$.afterState.order.status").value("APPLIED");
```

Also assert null `result`, `beforeState`, and `afterState` fields are present as JSON null on REQUESTED. This test must fail first because the current DTO returns double-encoded strings.

### Step 2: Correct the response mapping

Change `result`, `beforeState`, and `afterState` to `JsonNode`. Parse non-null DB JSON with a dedicated mapper/helper and fail loudly on corrupt stored JSON; do not silently return a text node. Keep all existing field names and explicit null inclusion.

Prefer injecting an `ObjectMapper`-backed response mapper if static `from` can no longer be cleanly tested. Do not change POST's HTTP 202 behavior.

### Step 3: Add shared MySQL + Kafka integration coverage

Add:

```groovy
testImplementation 'org.testcontainers:kafka:1.19.7'
```

`CancelOutboxRedriveWorkerIT` should use one class-scoped MySQL container/ApplicationContext and one class-scoped Kafka container/ApplicationContext for all scenarios. Do not restart containers or rebuild the context per test method.

Configure the payment service with OUTBOX mode, the Kafka bootstrap server, and scheduler delays large enough that tests call dispatcher/convergence methods deterministically.

Prove:

1. Seed a DEAD source row and matching payment/cancel data.
2. Make inspection return REDRIVE_REQUIRED through test doubles or deterministic stub servers.
3. Create REQUESTED, dispatch it, consume exactly one record, and assert raw key and raw payload equality.
4. Assert the DB stores ACK `topic`, `partition`, `offset`, preflight snapshot, REDRIVING status, and leaves the source DEAD row unchanged.
5. Make both downstream inspections APPLIED, invoke convergence, and assert RESOLVED with final snapshot.
6. Invoke convergence again and assert no second Kafka record and no terminal mutation.
7. Add no-publish scenarios for ALREADY_APPLIED and INCONSISTENT/invalid payload, asserting their terminal states and an empty Kafka consumer poll.

Keep lower-level repository and worker unit tests; this integration test proves wiring and transport, not every branch again.

### Step 4: Run consumer idempotency regressions

Run the existing exact tests:

```bash
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved'
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp'
```

Do not change consumer code unless a real regression is exposed; diagnose first under `superpowers:systematic-debugging`.

### Step 5: Document the operator CLI

Create `docs/operations/cancel-outbox-redrive.md` with copy-paste commands using variables that are not reserved shell environment names:

```bash
PAYMENT_BASE_URL=http://localhost:8080
OUTBOX_ID=123
OPERATOR_ID=ops@example.com
```

Document:

- inspect source outbox with the required operator header;
- POST a reason and capture `redriveId`;
- poll GET every 2 seconds;
- interpret RESOLVED, RESOLVED_ALREADY_APPLIED, REJECTED, and REDRIVING;
- treat REDRIVING beyond 60 seconds as manual investigation until issue #108 lands;
- never paste payloads or payment keys into tickets/logs.

Use the controller's actual header and paths, verified from `InternalCancelOutboxController`.

### Step 6: Run task verification

```bash
./gradlew :payment-service:test --tests '*InternalCancelOutboxControllerTest' --tests '*CancelOutboxRedriveWorkerIT'
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved'
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp'
git diff --check
```

### Step 7: Commit

```bash
git add payment-service/build.gradle payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveResponse.java payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java payment-service/src/test/java/com/example/payment/integration/CancelOutboxRedriveWorkerIT.java docs/operations/cancel-outbox-redrive.md
git commit -m "test(payment): verify cancel redrive end to end"
```

## Final Verification and Handoff

### Step 1: Run the full relevant suite once

```bash
./gradlew :payment-service:test
./gradlew :order-service:test --tests 'com.example.order.integration.CancelRestoreDeadEscalationIntegrationTest.alreadyProcessedRedriveIsNoOpResolved'
./gradlew :product-service:test --tests 'com.example.product.integration.CancelRestoreIdempotencyIntegrationTest.duplicateEventIsIdempotentNoOp'
```

### Step 2: Inspect evidence

```bash
rg -n 'failures="[1-9]|errors="[1-9]' payment-service/build/test-results/test order-service/build/test-results/test product-service/build/test-results/test
git diff --check main...HEAD
git status -sb
git log --oneline main..HEAD
```

Expected: test commands exit 0, failure search returns no matches, diff check is clean, and only intentional commits/files appear.

### Step 3: Request final code review

Use `superpowers:requesting-code-review` against `main...HEAD`. Resolve every actionable finding, rerun affected tests, and repeat review if production code changes.

### Step 4: Finish the branch

Use `superpowers:verification-before-completion`, then `superpowers:finishing-a-development-branch`. With the user's previously selected GitHub workflow, use `github:yeet` to push and open a draft pull request that references `Closes #107` and explicitly states that #108 owns publish/convergence failure terminal handling.
