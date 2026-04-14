package com.example.payment.domain.entity;

import com.example.payment.domain.BaseEntity;
import com.example.payment.domain.exception.CancelAmountExceededException;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 결제 항목 도메인 엔티티
 *
 * 개별 상품/서비스에 대한 결제 정보와 취소 추적을 관리한다.
 */
@Getter
public class PaymentItem extends BaseEntity {

    private final long id;
    private final long paymentId;
    private final long orderItemId;
    private final long productId;
    private final long productAutoId;
    private final String itemName;
    private final BigDecimal itemAmount;
    private BigDecimal cancelledAmount;
    private PaymentItemStatus status;

    private PaymentItem(
        long id,
        long paymentId,
        long orderItemId,
        long productId,
        long productAutoId,
        String itemName,
        BigDecimal itemAmount,
        BigDecimal cancelledAmount,
        PaymentItemStatus status
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderItemId = orderItemId;
        this.productId = productId;
        this.productAutoId = productAutoId;
        this.itemName = itemName;
        this.itemAmount = itemAmount;
        this.cancelledAmount = cancelledAmount;
        this.status = status;
    }

    /**
     * PaymentItem을 생성한다 (신규)
     */
    public static PaymentItem of(
        long paymentId,
        long orderItemId,
        long productId,
        long productAutoId,
        String itemName,
        BigDecimal itemAmount
    ) {
        return new PaymentItem(
            0, // DB에서 자동 생성
            paymentId,
            orderItemId,
            productId,
            productAutoId,
            itemName,
            itemAmount,
            BigDecimal.ZERO,
            PaymentItemStatus.ACTIVE
        );
    }

    /**
     * 취소 가능액을 계산한다
     * = itemAmount - cancelledAmount
     */
    public BigDecimal getAvailableCancelAmount() {
        return itemAmount.subtract(cancelledAmount);
    }

    /**
     * 항목을 부분 취소한다
     *
     * @param cancelAmount 취소 금액
     * @throws CancelAmountExceededException 취소 가능액 초과 시
     */
    public void cancelPartially(BigDecimal cancelAmount) {
        if (cancelAmount.compareTo(BigDecimal.ONE) < 0) {
            throw new CancelAmountExceededException(orderItemId, cancelAmount, getAvailableCancelAmount());
        }

        if (cancelAmount.compareTo(getAvailableCancelAmount()) > 0) {
            throw new CancelAmountExceededException(
                orderItemId,
                cancelAmount,
                getAvailableCancelAmount()
            );
        }

        this.cancelledAmount = cancelledAmount.add(cancelAmount);

        // 상태 전이: 전액 취소되었는지 확인
        if (cancelledAmount.compareTo(itemAmount) >= 0) {
            this.status = PaymentItemStatus.CANCELLED;
        } else {
            this.status = PaymentItemStatus.PARTIAL_CANCELLED;
        }
    }

    /**
     * 취소 가능한 상태인지 확인
     */
    public boolean isCancellable() {
        return status.isCancellable();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentItem that = (PaymentItem) o;
        return id == that.id && paymentId == that.paymentId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, paymentId);
    }

    public void updateStatusAndDate(PaymentItemStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
}
