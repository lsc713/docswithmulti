package com.example.payment.domain.entity;

import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import java.math.BigDecimal;
import java.util.Objects;

public class PaymentItem {

    private final long id;
    private final long paymentId;
    private final long orderItemId;
    private final long productId;
    private final long productAutoId;
    private final String itemName;
    private final BigDecimal itemAmount;
    private final Long skuId;
    private final int quantity;
    private PaymentItemStatus status;

    private PaymentItem(
        long id, long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount,
        Long skuId, int quantity,
        PaymentItemStatus status
    ) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderItemId = orderItemId;
        this.productId = productId;
        this.productAutoId = productAutoId;
        this.itemName = itemName;
        this.itemAmount = itemAmount;
        this.skuId = skuId;
        this.quantity = quantity;
        this.status = status;
    }

    /** 하위호환 오버로드(6인자): skuId=null, quantity=1. 취소 코어 테스트가 호출하므로 보존(D-P2-7). */
    public static PaymentItem of(
        long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount
    ) {
        return of(paymentId, orderItemId, productId, productAutoId, itemName, itemAmount, null, 1);
    }

    /** 생성 경로용(8인자): SKU 예약 수량 포함. */
    public static PaymentItem of(
        long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount,
        Long skuId, int quantity
    ) {
        return new PaymentItem(0, paymentId, orderItemId, productId, productAutoId,
            itemName, itemAmount, skuId, quantity, PaymentItemStatus.ACTIVE);
    }

    /** 하위호환 오버로드(8인자): skuId=null, quantity=1. 취소 코어 테스트가 호출하므로 보존(D-P2-7). */
    public static PaymentItem reconstruct(
        long id, long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount,
        PaymentItemStatus status
    ) {
        return reconstruct(id, paymentId, orderItemId, productId, productAutoId,
            itemName, itemAmount, null, 1, status);
    }

    /** DB에서 조회한 데이터로 재구성 (infrastructure 계층용, 10인자: skuId/quantity 포함) */
    public static PaymentItem reconstruct(
        long id, long paymentId, long orderItemId,
        long productId, long productAutoId,
        String itemName, BigDecimal itemAmount,
        Long skuId, int quantity,
        PaymentItemStatus status
    ) {
        return new PaymentItem(id, paymentId, orderItemId, productId, productAutoId,
            itemName, itemAmount, skuId, quantity, status);
    }

    /** 아이템 전액 취소. ACTIVE 상태에서만 가능. */
    public void cancel() {
        if (!isCancellable()) {
            throw new InvalidPaymentItemStatusException(id, status);
        }
        this.status = PaymentItemStatus.CANCELLED;
    }

    public boolean isCancellable() { return status.isCancellable(); }

    public long getId() { return id; }
    public long getPaymentId() { return paymentId; }
    public long getOrderItemId() { return orderItemId; }
    public long getProductId() { return productId; }
    public long getProductAutoId() { return productAutoId; }
    public String getItemName() { return itemName; }
    public BigDecimal getItemAmount() { return itemAmount; }
    public Long getSkuId() { return skuId; }
    public int getQuantity() { return quantity; }
    public PaymentItemStatus getStatus() { return status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentItem that = (PaymentItem) o;
        return id == that.id && paymentId == that.paymentId;
    }

    @Override
    public int hashCode() { return Objects.hash(id, paymentId); }
}
