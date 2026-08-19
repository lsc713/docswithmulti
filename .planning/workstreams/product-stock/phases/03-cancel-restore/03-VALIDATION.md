---
phase: 3
slug: cancel-restore
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-31
---

# Phase 3 — Validation Strategy

> 취소 복원(원래 목표). payment payload + product Kafka consumer + orphan 복구. Per-task map은 planner가 채운다.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + Testcontainers (MySQL + Kafka) — order-service consumer 테스트 관행 |
| **Quick run command** | `./gradlew :product-service:test` / `:payment-service:test` |
| **Full suite** | `./gradlew :product-service:test :payment-service:test` |
| **Cancel-core-logic gate** | payment 취소 코어의 TX/멱등/스케줄러/outbox **로직** 불변 — CancelTxWriter 변경은 buildPayload 필드추가로 한정, 기존 취소 통합테스트(멱등·TX·복구) 그린 유지가 불변 증거 |

## Sampling Rate

- **After every task commit:** task의 `<automated>` 명령.
- **After wave:** 해당 서비스 test.
- **Before verify:** 양 서비스 full suite green + 기존 취소 통합테스트 무회귀.

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 01-T1 | 03-01 | 1 | RST-01 | 취소 코어 불변(무회귀) | integration | `:payment-service:test --tests CancelFlowIntegrationTest --tests CancelRaceIdempotencyIT --tests ProcessingRecoveryConcurrencyIT --tests CancelEventOutboxPublisherIT --tests CancelTxWriterPayloadTest` | CancelTxWriter.java | ⬜ pending |
| 01-T2 | 03-01 | 1 | RST-01 | payload 필드 무결성 | integration | `:payment-service:test --tests CancelTxWriterPayloadTest` | CancelTxWriterPayloadTest.java | ⬜ pending |
| 01-T3 | 03-01 | 1 | RST-02 | consumer 배선 e2e | integration (MySQL+Kafka) | `:product-service:test --tests CancelRestoreTracerIntegrationTest` | CancelRestoreTracerIntegrationTest.java | ⬜ pending |
| 02-T1 | 03-02 | 2 | RST-02 | 멱등 스키마(cancel_request_id UK) | migration/compile | `:product-service:flywayInfo && :product-service:compileJava` | V2__create_processed_cancel_event.sql | ⬜ pending |
| 02-T2 | 03-02 | 2 | RST-02 | 중복 no-op·부분취소 | integration (MySQL) | `:product-service:test --tests CancelRestoreIdempotencyIntegrationTest` | CancelRestoreIdempotencyIntegrationTest.java | ⬜ pending |
| 03-T1 | 03-03 | 2 | RST-02 | retry/DLQ 인프라 | compile | `:product-service:compileJava :product-service:compileTestJava` | RetryRouter.java | ⬜ pending |
| 03-T2 | 03-03 | 2 | RST-02 | 실패 라우팅·수동 ack | unit | `:product-service:test --tests RetryRouterTest` | RetryRouterTest.java | ⬜ pending |
| 04-T1 | 03-04 | 1 | RST-03 | read-only 조회(코어 불변) | integration (MySQL) | `:payment-service:test --tests PaymentExistsEndpointIntegrationTest` | PaymentController.java | ⬜ pending |
| 04-T2 | 03-04 | 1 | RST-03 | 취소 코어 무회귀 | integration | `:payment-service:test --tests PaymentExistsEndpointIntegrationTest --tests CancelFlowIntegrationTest` | PaymentExistsEndpointIntegrationTest.java | ⬜ pending |
| 05-T1 | 03-05 | 3 | RST-03 | fail-safe 조회 클라이언트 | compile | `:product-service:compileJava :product-service:compileTestJava` | PaymentQueryHttpClient.java | ⬜ pending |
| 05-T2 | 03-05 | 3 | RST-03 | 분산락·멱등 release | integration (MySQL) | `:product-service:test --tests OrphanReservationRecoveryIntegrationTest` | OrphanReservationRecoveryScheduler.java | ⬜ pending |
| 05-T3 | 03-05 | 3 | RST-03 | orphan release·경계·skip | integration (MySQL) | `:product-service:test --tests OrphanReservationRecoveryIntegrationTest` | OrphanReservationRecoveryIntegrationTest.java | ⬜ pending |

## Wave 0 Requirements

- [x] product-service Kafka consumer 인프라(KafkaConsumerConfig) + Testcontainers Kafka — order-service 패턴 복제(product 최초 Kafka) → **03-01 Task 3에 포함**(build.gradle spring-kafka + testcontainers:kafka, KafkaConsumerConfig, application.yml kafka)

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| 라이브 취소→재고 복원 왕복(payment→Kafka→product) | RST-02 | 멀티서비스 라이브 필요 | 배포 후 취소 발생 시 product available_qty 원복 확인 |

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] 취소 코어 로직 불변(기존 취소 통합테스트 무회귀) 검증 포함
- [ ] 부분취소·멱등(cancelRequestId) 복원 검증
- [ ] `nyquist_compliant: true` set

**Approval:** pending
