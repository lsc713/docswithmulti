package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreatePaymentService implements CreatePaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;

    @Override
    @Transactional
    public Result create(CreatePaymentCommand command) {
        BigDecimal totalAmount = command.items().stream()
            .map(CreatePaymentCommand.Item::itemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        String paymentKey = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

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
                item.itemAmount()
            ))
            .toList();
        List<PaymentItem> savedItems = paymentItemRepository.saveAll(items);

        return new Result(saved, savedItems);
    }
}
