---
phase: 2
slug: payment-product
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-30
---

# Phase 2 — Validation Strategy

> payment↔product 통합. Per-task map은 planner가 채운다.

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Mockito + Testcontainers (MySQL) + MockRestServiceServer(product HTTP 스텁 — WireMock 미도입, Spring 내장 사용) |
| **Quick run command** | `./gradlew :payment-service:test` |
| **Full suite command** | `./gradlew :payment-service:test` |
| **Core-unchanged gate** | `git diff --name-only $(git merge-base HEAD origin/main)..HEAD \| grep -E 'CancelPaymentService\|CancelTxWriter\|CancelDomainService\|ProcessingRecoveryScheduler\|PendingRecoveryScheduler\|CompensationRetryScheduler\|CancelEventOutboxPublisher\|infrastructure/messaging\|cancel_event_outbox'` 매치 없음(exit 1). 취소/outbox 코어 무변경(CancelEventOutboxPublisher = OUTBOX 정식 발행+purge, 취소 코어 1급) |

## Sampling Rate

- **After every task commit:** task의 `<automated>` 명령.
- **After wave:** `./gradlew :payment-service:test`
- **Before verify:** Full suite green + core-unchanged gate.

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 02-01-T1 | 02-01 | 1 | RSV-02 | skuId/quantity 입력검증(T-02-01) | unit+persistence | `./gradlew :payment-service:test --tests PaymentItemTest --tests PaymentItemRepositoryImplTest` | PaymentItemTest.java | ⬜ pending |
| 02-01-T2 | 02-01 | 1 | RSV-01 | fail-closed CB(T-02-02) | unit(mock RestTemplate) | `./gradlew :payment-service:test --tests ProductStockHttpClientTest` | ProductStockHttpClientTest.java | ⬜ pending |
| 02-01-T3 | 02-01 | 1 | RSV-01 | 오버셀 방지 fail-closed(T-02-03) | integration(Testcontainers+MockRestServiceServer) | `./gradlew :payment-service:test --tests CreatePaymentServiceTest --tests CreatePaymentReserveIntegrationTest` | CreatePaymentReserveIntegrationTest.java | ⬜ pending |
| 02-02-T1 | 02-02 | 2 | RSV-03 | 재고 누수 보상(T-02-05) | unit(Mockito) | `./gradlew :payment-service:test --tests CreatePaymentCompensationTest` | CreatePaymentCompensationTest.java | ⬜ pending |
| 02-02-T2 | 02-02 | 2 | RSV-03 | 중복재시도 락(T-02-06) | unit(Mockito) | `./gradlew :payment-service:test --tests StockReleaseRetryServiceTest` | StockReleaseRetryServiceTest.java | ⬜ pending |

## Wave 0 Requirements

- [ ] product HTTP 스텁 = MockRestServiceServer.createServer(공유 RestTemplate 빈)로 reserve/release 응답 스텁(02-01-T3에 포함, 신규 의존성 없음)

## Manual-Only Verifications

*reserve 실패 시 결제 거부·보상은 WireMock+Mockito로 자동 커버 목표. 라이브 payment↔product 왕복은 배포 시점.*

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] 취소 코어 불변 게이트(git diff) 포함
- [ ] reserve 실패 → 결제 거부, 예약 후 TX 실패 → 보상 검증
- [ ] `nyquist_compliant: true` set

**Approval:** pending
