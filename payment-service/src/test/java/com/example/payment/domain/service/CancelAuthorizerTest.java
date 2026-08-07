package com.example.payment.domain.service;

import com.example.payment.common.exception.ErrorCode;
import com.example.payment.common.exception.domain.CancelNotAuthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 취소 인가 판정 도메인 규칙 매트릭스 (D-P3-2) — 순수 POJO 단위.
 *
 * Spring/Mockito 없이 primitive 인자만으로 6종 매트릭스를 exhaustive 하게 검증한다.
 * 실패 경로는 모두 CancelNotAuthorizedException(FORBIDDEN_PAYMENT, 403).
 */
@DisplayName("CancelAuthorizer 인가 매트릭스")
class CancelAuthorizerTest {

    private final CancelAuthorizer authorizer = new CancelAuthorizer();

    @Test
    @DisplayName("(1) ADMIN → 대상 merchantId 무관 통과")
    void admin_always_authorized() {
        // 대상 merchantId 가 무엇이든(null 포함) 통과
        assertThatCode(() -> authorizer.authorize("ADMIN", null, null, null, 999L)).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.authorize("ADMIN", null, null, 1L, 2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(2) MERCHANT + 헤더 merchantId == 대상 merchantId → 통과")
    void merchant_matching_merchant_authorized() {
        assertThatCode(() -> authorizer.authorize("MERCHANT", null, null, 7L, 7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(3) MERCHANT + 헤더 merchantId != 대상 merchantId → 403")
    void merchant_mismatch_forbidden() {
        assertForbidden(() -> authorizer.authorize("MERCHANT", null, null, 7L, 8L));
    }

    @Test
    @DisplayName("(4) MERCHANT + 헤더 merchantId 누락(null) → 403")
    void merchant_missing_header_forbidden() {
        assertForbidden(() -> authorizer.authorize("MERCHANT", null, null, null, 7L));
    }

    @Test
    @DisplayName("(5) USER 는 직접취소 불가 → 403 (P3: 승인 요청 흐름으로 전환)")
    void user_direct_cancel_forbidden() {
        assertForbidden(() -> authorizer.authorize("USER", 7L, 7L, null, null));
    }

    @Test
    @DisplayName("(5b) USER + 타인 결제(requestUserId != targetUserId) → 403")
    void user_non_owner_forbidden() {
        assertForbidden(() -> authorizer.authorize("USER", 7L, 8L, null, null));
    }

    @Test
    @DisplayName("(5c) USER + requestUserId 누락(null) → 403")
    void user_missing_request_user_id_forbidden() {
        assertForbidden(() -> authorizer.authorize("USER", null, 7L, null, null));
    }

    @Test
    @DisplayName("(6) role 누락(null) → 403")
    void missing_role_forbidden() {
        assertForbidden(() -> authorizer.authorize(null, 7L, 7L, null, null));
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOf(CancelNotAuthorizedException.class)
            .extracting(e -> ((CancelNotAuthorizedException) e).getErrorCode())
            .isEqualTo(ErrorCode.FORBIDDEN_PAYMENT)
            .extracting(ErrorCode::getHttpStatus)
            .isEqualTo(403);
    }
}
