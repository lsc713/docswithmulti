# Phase 2: 정합성 & 복구 갭 마감 - Context

**Gathered:** 2026-07-28
**Status:** Ready for planning

<domain>
## Phase Boundary

단일 인스턴스에서는 드러나지 않고 멀티파드/스케일아웃에서만 드러난 **정합성·복구 결함**을 제거한다. 신규 기능이 아니라 이미 존재하는 취소 플로우의 결함 마감이다.

**In scope (RESIL-01/02/03):**
- RESIL-01: `PgCancelHttpClient.getStatus()`·`RiskManagementHttpClient.isCharged()` 스텁 제거 → PROCESSING 5분 초과 건이 수동 개입 없이 수렴.
- RESIL-02: 멀티파드 동시 취소 레이스 패자가 500 대신 멱등 응답(진행 중/완료, 200) 반환.
- RESIL-03: ProcessingRecovery 동시성 안전 — `pg_retry_count` 원자 UPDATE.

**Out of scope:** 성능/용량 개선(→ Phase 4), 배포/노드 HA(→ Phase 3), 신규 기능.
</domain>

<decisions>
## Implementation Decisions

### PG 상태조회 계약 (RESIL-01)
- **D-01:** `PgCancelHttpClient.getStatus()`는 운영 PG가 **취소 상태조회 엔드포인트를 제공한다는 전제**로 그 계약에 맞춰 구현한다(응답 → `PgCancelResult` APPROVED/FAILED/PENDING + retryable 매핑). 정확한 엔드포인트 경로/응답 스키마는 researcher가 확인·정의. — **Reversibility:** costly — 복구 로직(`ProcessingRecoveryService.recoverOne`)이 getStatus 조회 계약에 결합된다. PG가 조회 미지원(fire-and-forget)으로 밝혀지면 "멱등 재취소로 복구" 설계로 갈아엎어야 한다.
- **D-05:** `RiskManagementHttpClient.isCharged()`는 신규 구현이 아니라 **이미 존재하는** `GET /internal/cancel-limit/check`(`CheckChargeUseCase` → `{charged,...}`)에 배선한다. — **Reversibility:** reversible.

### 동시성 테스트 재현 범위
- **D-02:** 멀티파드 레이스(RESIL-02)·스케줄러 동시 실행(RESIL-03) 결함은 **Testcontainers(실 MySQL) + 같은 JVM 동시 스레드**(CountDownLatch/ExecutorService)로 재현한다. 실 UK 위반·원자 UPDATE를 실 DB로 검증. 실제 2 인스턴스 구동은 하지 않음(CI 부담/오케스트레이션 대비 이득 낮음). — **Reversibility:** reversible.

### Claude's Discretion (선택 영역 밖 — 코드/스펙이 이미 규정)
- **D-03 (멱등 응답 시맨틱, RESIL-02):** `api-spec.md`가 이미 규정 — 진행 중이면 **200 + `status: PENDING/PROCESSING`**. 레이스 패자는 `CancelPaymentService.executeCancel`의 `saveTx1` PENDING INSERT에서 UK 위반(DataIntegrityViolation)을 catch → `findByPaymentIdAndRequestHash` 재조회 → 기존 CancelRequest를 `CancelPaymentService:71`의 상태 스위치(COMPLETED/PENDING/PROCESSING→기존건 반환)로 흘려 동일 계약 준수. 새 응답 형태를 만들지 않는다. — **Reversibility:** costly — api-spec.md의 공개 응답 계약. 형태 변경은 클라이언트 계약 파기.
- **D-04 (동시성 가드 강도, RESIL-03):** 스케줄러 Redis 분산락이 이미 단일 실행을 보장하므로, (a) `pg_retry_count`는 객체 mutation+save가 아닌 **DB 원자 UPDATE**로 교정(필수 위생), (b) **레코드 단위 분산락은 추가하지 않는다**(락 만료/실패 대비는 현재 YAGNI). — **Reversibility:** reversible — 레코드 락은 필요 시 후속 추가 가능.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### 잠긴 계약·불변식
- `CLAUDE.md` — 핵심 불변식: `request_hash` UK로 따닥 차단, TX1/2/3 경계, 이력(`cancel_request_history`)은 항상 TX 밖, 스케줄러 3개 Redis 분산락, FAILED 재시도는 새 INSERT 금지(FAILED→PENDING UPDATE).
- `.planning/PROJECT.md` `<decisions>` — D-001(payment.cancelled TX3 인라인, 복구의 `runTx3`도 동일 경로), D-002(병목 payment_db).
- `docs/api-spec.md` §1 (라인 114 부근) — 취소 응답 계약: PENDING/PROCESSING → **200 + status 반환**(멱등 응답 형태의 원본). D-03의 근거.
- `docs/kafka-design.md` — payment.cancelled 인라인 발행 계약(복구 재실행 시 준수).

### 결함 근거
- `.planning/codebase/CONCERNS.md` — ProcessingRecovery 미구현 스텁 / 동시성 불완전.
- `docs/load-test/k3s-scaleout-results.md` 실험 ② — 멀티파드 동시 취소 패자 500(정직한 발견). RESIL-02 근거.
- `docs/superpowers/specs/2026-04-28-scheduler-enhancement-design.md` — ProcessingRecovery 복구 상태머신 설계(APPROVED→TX3 재실행 / FAILED→보상 / PENDING→타임아웃).

### PG 상태조회 계약 (D-01 — researcher가 확정)
- (미확보) 운영 PG 상태조회 API 스펙 — 근거 문서 미존재. researcher가 관례적 REST 계약 설계 또는 사용자 제공 문서 확인. 사용자 제공 시 여기 추가.
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `CancelPaymentService:53` `findByPaymentIdAndRequestHash(...)` — 레이스 패자 멱등 재조회에 그대로 재사용. `:71` 상태 스위치가 멱등 반환 매핑의 단일 지점.
- `CancelTxWriter.saveTx1/saveTx2/saveTx3` — TX 경계. `runTx3`은 D-001 인라인 `payment.cancelled` 발행 경로(복구도 동일 사용).
- `RiskManagementHttpClient.compensate(...)` — 보상 재사용. risk `GET /internal/cancel-limit/check` — isCharged 배선 대상(D-05).
- `MockPgCancelClient.getStatus`(`@Profile("local")`, 이미 구현·approved 반환) — 실 `PgCancelHttpClient.getStatus` 구현 시 응답 형태 참조점.
- `PgCancelResult`(record: status/retryable + approved/failed/retryableFailed/pending 팩토리) — getStatus 응답 매핑 타깃.

### Established Patterns
- Port/Adapter: `PgCancelPort`/`RiskManagementPort` 인터페이스 + `@Profile("!local")`(실 HTTP) / `@Profile("local")`(Mock) 분기.
- resilience4j `CircuitBreaker`가 모든 외부 HTTP 클라이언트 호출을 감쌈 — getStatus도 동일 패턴.
- `recordHistory`는 try/catch로 TX 밖 실행(이력 실패가 비즈니스에 영향 없음).

### Integration Points
- `PgCancelHttpClient.java:60` `getStatus` — 구현 대상(D-01). `RiskManagementHttpClient.java:82` `isCharged` — 배선 대상(D-05).
- `ProcessingRecoveryService.java:58` — 현재 getStatus 예외를 "PG 조회 실패 → PROCESSING 유지"로 삼켜 복구 루프가 무동작. 스텁 제거 후 이 catch가 진짜 조회 실패만 걸러야 함(스텁 예외 ≠ 조회 실패).
- `ProcessingRecoveryService.java:95-96` `retryPgCancel` — `pg_retry_count` 객체 증가+save → DB 원자 UPDATE로 교정(D-04).
- `CancelPaymentService.executeCancel` `saveTx1`(PENDING INSERT) — DuplicateKey catch 지점(D-03).
</code_context>

<specifics>
## Specific Ideas

- 멱등 응답은 새로 설계하지 말고 `api-spec.md`의 200+status 형태를 그대로 준수(D-03).
- `isCharged`는 신규 API가 아니라 기존 `/internal/cancel-limit/check` 배선(D-05) — researcher는 이 엔드포인트 계약을 먼저 읽을 것.
- 스텁 예외를 조용히 삼키는 catch(`ProcessingRecoveryService:58`)가 결함을 은폐하고 있었음 — 구현 후 이 방어의 의미를 재정의할 것.
</specifics>

<deferred>
## Deferred Ideas

- **레코드 단위 분산락(cancelRequestId 멱등 가드):** 스케줄러 Redis 락 만료/실패 대비 방어. 현재 YAGNI(D-04) — 필요 시 후속 페이즈.
- **실 PG 상태조회 계약 근거 문서:** 사용자 제공 시 canonical ref로 등록, getStatus 구현의 authoritative source.
- **Phase 1 의존:** 로드맵상 Phase 2는 Phase 1 실측 기준선에 의존(복구/레이스 수정의 회귀를 기준선으로 검증). planning 시 Phase 1 산출물(재현 절차·USE 대시보드) 존재 여부 확인.

### Reviewed Todos (not folded)
None — 이 페이즈는 STATE.md의 RESIL-01/02/03 todo와 정확히 일치.
</deferred>

---

*Phase: 2-정합성 & 복구 갭 마감*
*Context gathered: 2026-07-28*
