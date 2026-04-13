package com.example.payment.domain.entity;

import com.example.payment.domain.exception.CancelNotAllowedException;
import com.example.payment.domain.exception.CancelPeriodExceededException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 결제 도메인 엔티티
 *
 * 결제 정보와 취소 가능 여부 판단을 관리한다.
 * CancelRequest와의 관계: 1개 Payment에 N개의 CancelRequest
 */
public class Payment {

    private final long id;
    private final String paymentKey;
    private final long merchantId;
    private final long userId;
    private final String pgType;
    private final BigDecimal totalAmount;
    private final String currency;
    private final int cancelPeriodDays;
    private PaymentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Payment(
        long id,
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.paymentKey = paymentKey;
        this.merchantId = merchantId;
        this.userId = userId;
        this.pgType = pgType;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.cancelPeriodDays = cancelPeriodDays;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 신규 Payment를 생성한다 (COMPLETED 상태)
     */
    public static Payment of(
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return new Payment(
            0, // DB에서 자동 생성
            paymentKey,
            merchantId,
            userId,
            pgType,
            totalAmount,
            currency,
            cancelPeriodDays,
            PaymentStatus.COMPLETED,
            now,
            now
        );
    }

    /**
     * 특정 생성 시각으로 Payment를 생성한다
     * Fixture 또는 과거 데이터 import 등에서 사용
     */
    public static Payment of(
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        LocalDateTime createdAt
    ) {
        return new Payment(
            0,
            paymentKey,
            merchantId,
            userId,
            pgType,
            totalAmount,
            currency,
            cancelPeriodDays,
            PaymentStatus.COMPLETED,
            createdAt,
            createdAt
        );
    }

    /**
     * PENDING 상태의 Payment를 생성한다 (테스트 용도)
     */
    public static Payment ofPending(
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return new Payment(
            0,
            paymentKey,
            merchantId,
            userId,
            pgType,
            totalAmount,
            currency,
            cancelPeriodDays,
            PaymentStatus.PENDING,
            now,
            now
        );
    }

    /**
     * 취소 가능한 상태인지 확인
     */
    public boolean canBeCancelled() {
        return status.isCancellable();
    }

    /**
     * 취소 가능한 상태 검증
     *
     * @throws CancelNotAllowedException 취소 불가능한 상태일 때
     */
    public void validateCancellable() {
        if (!canBeCancelled()) {
            throw new CancelNotAllowedException(status);
        }
    }

    /**
     * 취소 가능 기간 검증
     *
     * Payment.created_at + cancel_period_days 초과 여부 확인
     *
     * @throws CancelPeriodExceededException 기간 초과일 때
     */
    public void validateCancelPeriod() {
        LocalDateTime cancelDeadline = createdAt.plus(cancelPeriodDays, ChronoUnit.DAYS);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);

        if (now.isAfter(cancelDeadline)) {
            throw new CancelPeriodExceededException(createdAt, cancelPeriodDays);
        }
    }

    /**
     * 상태를 업데이트한다
     */
    public void updateStatus(PaymentStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * 잔여 취소 가능액을 계산한다
     * = totalAmount - cancelledAmount
     */
    public BigDecimal calculateRemainingCancelAmount(BigDecimal cancelledAmount) {
        return totalAmount.subtract(cancelledAmount);
    }

    // ===== Getters =====

    public long getId() {
        return id;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public long getUserId() {
        return userId;
    }

    public String getPgType() {
        return pgType;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public int getCancelPeriodDays() {
        return cancelPeriodDays;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ===== Package-private methods (테스트용) =====

    void updateCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Payment payment = (Payment) o;
        return id == payment.id && Objects.equals(paymentKey, payment.paymentKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, paymentKey);
    }
}
