package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentEventOutboxRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 결제 생성 TX 경계 전담 빈 (CancelTxWriter 동형, D-P2-1).
 *
 * CreatePaymentService(비-TX 오케스트레이터)가 product reserve(TX 밖) 성공 후 이 메서드를 호출한다.
 * 별도 빈으로 분리해 @Transactional 자기호출(프록시 우회) 문제를 회피한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCreateTxWriter {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentEventOutboxRepository paymentEventOutboxRepository;

    @Transactional
    public Result persist(CreatePaymentCommand command, String paymentKey, BigDecimal totalAmount, long orderId) {
        Payment payment = Payment.of(
            paymentKey,
            command.merchantId(),
            command.userId(),
            command.pgType(),
            totalAmount,
            "KRW",
            command.cancelPeriodDays(),
            orderId
        );
        Payment saved = paymentRepository.save(payment);

        List<PaymentItem> items = command.items().stream()
            .map(item -> PaymentItem.of(
                saved.getId(),
                item.orderItemId(),
                item.productId(),
                item.productId(),   // productAutoId: productId로 대체 (PG 연동 불필요)
                item.itemName(),
                item.itemAmount(),
                item.skuId(),
                item.quantity()
            ))
            .toList();
        List<PaymentItem> savedItems = paymentItemRepository.saveAll(items);

        // TX 마지막: payment.completed 아웃박스 원자 INSERT (payment/payment_item 커밋과 같은 TX).
        // 발행은 out-of-band(PaymentCompletedOutboxPublisher 폴). 이 TX 롤백 시 아웃박스 행도 0 (dual-write 안전).
        paymentEventOutboxRepository.insertPending(
            saved.getPaymentKey(), buildCompletedPayload(saved, savedItems));

        return new Result(saved, savedItems);
    }

    /**
     * payment.completed payload 를 손수 조립(CancelTxWriter.buildPayload 동형, Jackson 미사용).
     * completedAt 은 createdAt(UTC LocalDateTime) → Instant(...Z) — settlement 의 Instant.parse 가 요구.
     */
    private String buildCompletedPayload(Payment saved, List<PaymentItem> items) {
        String itemsJson = items.stream()
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"itemAmount\":%s}",
                i.getId(), i.getItemAmount().toPlainString()))
            .collect(Collectors.joining(",", "[", "]"));

        return String.format(
            "{\"paymentKey\":\"%s\",\"orderId\":%d,\"merchantId\":%d,\"totalAmount\":%s," +
            "\"items\":%s,\"completedAt\":\"%s\"}",
            saved.getPaymentKey(),
            saved.getOrderId(),
            saved.getMerchantId(),
            saved.getTotalAmount().toPlainString(),
            itemsJson,
            saved.getCreatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
