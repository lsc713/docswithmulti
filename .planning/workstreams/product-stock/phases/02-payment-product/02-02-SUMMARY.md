---
phase: 02-payment-product
plan: 02
subsystem: payments
tags: [spring-boot, flyway, redisson, jackson, compensation, retry-scheduler, fail-closed]

# Dependency graph
requires:
  - phase: 02-payment-product
    plan: 01
    provides: "ProductStockPort.release + CreatePaymentService 오케스트레이터 (reserve→persist)"
provides:
  - "예약 성공 후 persist 실패 시 release best-effort 보상 (RSV-03)"
  - "stock_release_retry(V17) + StockReleaseRetryService/Scheduler (compensation-retry 동형, 30s Redis 분산락)"
affects: [phase-3-cancel-stock-release]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "예약 고아 회수: persist(@Transactional) 실패 catch → release best-effort → 실패 시 재시도 테이블 적재 → 원예외 재던짐(fail-closed)"
    - "compensation-retry 미러: Repository 계약(enqueue/findDueForRetry/markDone/markRetryLater/exhaust) + JPA 어댑터 + Redisson 분산락 스케줄러"
    - "payment_item 롤백으로 소실되는 release 대상 items를 items_json에 보존"
    - "payment-service ObjectMapper 빈 부재 → 서비스 내부 static ObjectMapper(LongListConverter 관행)"

key-files:
  created:
    - payment-service/src/main/resources/db/migration/V17__create_stock_release_retry.sql
    - payment-service/src/main/java/com/example/payment/application/interfaces/StockReleaseRetryRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/StockReleaseRetryJpaEntity.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/StockReleaseRetryJpaRepository.java
    - payment-service/src/main/java/com/example/payment/infrastructure/persistence/StockReleaseRetryRepositoryImpl.java
    - payment-service/src/main/java/com/example/payment/application/service/StockReleaseRetryService.java
    - payment-service/src/main/java/com/example/payment/infrastructure/scheduler/StockReleaseRetryScheduler.java
    - payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java
    - payment-service/src/test/java/com/example/payment/application/service/StockReleaseRetryServiceTest.java
  modified:
    - payment-service/src/main/java/com/example/payment/application/service/CreatePaymentService.java
    - payment-service/src/main/resources/application.yml
    - payment-service/src/test/resources/application.yml
    - payment-service/src/test/java/com/example/payment/application/service/CreatePaymentServiceTest.java

key-decisions:
  - "PersistenceConfig 수동 배선 대신 @Repository 컴포넌트 스캔 — CompensationRetryRepositoryImpl 실제 관행과 동일(플랜의 PersistenceConfig 수정 불필요)"
  - "ObjectMapper는 DI 대신 서비스 내부 static 인스턴스 — payment-service에 ObjectMapper 빈이 없고 LongListConverter도 동일 패턴(신규 빈 배선 리스크 0)"
  - "enqueue 멱등: existsByPaymentKey 선검사 + UK 경합 시 DataIntegrityViolationException 흡수"

requirements-completed: [RSV-03]

coverage:
  - id: D-P2-6a
    description: "예약 성공 후 persist 실패 → release(paymentKey, items) best-effort 1회 호출, 원예외 전파(payment 미생성)"
    requirement: "RSV-03"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java#persistFails_releaseCompensates"
        status: pass
    human_judgment: false
  - id: D-P2-6b
    description: "persist 실패 + release도 실패 → stock_release_retry에 items 보존 적재 + 원예외 전파"
    requirement: "RSV-03"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/CreatePaymentCompensationTest.java#persistFails_releaseFails_enqueued"
        status: pass
    human_judgment: false
  - id: D-P2-6c
    description: "PENDING release 재시도: 성공→markDone, 실패&<5→백오프 markRetryLater, 5회 도달→exhaust"
    requirement: "RSV-03"
    verification:
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/StockReleaseRetryServiceTest.java#releaseSucceeds_markDone"
        status: pass
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/StockReleaseRetryServiceTest.java#releaseFails_belowMax_markRetryLater"
        status: pass
      - kind: unit
        ref: "payment-service/src/test/java/com/example/payment/application/service/StockReleaseRetryServiceTest.java#releaseFails_reachesMax_exhaust"
        status: pass
    human_judgment: false
  - id: D-P2-7
    description: "취소 코어(CancelPaymentService/CancelTxWriter/CancelDomainService/기존 scheduler/messaging/outbox) 무변경"
    verification:
      - kind: other
        ref: "git diff --name-only $(git merge-base HEAD origin/main)..HEAD | grep -E 'CancelPaymentService|CancelTxWriter|CancelDomainService|ProcessingRecoveryScheduler|PendingRecoveryScheduler|CompensationRetryScheduler|CancelEventOutboxPublisher|infrastructure/messaging|cancel_event_outbox' → empty"
        status: pass
      - kind: integration
        ref: "payment-service/src/test/java/com/example/payment/integration/CancelFlowIntegrationTest.java (full suite 291 green)"
        status: pass
    human_judgment: false

# Metrics
duration: 31 min
completed: 2026-07-31
status: complete
---

# Phase 02 Plan 02: 예약 성공 후 persist 실패 보상(release/재시도) Summary

**예약 성공 후 payment 생성 TX가 실패하면 ProductStockPort.release를 best-effort로 호출하고, release도 실패하면 items를 보존한 stock_release_retry(V17)에 적재해 compensation-retry 동형 스케줄러(30s, Redis 분산락)가 release를 재호출해 예약 누수를 정리한다(RSV-03). 취소 코어 완전 무변경.**

## Performance

- **Duration:** ~31 min
- **Completed:** 2026-07-31
- **Tasks:** 2 (Task 1 tdd, Task 2 auto)
- **Files created/modified:** 13 (신규 9 · 수정 4)

## Accomplishments
- CreatePaymentService 오케스트레이터에 persist 실패 catch 배선: release best-effort → 실패 시 enqueue → **원예외 재던짐**(보상이 결제 실패 응답을 바꾸지 않음, fail-closed 유지)
- V17 stock_release_retry(payment_key UK, items_json, attempt_count, next_retry_at, status) — payment_item 롤백으로 소실되는 release 대상 items를 보존
- StockReleaseRetryRepository 계약 + JPA 어댑터(@Repository 컴포넌트 스캔) — CompensationRetryRepository 미러
- StockReleaseRetryService + Scheduler(Redisson 분산락, @Scheduled 30s) — MAX_ATTEMPTS=5, 백오프 attempt*60s, 초과 시 exhaust(수동 처리 전이)
- 취소 코어 무변경(core-unchanged gate CLEAN) — 신규 스케줄러는 취소/outbox 코어 정규식 미매치(D-P2-7)

## Task Commits

1. **Task 1 RED:** `8a9dfac` (test) — 보상 실패 테스트 + StockReleaseRetryRepository 계약
2. **Task 1 GREEN:** `6f2596d` (feat) — persist 실패 release 보상 + V17 적재
3. **Task 2:** `c62b03a` (feat) — StockReleaseRetryService + 스케줄러 + ObjectMapper 정정

_플랜 메타데이터(.planning/)는 실행 제약(`.planning/ 커밋 금지`)에 따라 커밋하지 않음._

## Decisions Made
- **PersistenceConfig 수정 불필요:** 플랜은 수동 빈 배선을 지시했으나 레퍼런스 CompensationRetryRepositoryImpl은 실제로 `@Repository` 컴포넌트 스캔을 쓴다. 동일 관행으로 `@Repository`만 부착 — PersistenceConfig 무변경(불필요한 파일 수정 회피).
- **ObjectMapper 내부 static:** payment-service 컨텍스트에 ObjectMapper 빈이 없다(LongListConverter도 `new ObjectMapper()` 내부 생성). DI 대신 서비스 내부 static 인스턴스로 통일 — 신규 빈 도입 없이 관행 준수(아래 Deviation 1).
- **enqueue 멱등:** 같은 실패 요청 재적재를 payment_key UK로 차단(existsByPaymentKey 선검사 + 경합 시 DataIntegrityViolationException 흡수).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] ObjectMapper DI → 서비스 내부 static 인스턴스**
- **Found during:** Task 2 (전체 통합테스트 컨텍스트 로드)
- **Issue:** 플랜은 "ObjectMapper(기존 빈) 주입"을 권장했으나, payment-service에는 ObjectMapper 빈이 존재하지 않음(웹 MVC 슬라이스 아님, LongListConverter도 내부 `new ObjectMapper()` 사용). 생성자 주입 시 모든 @SpringBootTest 컨텍스트가 `NoSuchBeanDefinitionException: ObjectMapper`로 파손(통합테스트 15건).
- **Fix:** CreatePaymentService/StockReleaseRetryService의 ObjectMapper를 생성자 파라미터에서 제거하고 `private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();`(스레드 안전)로 대체 — 코드베이스 기존 관행과 동일.
- **Files modified:** CreatePaymentService.java, StockReleaseRetryService.java, 관련 단위테스트 3개(생성자 인자 조정)
- **Verification:** `./gradlew :payment-service:test` 291개 전부 그린
- **Committed in:** `c62b03a`

**2. [Rule 3 - Blocking] 테스트 application.yml에 scheduler.lock.stock-release-retry 키 추가**
- **Found during:** Task 2 (통합테스트 컨텍스트 로드)
- **Issue:** 신규 StockReleaseRetryScheduler의 `@Value("${scheduler.lock.stock-release-retry}")`가 test/resources/application.yml에 부재해 모든 @SpringBootTest 컨텍스트가 PlaceholderResolutionException으로 파손(02-01 deviation 2와 동형 — 신규 실 빈이 @Value 요구).
- **Fix:** test/resources/application.yml scheduler.lock 블록에 stock-release-retry 키 추가(main yml과 대칭).
- **Files modified:** payment-service/src/test/resources/application.yml
- **Verification:** 통합테스트 컨텍스트 로드 회복, 전체 그린
- **Committed in:** `c62b03a`

**3. [해당 없음 - 스코프 외] 사전존재 `* 2` 중복 파일**
- api-gateway/infra/docs 하위에 클라우드 싱크 산물로 보이는 untracked `* 2` 파일이 존재하나 payment-service/src 밖이며 이번 변경과 무관 → **미조치**(스코프 경계). 02-01에서 payment-service/src 내부 산물은 이미 정리됨(이번엔 src 내부 0건).

---

**Total deviations:** 2 auto-fixed (2 blocking) + 1 스코프 외 미조치. **Impact on plan:** 둘 다 신규 실 빈이 기존 테스트 컨텍스트에 미치는 배선 파손 해소로 correctness 필수. 프로덕션 로직/산출물/계약은 플랜과 동일(PersistenceConfig 수정만 레퍼런스 실제 관행에 맞춰 생략). 취소 코어 스코프 크립 없음.

## Issues Encountered
None blocking — 위 Deviation 1/2로 전부 해소.

## Known Stubs
None — 보상 catch·재시도 서비스 모두 단위테스트로 3케이스씩 증명. release 멱등성(product 측 UK)은 01 phase 계약에 의존(재시도 안전성 근거).

## User Setup Required
None — 신규 외부 서비스/시크릿 없음. Redisson/JPA/Jackson 기존 재사용, 신규 패키지 0.

## Next Phase Readiness
- Phase 3(취소 시 재고 해제): stock_release_retry 회수 경로가 갖춰짐. 취소 코어 무변경으로 취소 플로우에 sku 재고 해제 배선 시 간섭 없음.
- exhaust(FAILED) 건은 수동 처리 대상 — 운영 알림(OperationAlertPort) 연동은 본 플랜 범위 밖(로그 error로 표식).

## Self-Check: PASSED
- 신규 파일 9개 디스크 존재 확인.
- 커밋 8a9dfac / 6f2596d / c62b03a 존재 확인.
- `./gradlew :payment-service:test` 291개 전부 그린. core-unchanged gate CLEAN(취소/outbox 코어 미매치). V17 flyway 적용(통합테스트 부팅 성공).

---
*Phase: 02-payment-product*
*Completed: 2026-07-31*
