package com.example.payment.application.service;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatePaymentService implements CreatePaymentUseCase {

    // payment-service에는 ObjectMapper 빈이 없음(LongListConverter 관행) → 내부 인스턴스 사용(스레드 안전).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProductStockPort productStockPort;
    private final PaymentCreateTxWriter paymentCreateTxWriter;
    private final StockReleaseRetryRepository stockReleaseRetryRepository;

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

        // (3) 예약 성공 후 payment/payment_item persist(@Transactional).
        //     persist 실패(TX 롤백 완료 상태) → 예약 고아 회수: release best-effort, 실패 시 재시도 적재(RSV-03, D-P2-6).
        try {
            return paymentCreateTxWriter.persist(command, paymentKey, totalAmount);
        } catch (RuntimeException e) {
            compensateReserve(paymentKey, reserveItems);
            throw e; // 원예외 재던짐 → 결제 실패(payment 미생성). 보상은 응답을 바꾸지 않음.
        }
    }

    /** 예약 해제 보상: release best-effort, 실패 시 stock_release_retry 적재. release는 paymentKey+sku 멱등이라 재시도 안전. */
    private void compensateReserve(String paymentKey, List<ProductStockPort.Item> items) {
        try {
            productStockPort.release(paymentKey, items);
        } catch (RuntimeException re) {
            log.warn("[stock-release] best-effort release 실패 → 재시도 적재 paymentKey={}: {}",
                paymentKey, re.getMessage());
            stockReleaseRetryRepository.enqueue(paymentKey, serialize(items));
        }
    }

    private String serialize(List<ProductStockPort.Item> items) {
        try {
            return OBJECT_MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("release items 직렬화 실패", e);
        }
    }
}
