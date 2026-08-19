---
status: resolved
phase: 02-consistency-recovery-gaps
source: [02-VERIFICATION.md]
started: 2026-07-29
updated: 2026-07-29
---

## Current Test

number: 1
name: ASSUMED PG cancel-status 계약(D-01) 운영 대조 — RESOLVED
expected: |
  getStatus()/cancel() 가 실제 PG(Toss Payments) 취소 조회/실행 API 계약과 일치한다.
awaiting: none (resolved)

## Tests

### 1. ASSUMED PG cancel-status 계약(D-01) 운영 대조
expected: |
  운영 PG 취소 조회 API 문서와 getStatus()/cancel() 계약을 대조하고, 불일치 시 정정.
result: [passed]
resolution: |
  사용자가 실 PG(Toss Payments) 공식 문서를 제공(https://docs.tosspayments.com/reference).
  ASSUMED 계약이 실 Toss 와 실질적으로 불일치함을 확인:
    - 조회: ASSUMED `GET /v1/payments/{paymentKey}/cancel/status` 는 존재하지 않음
      → 실제는 `GET /v1/payments/{paymentKey}` (Payment.status + cancels[] 로 취소 결과 판별)
    - 응답: ASSUMED {pgTransactionId,status(APPROVED/FAILED/PENDING),retryable}
      → 실제 Payment{status(READY..DONE/CANCELED/PARTIAL_CANCELED/ABORTED/EXPIRED), cancels[]{transactionKey,cancelStatus,cancelAmount,...}}
    - retryable 필드 없음(HTTP 에러코드로 판단)
  → 코드 정정 완료(commits 9e53ad2·0915594·b5d6b88·021d8ad·6fa62a4, 02-04-SUMMARY):
    - getStatus(paymentKey, cancelAmount): GET /v1/payments/{paymentKey} → status/cancels[] amount 매칭 → PgCancelResult (규칙 1~7)
    - cancel(): Toss Payment 응답 파싱 → transactionKey 추출
    - transactionKey 저장: Flyway V13 + CancelRequest.pgTransactionKey (감사 + 부분취소 tiebreaker)
    - 전체 `./gradlew :payment-service:test` 230 tests green (Testcontainers 포함)

## Summary

total: 1
passed: 1
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

## Open Items (배포 전 config — 코드 아님)

1. `external.pg.url` 을 mock `http://pg-gateway:443` → 실 Toss `https://api.tosspayments.com` 로 전환.
2. RestTemplate 에 Toss Basic 인증(시크릿 키) 헤더 추가 — 현재 인증 미설정(실 Toss 호출 시 401).
   (본 정정 범위 밖 — 02-04-SUMMARY 의 config open items 참조.)
