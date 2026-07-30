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
        assertThatCode(() -> authorizer.authorize("ADMIN", null, 999L)).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.authorize("ADMIN", 1L, 2L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(2) MERCHANT + 헤더 merchantId == 대상 merchantId → 통과")
    void merchant_matching_merchant_authorized() {
        assertThatCode(() -> authorizer.authorize("MERCHANT", 7L, 7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(3) MERCHANT + 헤더 merchantId != 대상 merchantId → 403")
    void merchant_mismatch_forbidden() {
        assertForbidden(() -> authorizer.authorize("MERCHANT", 7L, 8L));
    }

    @Test
    @DisplayName("(4) MERCHANT + 헤더 merchantId 누락(null) → 403")
    void merchant_missing_header_forbidden() {
        assertForbidden(() -> authorizer.authorize("MERCHANT", null, 7L));
    }

    @Test
    @DisplayName("(5) USER → 소유 여부 무관 403 (self-cancel 분기 미채택)")
    void user_forbidden_even_if_owner() {
        // USER 는 대상 payment 소유 여부와 무관하게 항상 403 (우리 정책은 self-cancel 미허용)
        assertForbidden(() -> authorizer.authorize("USER", 7L, 7L));
    }

    @Test
    @DisplayName("(6) role 누락(null) → 403")
    void missing_role_forbidden() {
        assertForbidden(() -> authorizer.authorize(null, 7L, 7L));
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
