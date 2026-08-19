# Phase 2 — External API Coverage Matrix

**Trigger:** `PgCancelHttpClient.getStatus()` (RESIL-01) integrates an external payment-gateway
cancel-status endpoint. `RiskManagementHttpClient.isCharged()` wires an EXISTING internal
endpoint (`GET /internal/cancel-limit/check`) — internal service, not an external-API surface
expansion, so not enumerated here.

**Detector:** `query api-coverage` command not available in this toolchain build; the reasoned
declaration below stands in per the API-Coverage contribution ("write a reasoned declaration if
you judge no genuine external-API surface expansion").

## PG (payment gateway) cancel capability surface

| Capability (verb) | Endpoint (assumed/known) | Disposition | Reason |
|-------------------|--------------------------|-------------|--------|
| cancel (취소 실행) | `POST /v1/payments/{paymentKey}/cancel` | ALREADY-INTEGRATED | `PgCancelHttpClient.cancel()` (기존, pre-Phase-2) |
| cancel-status (취소 상태조회) | `GET /v1/payments/{paymentKey}/cancel/status` `[ASSUMED — D-01]` | INTEGRATE (this phase) | RESIL-01 복구 경로가 요구. 근거 문서 없음 → `checkpoint:human-verify` 게이트 (Plan 02-01 Task 1). costly reversibility. |
| status/query (기타 조회 verb: 잔여취소·부분취소 조회 등) | (미확인) | OPT-OUT | 현 도메인 규칙상 paymentKey당 진행 중 취소 최대 1건 → 단건 cancel-status 조회로 충분. 부분취소 재도입 시 재검토 (RESEARCH Assumption A2). |

**Net:** 이 페이즈의 외부 PG 표면 확장은 cancel-status 단일 verb 뿐이며, 그마저도 ASSUMED
계약이라 human-verify로 게이트한다. 신규 opt-out은 도메인 불변식으로 정당화됨.
