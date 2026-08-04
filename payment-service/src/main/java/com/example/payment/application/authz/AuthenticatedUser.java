package com.example.payment.application.authz;

/**
 * 신뢰 헤더 원문을 담는 얇은 carrier record (판정 로직 없음).
 *
 * 게이트웨이(Phase 2)가 검증 후 재주입한 X-User-* 헤더 원문을 그대로 운반한다 (D-P3-3).
 * 판정은 domain CancelAuthorizer 가 한다.
 * userId 는 USER 자가취소 소유 판정에 사용(P3) — MERCHANT/ADMIN 경로에서는 미사용.
 */
public record AuthenticatedUser(String userId, String role, String merchantId) {
}
