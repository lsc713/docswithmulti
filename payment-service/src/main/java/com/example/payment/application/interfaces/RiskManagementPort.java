package com.example.payment.application.interfaces;

import com.example.payment.application.dto.RiskReserveResult;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface RiskManagementPort {

    RiskReserveResult validateAndReserve(long merchantId, long cancelRequestId,
                                          BigDecimal cancelAmount, LocalDate kstDate);

    void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount);

    /**
     * 차감 여부 확인. pending-recovery에서 보상 필요 여부 판단에 사용.
     * @return true: risk의 used_amount가 이미 차감된 상태
     */
    boolean isCharged(long cancelRequestId);
}
