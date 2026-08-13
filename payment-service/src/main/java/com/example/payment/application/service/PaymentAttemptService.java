package com.example.payment.application.service;

import com.example.payment.application.exception.PaymentAttemptException;
import com.example.payment.application.exception.PaymentApprovalRejectedException;
import com.example.payment.application.interfaces.OrderVerifyPort;
import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import com.example.payment.application.interfaces.TossPaymentPort;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.PaymentAttemptUseCase;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAttemptService implements PaymentAttemptUseCase {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OrderVerifyPort orderVerifyPort;
    private final ProductStockPort productStockPort;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptTxWriter txWriter;
    private final TossPaymentPort tossPaymentPort;
    private final StockReleaseRetryRepository stockReleaseRetryRepository;

    @Value("${toss.client-key}") private String clientKey;
    @Value("${payment.customer-key-salt}") private String customerKeySalt;
    @Value("${payment.attempt.recovery.unknown-after-seconds:30}") private long unknownAfterSeconds;
    @Value("${payment.attempt.recovery.expires-after-seconds:600}") private long expiresAfterSeconds;
    @Value("${payment.attempt.recovery.batch-size:100}") private int recoveryBatchSize;

    @Override
    public Prepared prepare(CreatePaymentCommand command) {
        if (!"NORMAL".equals(command.pgType())) {
            throw new PaymentAttemptException(ErrorCode.INVALID_REQUEST, "NORMAL 결제만 지원합니다.");
        }
        long orderId = orderVerifyPort.verify(command.userId(), command.items().stream()
            .map(CreatePaymentCommand.Item::orderItemId).toList());
        String requestId = UUID.randomUUID().toString();
        List<ProductStockPort.Item> reserveItems = command.items().stream()
            .map(i -> new ProductStockPort.Item(i.productId(), i.skuId(), i.quantity()))
            .toList();
        List<ProductStockPort.Item> releaseItems = reserveItems.stream()
            .map(i -> new ProductStockPort.Item(i.skuId(), i.qty())).toList();
        List<ProductStockPort.ReservedItem> reserved =
            productStockPort.reserve(requestId, reserveItems);
        try {
            CreatePaymentCommand priced = priced(command, reserved);
            BigDecimal amount = priced.items().stream().map(CreatePaymentCommand.Item::itemAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            txWriter.prepare(requestId, priced, amount, orderId);
            String orderName = priced.items().size() == 1
                ? priced.items().get(0).itemName()
                : priced.items().get(0).itemName() + " 외 " + (priced.items().size() - 1) + "건";
            return new Prepared(requestId, amount, orderName, customerKey(command.userId()), clientKey);
        } catch (DataIntegrityViolationException e) {
            compensate(requestId, releaseItems);
            throw new PaymentAttemptException(ErrorCode.PAYMENT_ATTEMPT_CONFLICT);
        } catch (RuntimeException e) {
            compensate(requestId, releaseItems);
            throw e;
        }
    }

    @Override
    public Status confirm(
        String requestId, long userId, String paymentKey, String orderId, BigDecimal amount
    ) {
        Payment current = owned(requestId, userId);
        if (current.getStatus() == PaymentStatus.COMPLETED) return status(current);
        if (current.getPaymentKey() != null && !current.getPaymentKey().equals(paymentKey)) {
            throw new PaymentAttemptException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        boolean mismatch = !requestId.equals(orderId)
            || amount == null
            || amount.stripTrailingZeros().scale() > 0
            || current.getTotalAmount().compareTo(amount) != 0
            || !"NORMAL".equals(current.getPgType());
        if (mismatch) {
            log.warn("PAYMENT_CONFIRM_MISMATCH paymentRequestId={}", requestId);
            throw new PaymentAttemptException(ErrorCode.PAYMENT_CONFIRM_MISMATCH);
        }

        PaymentAttemptTxWriter.AttachResult attached = txWriter.attach(requestId, userId, paymentKey);
        if (!attached.shouldConfirm()) return status(attached.payment());
        try {
            tossPaymentPort.confirm(paymentKey, requestId, amount);
        } catch (PaymentApprovalRejectedException e) {
            release(txWriter.failConfirmed(requestId));
            throw new PaymentAttemptException(ErrorCode.PAYMENT_APPROVAL_REJECTED);
        }
        return status(txWriter.complete(requestId));
    }

    @Override
    public Status get(String requestId, long userId) {
        return status(owned(requestId, userId));
    }

    @Override
    public Status fail(String requestId, long userId) {
        PaymentAttemptTxWriter.FailureResult result = txWriter.failUnconfirmed(requestId, userId);
        release(result);
        return status(result.payment());
    }

    public void recoverPending() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (Payment payment : paymentRepository.findPendingRecoveryCandidates(
            now.minusSeconds(expiresAfterSeconds), now.minusSeconds(unknownAfterSeconds),
            recoveryBatchSize)) {
            try {
                if (payment.getPaymentKey() == null) {
                    release(txWriter.expire(payment.getPaymentRequestId()));
                    continue;
                }
                switch (tossPaymentPort.getStatus(payment.getPaymentKey())) {
                    case DONE -> txWriter.complete(payment.getPaymentRequestId());
                    case ABORTED, EXPIRED -> release(
                        txWriter.failConfirmed(payment.getPaymentRequestId()));
                    case PENDING -> { }
                }
            } catch (RuntimeException e) {
                log.warn("결제 시도 복구 보류. paymentRequestId={}",
                    payment.getPaymentRequestId(), e);
            }
        }
    }

    private void release(PaymentAttemptTxWriter.FailureResult result) {
        if (result.shouldRelease()) {
            compensate(result.payment().getPaymentRequestId(), result.items());
        }
    }

    private Payment owned(String requestId, long userId) {
        Payment payment = paymentRepository.findByPaymentRequestId(requestId)
            .orElseThrow(() -> new PaymentAttemptException(ErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        if (payment.getUserId() != userId) {
            throw new PaymentAttemptException(ErrorCode.FORBIDDEN_PAYMENT);
        }
        return payment;
    }

    private CreatePaymentCommand priced(
        CreatePaymentCommand command, List<ProductStockPort.ReservedItem> reserved
    ) {
        if (reserved == null || reserved.size() != command.items().size()) {
            throw new IllegalStateException("재고 예약 가격 응답의 항목 수가 다릅니다.");
        }
        var items = new java.util.ArrayList<CreatePaymentCommand.Item>(reserved.size());
        for (int i = 0; i < reserved.size(); i++) {
            var requested = command.items().get(i);
            var server = reserved.get(i);
            if (server.skuId() != requested.skuId() || server.productId() != requested.productId()
                || server.quantity() != requested.quantity() || server.unitPrice() == null
                || server.unitPrice().signum() <= 0 || server.unitPrice().stripTrailingZeros().scale() > 0) {
                throw new IllegalStateException("재고 예약 가격 응답이 요청과 다릅니다.");
            }
            items.add(new CreatePaymentCommand.Item(
                requested.orderItemId(), requested.productId(), requested.itemName(),
                server.unitPrice().multiply(BigDecimal.valueOf(server.quantity())),
                requested.skuId(), requested.quantity()));
        }
        return new CreatePaymentCommand(command.merchantId(), command.userId(), command.pgType(),
            command.cancelPeriodDays(), items);
    }

    private String customerKey(long userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((customerKeySalt + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return "customer_" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void compensate(String requestId, List<ProductStockPort.Item> items) {
        try {
            productStockPort.release(requestId, items);
        } catch (RuntimeException e) {
            try {
                stockReleaseRetryRepository.enqueue(requestId, OBJECT_MAPPER.writeValueAsString(items));
            } catch (JsonProcessingException serializationError) {
                e.addSuppressed(serializationError);
                log.error("결제 시도 재고 보상 적재 실패. paymentRequestId={}", requestId, e);
            }
        }
    }

    private Status status(Payment payment) {
        return new Status(payment.getPaymentRequestId(), payment.getPaymentKey(),
            payment.getTotalAmount(), payment.getStatus());
    }
}
