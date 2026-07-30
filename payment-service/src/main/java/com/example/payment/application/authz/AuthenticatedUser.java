package com.example.payment.application.authz;

/**
 * 신뢰 헤더 원문을 담는 얇은 carrier record (판정 로직 없음).
 *
 * 게이트웨이(Phase 2)가 검증 후 재주입한 X-User-* 헤더 원문을 그대로 운반한다 (D-P3-3).
 * 참조 자산 ADAPT: USER self-cancel 판정 분기 제거 — 판정은 domain CancelAuthorizer 가 한다.
 * userId 는 인가에 사용하지 않고 감사 로깅 용도로만 보관한다 (D-P3-7).
 */
public record AuthenticatedUser(String userId, String role, String merchantId) {
}
