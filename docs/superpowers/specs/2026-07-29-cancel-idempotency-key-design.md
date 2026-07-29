# 결제 취소 멱등성 재구성 — 클라이언트 Idempotency-Key (설계)

**작성:** 2026-07-29
**상태:** 설계 확정 (구현 대기)
**범위:** payment-service 취소 요청 멱등성 키. (Outbox 발행 재기획은 별도 스펙 — 완료·머지됨.)

---

## 1. 문제 (Problem)

현재 취소 멱등성은 **서버 자체 생성 content-hash**만 사용한다:
```
request_hash = SHA-256(paymentKey + sorted(paymentItemIds).toString())
UK: uk_cancel_request_hash (payment_id, request_hash)   // "따닥" 차단
```
클라이언트 `Idempotency-Key`를 받지 않는다. 업계 표준(Stripe·**Toss 취소 API는 `Idempotency-Key` 헤더 지원**)과 어긋나, 클라이언트가 재시도 dedup을 명시적으로 제어할 수 없다.

**설계 이력(오해 방지):** 초기 구현(commit a995b72)의 V8 마이그레이션이 `idempotency_key`(컬럼+별도 테이블)를 제거하고 `request_hash`로 통합했다. 이는 실패한 클라 키 실험의 반전이 아니라 **초기 과설계 스키마의 단순화**였다. 지금은 Toss 정합이라는 명확한 이유로 이를 재방문하며, 아래 설계는 두 방식의 **상위집합**(content-hash 유지 + 클라 키 추가)이다.

**핵심 규명 — 이 시스템의 두 가드는 이미 분리돼 있다:**
| 가드 | 위치 | 역할 |
|------|------|------|
| request_hash UK | `cancel_request(payment_id, request_hash)` | 요청-재시도 dedup ("따닥") |
| **이중취소 money guard** | TX3 `findAllByPaymentIdForUpdate` 행 락 + `cancelDomainService.apply`(CANCELLED 아이템 거부 → `InvalidPaymentItemStatusException`) | 실제 돈 가드 (RESIL-02/03에서 동시 패자가 이 예외로 확인) |
→ 클라 키 도입은 **dedup 레이어 변경**이며 money guard는 무변경. 안전.

## 2. 목표 / 비목표

**목표**
- 클라이언트 `Idempotency-Key`(optional) 수용 — Toss/Stripe 정합, 명시적 재시도 dedup.
- content-hash **fallback 유지** — 키 미전송 클라이언트 완전 back-compat.
- 키 재사용 일관성 검증 — 같은 키 + 다른 요청 → **409 Conflict** (Stripe/Toss 동작).
- money guard(아이템 상태머신) 무변경.

**비목표**
- TTL/키 만료 (취소는 terminal 1회성 — YAGNI).
- Outbox 발행 (별건, 완료).
- order-service 변경.

## 3. 설계 결정

- **D1 — Optional + fallback.** `Idempotency-Key` 헤더 있으면 그걸로 dedup, 없으면 기존 content-hash로 fallback.
- **D2 — 네임스페이스 접두 유효키 + UK 교체.** `dedup_key = idempotency_key ? "ik:"+key : "ch:"+request_hash`. UK `(payment_id, request_hash)` → `(payment_id, dedup_key)`. `request_hash` 컬럼은 **유지**(재사용 검증 fingerprint). 접두(`ik:`/`ch:`)로 클라 키가 content-hash(64-hex)와 충돌하는 것 차단.
- **D3 — 재사용 일관성 409.** 키 있는 요청 → `(payment_id, idempotency_key)` 조회. 있고 저장 `request_hash` **같음** → 멱등 반환 / **다름** → 409(신규 에러코드) / 없음 → 신규.
- **D4 — money guard 무변경, TTL 없음.**
- **D5 — dedup_key = MySQL generated STORED column.** DB가 유효키 계산, UK가 원자적 강제. 앱은 `idempotency_key`(or null)만 INSERT. 기존 행은 자동으로 `"ch:"+request_hash`.

## 4. 스키마 (Flyway 신규 — 현재 최고 V14 → **V15**)

```sql
-- V15__add_cancel_idempotency_key.sql
ALTER TABLE cancel_request
    ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER request_hash,
    ADD COLUMN dedup_key VARCHAR(300)
        AS (CONCAT(CASE WHEN idempotency_key IS NOT NULL THEN 'ik:' ELSE 'ch:' END,
                   COALESCE(idempotency_key, request_hash))) STORED,
    DROP KEY uk_cancel_request_hash,
    ADD UNIQUE KEY uk_cancel_request_dedup (payment_id, dedup_key);
```
- `request_hash`는 NOT NULL 유지(항상 계산). `idempotency_key` nullable.
- generated STORED + UK: MySQL 8 지원 확인 완료.

## 5. 아키텍처 / 데이터 흐름

```
POST /v1/payments/{paymentKey}/cancel  [Idempotency-Key: <opt>]
  → CancelController: @RequestHeader(required=false) idempotencyKey → CancelPaymentCommand
  → CancelPaymentService.cancel:
      requestHash = RequestHashGenerator.generate(paymentKey, itemIds)   // 항상 계산(fingerprint)
      effectiveDedup = idempotencyKey != null ? "ik:"+idempotencyKey : "ch:"+requestHash
      existing = cancelRequestRepository.findByPaymentIdAndDedupKey(paymentId, effectiveDedup)
      if existing:
          if idempotencyKey != null && existing.requestHash != requestHash → 409 (키 재사용 불일치)
          else → handleExistingRequest(existing)   // 멱등 반환/FAILED 재구동 (기존)
      else → executeCancel(...)  // saveTx1 INSERT(idempotency_key 포함); UK 위반 시 race-loser catch → findByPaymentIdAndDedupKey 재조회
```

## 6. 통합 지점 (기존 코드 영향)

- **`request_hash`가 더는 payment 내 unique가 아니다**(같은 아이템+다른 키 → 같은 request_hash, 다른 dedup_key). `findByPaymentIdAndRequestHash`는 다중 행 가능 → **`findByPaymentIdAndDedupKey`로 교체.** 3곳:
  - `CancelPaymentService.java:54` (멱등 조회) → 이번 요청의 effectiveDedup
  - `CancelPaymentService.java:110` (saveTx1 UK 위반 race-loser catch) → effectiveDedup 재조회 (UK가 dedup_key로 이동)
  - `ProcessingRecoveryService.java:119` (incrementPgRetryCount 후 동일 행 재조회) → 그 행의 dedup_key(또는 id)로 재조회
- `CancelController.cancel` — `@RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey` 추가 → command.
- `CancelPaymentCommand` — `String idempotencyKey` 필드 추가(nullable).
- 도메인 `CancelRequest` + JPA `CancelRequestJpaEntity` — `idempotencyKey` 필드/매핑(도메인 레이어 JPA 어노테이션 금지). `create`/`reconstruct` 시그니처 확장.
- `RequestHashGenerator`는 그대로(content-hash는 fingerprint로 항상 필요).
- 리포지토리 인터페이스/JPA: `findByPaymentIdAndDedupKey` 추가.

## 7. 에러 / API / 문서

- `error-catalog.md`: 신규 `IDEMPOTENCY_KEY_CONFLICT` (HTTP 409) — 같은 키 + 다른 요청.
- `api-spec.md`: 취소 엔드포인트에 `Idempotency-Key`(optional) 헤더 + 409 응답 명시.
- `domain-rules.md` §멱등성: content-hash 단독 → "클라 Idempotency-Key(있으면) / content-hash(fallback), dedup_key UK" 로 갱신.
- `db-schema.md` + `CLAUDE.md` 핵심 불변식의 request_hash 서술 갱신.
- `GlobalExceptionHandler`: 409 매핑.

## 8. 엣지 케이스

- **keyed / unkeyed 혼용(같은 취소):** dedup_key가 `ik:` vs `ch:`로 달라 서로 dedup 못 함 → 두 번째가 executeCancel까지 가나 **아이템 상태머신이 이미 CANCELLED를 거부**(InvalidPaymentItemStatusException) → 정합 보호, 멱등 응답으로 수렴. 클라 일관성 문제일 뿐 돈 안전.
- **키 길이/형식:** ≤255 검증(초과 400). null/blank는 미전송으로 취급(fallback).
- **FAILED 재시도 + 키:** 같은 키로 재시도 → 같은 dedup_key → 기존 FAILED 행 → handleExistingRequest FAILED 분기(raiseToPending) 정상 동작.

## 9. 테스트 전략

- 단위: `RequestHashGenerator` 불변; effectiveDedup 계산(키 유/무); 409 재사용(같은 키+다른 아이템); 멱등 반환(같은 키+같은 아이템); fallback(키 없음 = 기존 동작).
- 통합(Testcontainers): UK가 dedup_key로 dedup(키/무키); generated column 값 검증; race-loser catch가 dedup_key로 재조회; 기존 RESIL-02 `CancelRaceIdempotencyIT`가 무키 경로에서 green 유지.
- 회귀: `./gradlew :payment-service:test` 전체 green.

## 10. 미해결/후속
- api-spec/error-catalog 클라이언트 배포 조율(헤더 도입 안내).
- request_hash 입력 canonicalization 견고성(구분자 충돌)은 별건 — 이번 스코프는 키 도입에 한정.
