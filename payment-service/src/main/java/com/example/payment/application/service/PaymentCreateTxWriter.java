package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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

    @Transactional
    public Result persist(CreatePaymentCommand command, String paymentKey, BigDecimal totalAmount) {
        Payment payment = Payment.of(
            paymentKey,
            command.merchantId(),
            command.userId(),
            command.pgType(),
            totalAmount,
            "KRW",
            command.cancelPeriodDays()
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

        return new Result(saved, savedItems);
    }
}
