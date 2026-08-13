package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentEventOutboxRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.exception.PaymentAttemptException;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PaymentAttemptTxWriter {
    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentEventOutboxRepository outboxRepository;

    @Transactional
    public Payment prepare(
        String paymentRequestId, CreatePaymentCommand command, BigDecimal amount, long orderId
    ) {
        Payment payment = paymentRepository.save(Payment.pendingAttempt(
            paymentRequestId, command.merchantId(), command.userId(), "NORMAL", amount,
            "KRW", command.cancelPeriodDays(), orderId));
        paymentItemRepository.saveAll(command.items().stream()
            .map(item -> PaymentItem.of(
                payment.getId(), item.orderItemId(), item.productId(), item.productId(),
                item.itemName(), item.itemAmount(), item.skuId(), item.quantity()))
            .toList());
        return payment;
    }

    @Transactional
    public AttachResult attach(String paymentRequestId, long userId, String paymentKey) {
        Payment payment = locked(paymentRequestId);
        requireOwner(payment, userId);
        if (payment.getStatus() != com.example.payment.domain.entity.PaymentStatus.PENDING) {
            return new AttachResult(payment, false);
        }
        boolean attached = payment.attachPaymentKey(paymentKey);
        if (attached) payment = paymentRepository.save(payment);
        return new AttachResult(payment, attached);
    }

    @Transactional
    public Payment complete(String paymentRequestId) {
        Payment payment = locked(paymentRequestId);
        if (!payment.complete()) return payment;
        payment = paymentRepository.save(payment);
        List<PaymentItem> items = paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId());
        outboxRepository.insertPending(payment.getPaymentKey(), payload(payment, items));
        return payment;
    }

    @Transactional
    public FailureResult failUnconfirmed(String paymentRequestId, long userId) {
        Payment payment = locked(paymentRequestId);
        if (payment.getUserId() != userId) {
            throw new PaymentAttemptException(ErrorCode.FORBIDDEN_PAYMENT);
        }
        return fail(payment, payment.failUnconfirmed());
    }

    @Transactional
    public FailureResult failConfirmed(String paymentRequestId) {
        Payment payment = locked(paymentRequestId);
        return fail(payment, payment.failConfirmed());
    }

    @Transactional
    public FailureResult expire(String paymentRequestId) {
        Payment payment = locked(paymentRequestId);
        return fail(payment, payment.failUnconfirmed());
    }

    private FailureResult fail(Payment payment, boolean changed) {
        if (!changed) return new FailureResult(payment, false, List.of());
        payment = paymentRepository.save(payment);
        List<ProductStockPort.Item> items = paymentItemRepository
            .findAllByPaymentIdForUpdate(payment.getId()).stream()
            .filter(item -> item.getSkuId() != null)
            .map(item -> new ProductStockPort.Item(
                item.getProductId(), item.getSkuId(), item.getQuantity()))
            .toList();
        return new FailureResult(payment, true, items);
    }

    private Payment locked(String paymentRequestId) {
        return paymentRepository.findByPaymentRequestIdForUpdate(paymentRequestId)
            .orElseThrow(() -> new IllegalArgumentException("결제 시도를 찾을 수 없습니다."));
    }

    private void requireOwner(Payment payment, long userId) {
        if (payment.getUserId() != userId) {
            throw new SecurityException("결제 시도 소유자가 아닙니다.");
        }
    }

    private String payload(Payment payment, List<PaymentItem> items) {
        String itemsJson = items.stream()
            .map(i -> String.format("{\"paymentItemId\":%d,\"itemAmount\":%s}",
                i.getId(), i.getItemAmount().toPlainString()))
            .collect(Collectors.joining(",", "[", "]"));
        return String.format(
            "{\"paymentKey\":\"%s\",\"orderId\":%d,\"merchantId\":%d,\"totalAmount\":%s,"
                + "\"items\":%s,\"completedAt\":\"%s\"}",
            payment.getPaymentKey(), payment.getOrderId(), payment.getMerchantId(),
            payment.getTotalAmount().toPlainString(), itemsJson,
            payment.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    public record AttachResult(Payment payment, boolean shouldConfirm) {}
    public record FailureResult(
        Payment payment, boolean shouldRelease, List<ProductStockPort.Item> items
    ) {}
}
