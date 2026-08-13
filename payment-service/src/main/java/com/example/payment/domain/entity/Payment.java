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
    private final String paymentRequestId;
    private String paymentKey;
    private final long merchantId;
    private final long userId;
    private final String pgType;
    private final BigDecimal totalAmount;
    private final String currency;
    private final int cancelPeriodDays;
    // order-service 검증된 orderId (PLINK-02). 레거시/취소 테스트 seed 행은 0L 센티널(order 미링크,
    // 취소 코어는 order_id를 읽지 않음) — 아래 7/8-인자 of()·ofPending()·11-인자 reconstruct()가 위임.
    private final long orderId;
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
        long orderId,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this(id, java.util.UUID.randomUUID().toString(), paymentKey, merchantId, userId, pgType, totalAmount, currency,
            cancelPeriodDays, orderId, status, createdAt, updatedAt);
    }

    private Payment(
        long id,
        String paymentRequestId,
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        long orderId,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.paymentRequestId = paymentRequestId;
        this.paymentKey = paymentKey;
        this.merchantId = merchantId;
        this.userId = userId;
        this.pgType = pgType;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.cancelPeriodDays = cancelPeriodDays;
        this.orderId = orderId;
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
            0L, // orderId 센티널: 레거시/취소 테스트 seed 행(order 미링크, 취소 코어는 읽지 않음)
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
            0L, // orderId 센티널
            PaymentStatus.COMPLETED,
            createdAt,
            createdAt
        );
    }

    /**
     * 결제 생성 경로 전용: order-service 검증된 orderId를 실어 신규 Payment를 생성한다 (PLINK-02/03).
     * 7-인자 of()와 달리 실제 orderId를 저장한다 — CreatePaymentService/PaymentCreateTxWriter만 호출.
     */
    public static Payment of(
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        long orderId
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
            orderId,
            PaymentStatus.COMPLETED,
            now,
            now
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
            0L, // orderId 센티널
            PaymentStatus.PENDING,
            now,
            now
        );
    }

    public static Payment pendingAttempt(
        String paymentRequestId,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        long orderId
    ) {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return new Payment(
            0, paymentRequestId, null, merchantId, userId, pgType, totalAmount,
            currency, cancelPeriodDays, orderId, PaymentStatus.PENDING, now, now);
    }

    /**
     * DB에서 조회한 데이터로 Payment를 재구성한다 (infrastructure 계층용)
     * orderId 없는 레거시 호출부(취소 플로우 테스트 등) 호환 — 0L 센티널 위임.
     */
    public static Payment reconstruct(
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
        return reconstruct(
            id, paymentKey, merchantId, userId, pgType, totalAmount, currency,
            cancelPeriodDays, 0L, status, createdAt, updatedAt
        );
    }

    /**
     * DB에서 조회한 데이터로 Payment를 재구성한다 (orderId 포함, PaymentJpaEntity.toDomain 전용).
     */
    public static Payment reconstruct(
        long id,
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        long orderId,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        return new Payment(
            id,
            paymentKey,
            merchantId,
            userId,
            pgType,
            totalAmount,
            currency,
            cancelPeriodDays,
            orderId,
            status,
            createdAt,
            updatedAt
        );
    }

    public static Payment reconstruct(
        long id,
        String paymentRequestId,
        String paymentKey,
        long merchantId,
        long userId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        int cancelPeriodDays,
        long orderId,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        return new Payment(
            id, paymentRequestId, paymentKey, merchantId, userId, pgType, totalAmount,
            currency, cancelPeriodDays, orderId, status, createdAt, updatedAt);
    }

    public boolean attachPaymentKey(String paymentKey) {
        if (this.paymentKey != null && !this.paymentKey.equals(paymentKey)) {
            throw new IllegalStateException("다른 PG paymentKey가 이미 연결되어 있습니다.");
        }
        if (this.paymentKey != null) {
            return false;
        }
        this.paymentKey = paymentKey;
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return true;
    }

    public boolean complete() {
        if (status == PaymentStatus.COMPLETED) {
            return false;
        }
        if (status != PaymentStatus.PENDING || paymentKey == null) {
            throw new IllegalStateException("승인 대기 결제만 완료할 수 있습니다.");
        }
        updateStatus(PaymentStatus.COMPLETED);
        return true;
    }

    public boolean failUnconfirmed() {
        if (status != PaymentStatus.PENDING || paymentKey != null) return false;
        updateStatus(PaymentStatus.FAILED);
        return true;
    }

    public boolean failConfirmed() {
        if (status != PaymentStatus.PENDING || paymentKey == null) return false;
        updateStatus(PaymentStatus.FAILED);
        return true;
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

    public String getPaymentRequestId() {
        return paymentRequestId;
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

    public long getOrderId() {
        return orderId;
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
