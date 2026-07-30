package com.example.payment.application.service;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 결제 생성 비-TX 오케스트레이터 (D-P2-1, 취소 플로우 오케스트레이션과 동형).
 *
 * 순서: paymentKey 생성 → product.reserve(HTTP, TX 밖) → PaymentCreateTxWriter.persist(@Transactional).
 * reserve 실패(재고 부족 409 / product 장애·CB OPEN)는 catch하지 않고 전파 →
 * GlobalExceptionHandler가 409/503으로 거부(fail-closed, D-P2-2). persist 미도달 → payment 행 미생성.
 */
@Service
@RequiredArgsConstructor
public class CreatePaymentService implements CreatePaymentUseCase {

    private final ProductStockPort productStockPort;
    private final PaymentCreateTxWriter paymentCreateTxWriter;

    @Override
    public Result create(CreatePaymentCommand command) {
        BigDecimal totalAmount = command.items().stream()
            .map(CreatePaymentCommand.Item::itemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // (1) paymentKey는 reserve 호출 전에 확정 → reserve의 멱등 키로 전달(커밋 이전 확정, TX 밖).
        String paymentKey = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        // (2) product 재고 동기 예약(TX 밖, HTTP). 실패는 전파 → 결제 거부(fail-closed).
        List<ProductStockPort.Item> reserveItems = command.items().stream()
            .map(item -> new ProductStockPort.Item(item.skuId(), item.quantity()))
            .toList();
        productStockPort.reserve(paymentKey, reserveItems);

        // (3) 예약 성공 후에만 payment/payment_item persist(@Transactional).
        return paymentCreateTxWriter.persist(command, paymentKey, totalAmount);
    }
}
