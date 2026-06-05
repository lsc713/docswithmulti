package com.example.payment.application.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.application.CancelRequestNotFoundException;
import com.example.payment.common.exception.application.IdempotentDuplicationException;
import com.example.payment.common.exception.application.MerchantCancelLimitExceededException;
import com.example.payment.common.exception.application.MerchantCancelLimitNotFoundException;
import com.example.payment.common.exception.application.PaymentNotFoundException;
import com.example.payment.domain.entity.CancelStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationExceptionTest {

    @Test
    void payment_not_found_stores_payment_key() {
        PaymentNotFoundException e = new PaymentNotFoundException("pk-1");

        assertThat(e.getPaymentKey()).isEqualTo("pk-1");
        assertThat(e).isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_request_not_found() {
        CancelRequestNotFoundException e = new CancelRequestNotFoundException(42L);

        assertThat(e).isInstanceOf(BusinessException.class);
    }

    @Test
    void idempotent_duplication_stores_fields() {
        IdempotentDuplicationException e = new IdempotentDuplicationException("cr-1", CancelStatus.PROCESSING);

        assertThat(e.getCancelRequestId()).isEqualTo("cr-1");
        assertThat(e.getOriginalStatus()).isEqualTo(CancelStatus.PROCESSING);
        assertThat(e).isInstanceOf(BusinessException.class);
    }

    @Test
    void merchant_cancel_limit_exceeded() {
        MerchantCancelLimitExceededException e = new MerchantCancelLimitExceededException(
            new BigDecimal("100000"),
            new BigDecimal("50000"),
            new BigDecimal("500000")
        );

        assertThat(e.getRequestedAmount()).isEqualByComparingTo("100000");
        assertThat(e.getRemainingLimit()).isEqualByComparingTo("50000");
        assertThat(e.getDailyLimit()).isEqualByComparingTo("500000");
        assertThat(e).isInstanceOf(BusinessException.class);
    }

    @Test
    void merchant_cancel_limit_not_found() {
        MerchantCancelLimitNotFoundException e = new MerchantCancelLimitNotFoundException(99L);

        assertThat(e.getMerchantId()).isEqualTo(99L);
        assertThat(e).isInstanceOf(BusinessException.class);
    }
}
