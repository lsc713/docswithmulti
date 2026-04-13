package com.example.payment.domain.policy;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.exception.CancelPeriodExceededException;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 취소 가능 기간 검증 정책 객체
 *
 * domain-rules.md 1-3: 취소 가능 기간
 *
 * Clock을 주입받아 현재 시각을 제어 가능하게 하므로,
 * 테스트에서 시간에 의존하지 않고 일관된 결과를 얻을 수 있다.
 */
public class CancelPeriodPolicy {

    private final Clock clock;

    public CancelPeriodPolicy(Clock clock) {
        this.clock = clock;
    }

    /**
     * 취소 가능 기간 검증
     *
     * Payment.created_at 기준으로 payment.cancel_period_days 이내인지 확인
     *
     * @param payment 결제 정보
     * @throws CancelPeriodExceededException 기간을 초과했을 때
     */
    public void validateCancelPeriod(Payment payment) {
        LocalDateTime expiredAt = payment.getCreatedAt()
            .plusDays(payment.getCancelPeriodDays());
        LocalDateTime now = LocalDateTime.now(clock);

        if (now.isAfter(expiredAt)) {
            throw new CancelPeriodExceededException(
                payment.getCreatedAt(),
                payment.getCancelPeriodDays()
            );
        }
    }
}
