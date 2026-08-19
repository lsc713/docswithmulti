---
phase: 03-cancel-restore
plan: 02
subsystem: infra
tags: [kafka, idempotency, flyway, mysql, testcontainers, jpa, stock]

requires:
  - phase: 03-01
    provides: product PaymentCancelledStockConsumer + ProcessCancelledStockService(release 위임 happy path) + payload cancelledItems(skuId/quantity)
provides:
  - product processed_cancel_event 테이블(Flyway V2, cancel_request_id UK) + 리포지토리
  - ProcessCancelledStockService cancelRequestId 멱등 게이트(중복 payment.cancelled no-op)
  - 부분취소 검증(cancelledItems SKU만 복원, 나머지 RESERVED 유지)
affects: [03-03 retry/DLQ, product-stock 후속]

tech-stack:
  added: []
  patterns:
    - "processed_cancel_event UK 멱등 게이트를 TransactionTemplate 안에서 선체크→처리→save (order ProcessCancelledItemsService 동형)"
    - "멱등 이중 안전: 상위 processed_event 게이트 + 하위 release W2 조건부 전이"

key-files:
  created:
    - product-service/src/main/resources/db/migration/V2__create_processed_cancel_event.sql
    - product-service/src/main/java/com/example/product/application/interfaces/ProcessedCancelEventRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProcessedCancelEventJpaEntity.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProcessedCancelEventJpaRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/ProcessedCancelEventRepositoryImpl.java
    - product-service/src/test/java/com/example/product/integration/CancelRestoreIdempotencyIntegrationTest.java
  modified:
    - product-service/src/main/java/com/example/product/application/service/ProcessCancelledStockService.java
    - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java

key-decisions:
  - "processed_cancel_event 스키마를 order-service V1에서 그대로 복제(cancel_request_id VARCHAR(64) UK, processed_at DATETIME(3)) — product ddl-auto=validate라 엔티티/DDL 정확 일치"
  - "멱등 게이트를 TransactionTemplate로 감싸 게이트 선체크+release+save를 단일 TX 원자 처리(order 패턴 동형). @Service 유지 + 생성자 주입"
  - "통합테스트는 Kafka 없이 서비스 레벨 직접 검증(03-01 tracer가 Kafka 배선 이미 증명) — 브로커 불필요, 빠름"

patterns-established:
  - "at-least-once 컨슈머 멱등: processed_cancel_event(cancel_request_id UK) 선체크 게이트로 release 재호출 자체를 건너뜀"

requirements-completed: [RST-02]

coverage:
  - id: D1
    description: "중복 payment.cancelled 이벤트는 cancelRequestId 멱등으로 no-op — 두 번째 동일 이벤트가 available_qty를 추가 복원하지 않고 processed_cancel_event는 1행 유지"
    requirement: "RST-02"
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/CancelRestoreIdempotencyIntegrationTest.java#duplicateEventIsIdempotentNoOp"
        status: pass
    human_judgment: false
  - id: D2
    description: "부분취소 시 취소 이벤트 cancelledItems에 실린 SKU만 복원되고 나머지 예약은 RESERVED로 유지"
    requirement: "RST-02"
    verification:
      - kind: integration
        ref: "product-service/src/test/java/com/example/product/integration/CancelRestoreIdempotencyIntegrationTest.java#partialCancelRestoresOnlyCancelledSku"
        status: pass
    human_judgment: false
  - id: D3
    description: "product processed_cancel_event V2 마이그레이션이 실 MySQL에 적용되고 엔티티 매핑(ddl-auto=validate)과 정합"
    requirement: "RST-02"
    verification:
      - kind: integration
        ref: "SpringBootTest 컨텍스트 기동 — Flyway V2 적용 + Hibernate validate green (CancelRestoreIdempotencyIntegrationTest 부트업)"
        status: pass
    human_judgment: false

duration: 7min
completed: 2026-07-31
status: complete
---

# Phase 3 Plan 2: 취소 복원 멱등·부분취소 하드닝 Summary

**product processed_cancel_event(Flyway V2, cancel_request_id UK) 멱등 게이트로 at-least-once payment.cancelled 중복을 no-op 처리하고, 부분취소 시 cancelledItems SKU만 복원되도록 하드닝(RST-02)**

## Performance

- **Duration:** 7 min
- **Started:** 2026-07-31T02:57:02Z
- **Completed:** 2026-07-31T03:04:23Z
- **Tasks:** 2
- **Files modified:** 8 (6 created, 2 modified)

## Accomplishments
- product `processed_cancel_event` 테이블(Flyway V2)을 order 스키마 복제로 신설 — `cancel_request_id` UK가 멱등 계약
- `ProcessCancelledStockService`에 `TransactionTemplate` 기반 멱등 게이트 추가: 선체크(existsByCancelRequestId) → release → save를 단일 TX 원자 처리(order `ProcessCancelledItemsService` 동형)
- 중복 이벤트 no-op(추가 복원 없음, processed_cancel_event 1행) + 부분취소(cancelledItems SKU만 복원, 나머지 RESERVED) 통합테스트로 고정

## Task Commits

Each task was committed atomically:

1. **Task 1: processed_cancel_event V2 + 리포지토리** - `2ab39ab` (feat)
2. **Task 2: 멱등 게이트 + 부분취소 통합테스트** - `aba43ec` (feat)

_플랜 메타데이터(SUMMARY/STATE)는 실행 환경 지시에 따라 커밋하지 않음(`.planning/` 커밋 금지)._

## Files Created/Modified
- `product-service/.../db/migration/V2__create_processed_cancel_event.sql` - order 스키마 복제, cancel_request_id UK
- `product-service/.../application/interfaces/ProcessedCancelEventRepository.java` - existsByCancelRequestId/save
- `product-service/.../infrastructure/persistence/ProcessedCancelEventJpaEntity.java` - @Table processed_cancel_event, UK, of() 팩토리
- `product-service/.../infrastructure/persistence/ProcessedCancelEventJpaRepository.java` - existsByCancelRequestId 파생 쿼리
- `product-service/.../infrastructure/persistence/ProcessedCancelEventRepositoryImpl.java` - JPA 위임(product 명시 생성자 스타일)
- `product-service/.../application/service/ProcessCancelledStockService.java` - TransactionTemplate 멱등 게이트 추가
- `product-service/.../infrastructure/config/PersistenceConfig.java` - TransactionTemplate + processedCancelEventRepository 빈 등록
- `product-service/.../integration/CancelRestoreIdempotencyIntegrationTest.java` - 멱등/부분취소 통합테스트(Testcontainers MySQL)

## Decisions Made
- **RepositoryImpl 스타일**: order는 `@RequiredArgsConstructor`(lombok), product의 기존 impl들은 명시 생성자 — product 컨벤션에 맞춰 명시 생성자로 통일(자기완결).
- **테스트 레벨**: Kafka/Consumer 없이 서비스(`ProcessCancelledStockService`) 직접 호출로 멱등·부분취소 검증. 03-01 tracer가 이미 Kafka 전 슬라이스를 관통 검증했으므로 브로커 불필요 — 빠르고 결정적.
- **`useAffectedRows=true`**: MySQL 컨테이너 URL 파라미터로 지정 — `releaseIfReserved` 조건부 전이가 affected=1을 정확히 보고해야 복원이 트리거되므로(StockReleaseIntegrationTest와 동일).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] PersistenceConfig에 TransactionTemplate + processedCancelEventRepository 빈 추가**
- **Found during:** Task 1/Task 2 (배선)
- **Issue:** 플랜 files_modified에 PersistenceConfig가 없으나, TransactionTemplate은 Spring Boot 기본 자동설정 빈이 아니고 신규 리포지토리도 빈 등록이 필요(order는 동일 config에서 두 빈을 등록). 없으면 컨텍스트 기동 불가.
- **Fix:** order PersistenceConfig 동형으로 `transactionTemplate`·`processedCancelEventRepository` 빈 2개 추가.
- **Files modified:** product-service/.../infrastructure/config/PersistenceConfig.java
- **Verification:** SpringBootTest 컨텍스트 기동 성공 + 통합테스트 green.
- **Committed in:** 2ab39ab(repo 빈), aba43ec(TransactionTemplate 빈)

---

**Total deviations:** 1 auto-fixed (1 blocking wiring)
**Impact on plan:** 필수 배선 보강(order 패턴 동형). 스코프 확장 없음.

## Issues Encountered
- **`./gradlew :product-service:flywayInfo` 실행 불가(선행 툴링 이슈, 본 플랜 무관)**: Flyway Gradle 플러그인이 현재 Gradle 버전에서 제거된 `org.gradle.api.plugins.JavaPluginConvention`을 참조해 태스크가 실패. 내 SQL과 무관한 기존 빌드툴 비호환이라 스코프 밖(수정 안 함). V2 마이그레이션 자체는 통합테스트에서 실 MySQL에 Flyway로 적용되고 `ddl-auto=validate`가 엔티티/스키마 정합을 검증하며 통과 — flywayInfo 없이도 마이그레이션 정합 증명됨.

## User Setup Required
None - 외부 서비스 설정 불필요.

## Next Phase Readiness
- RST-02(멱등+부분취소) 완성. at-least-once 중복/부분취소가 재고를 오염시키지 않음.
- 03-03(retry/DLQ) 준비됨 — 컨슈머는 이미 UK 충돌 시 ack(멱등), processed_event 게이트가 상위 안전망.
- product-service 전체 테스트 무회귀 green.

## Self-Check: PASSED

- 생성 파일 6종 + 수정 파일 2종 디스크 존재 확인.
- 커밋 존재 확인: `2ab39ab`(feat 03-02 repo), `aba43ec`(feat 03-02 gate+test).
- `./gradlew :product-service:test` 전체 그린(무회귀), 신규 `CancelRestoreIdempotencyIntegrationTest` 2케이스 green.

---
*Phase: 03-cancel-restore (workstream: product-stock)*
*Completed: 2026-07-31*
