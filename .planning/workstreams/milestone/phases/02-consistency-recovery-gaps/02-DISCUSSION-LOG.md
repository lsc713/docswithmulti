# Phase 2 — Discussion Log

**Date:** 2026-07-28 · human reference only (downstream agents read CONTEXT.md).

## Gray areas presented
1. 멱등 응답 시맨틱 (RESIL-02)
2. 동시성 가드 강도 (RESIL-03)
3. **PG getStatus 계약 (RESIL-01)** ← 선택
4. **동시성 테스트 재현 범위** ← 선택

사용자가 3·4를 선택. 1·2는 코드/스펙이 이미 규정 → Claude 재량 기본값으로 기록.

## Area: PG getStatus 계약 (RESIL-01)
- Q: 운영 PG의 '취소 상태조회' 계약을 어떻게?
  - 옵션: 실 상태조회 엔드포인트 계약화 / PG 조회 미지원→멱등 재취소 대체 / 계약만 정의+WireMock 검증
  - **선택: 실 상태조회 엔드포인트 계약화** → D-01
- 발견: `isCharged`는 기존 `GET /internal/cancel-limit/check` 존재 → 배선만(D-05). `MockPgCancelClient.getStatus`는 local에서 이미 구현, 실 `PgCancelHttpClient.getStatus`만 스텁.

## Area: 동시성 테스트 재현 범위
- Q: 멀티파드 레이스·스케줄러 동시 실행을 어디까지 충실히 재현?
  - 옵션: Testcontainers+동시 스레드 / 실제 2 인스턴스 / Mockito 단위
  - **선택: Testcontainers + 동시 스레드** → D-02

## Claude's discretion (기록)
- D-03 멱등 응답: api-spec.md 200+status 형태 준수, saureTx1 DuplicateKey catch→재조회→기존 스위치.
- D-04 가드 강도: pg_retry_count 원자 UPDATE only, 레코드 락 미추가(YAGNI).

## Deferred
- 레코드 단위 분산락, 실 PG 상태조회 근거 문서, Phase 1 기준선 의존.
