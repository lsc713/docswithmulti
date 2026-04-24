package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.entity.CancelStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 결제 취소 유스케이스 구현체
 *
 * 플로우: TX1(PENDING INSERT) → Risk HTTP → TX2(PROCESSING) → PG HTTP → TX3(COMPLETED + Outbox)
 * 이력은 각 TX 커밋 후 별도 트랜잭션으로 기록 (실패해도 비즈니스 영향 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPaymentService implements CancelPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelRequestRepository cancelRequestRepository;
    private final CancelRequestHistoryRepository historyRepository;
    private final CancelEventOutboxRepository outboxRepository;
    private final CompensationRetryRepository compensationRetryRepository;
    private final RiskManagementPort riskManagementPort;
    private final PgCancelPort pgCancelPort;
    private final CancelDomainService cancelDomainService;
    private final CancelTxWriter cancelTxWriter;

    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        // Step 1. Payment / PaymentItem 조회
        Payment payment = paymentRepository.findByPaymentKey(command.paymentKey())
            .orElseThrow(() -> new PaymentNotFoundException(command.paymentKey()));

        List<PaymentItem> items =
            paymentItemRepository.findAllByPaymentIdOrderByIdAsc(payment.getId());

        // Step 2. request_hash 생성 및 멱등성 체크
        String requestHash = RequestHashGenerator.generate(
            command.paymentKey(), command.cancelPaymentItemIds());

        var existing = cancelRequestRepository.findByPaymentIdAndRequestHash(
            payment.getId(), requestHash);

        if (existing.isPresent()) {
            return handleExistingRequest(existing.get(), command, payment, items);
        }

        return executeCancel(payment, items, requestHash, command);
    }

    /** 기존 cancel_request 상태별 처리 */
    private CancelRequest handleExistingRequest(
        CancelRequest cancelRequest, CancelPaymentCommand command,
        Payment payment, List<PaymentItem> items
    ) {
        return switch (cancelRequest.getStatus()) {
            case COMPLETED, PENDING, PROCESSING -> cancelRequest;
            case FAILED -> {
                cancelRequest.raiseToPending();
                cancelRequestRepository.save(cancelRequest);
                recordHistory(cancelRequest.getId(), CancelStatus.PENDING, "FAILED 재시도");
                yield executeCancel(payment, items, cancelRequest.getRequestHash(), command);
            }
        };
    }

    /** TX1 → Risk → TX2 → PG → TX3 */
    private CancelRequest executeCancel(
        Payment payment, List<PaymentItem> items,
        String requestHash, CancelPaymentCommand command
    ) {
        // 사전 검증 (risk 호출 전 차단)
        payment.validateCancellable();
        validateTargetItemsActive(items, command.cancelPaymentItemIds());

        // TX1: CancelRequest PENDING INSERT
        BigDecimal cancelAmount = calculateCancelAmount(items, command.cancelPaymentItemIds());
        CancelRequest cancelRequest = CancelRequest.create(
            payment.getId(), requestHash, cancelAmount, command.cancelReason());
        cancelRequest = cancelTxWriter.saveTx1(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.PENDING, null);

        // Risk 호출
        try {
            LocalDate kstDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
            riskManagementPort.validateAndReserve(
                payment.getMerchantId(), cancelRequest.getId(), cancelAmount, kstDate);
        } catch (Exception e) {
            tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);
            markFailed(cancelRequest, e.getMessage());
            throw e;
        }

        // TX2: PROCESSING UPDATE
        cancelRequest = cancelTxWriter.saveTx2(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.PROCESSING, null);

        // PG사 취소 API 호출
        PgCancelResult pgResult = pgCancelPort.cancel(
            payment.getPaymentKey(), cancelAmount, command.cancelReason());

        if (pgResult.isFailed()) {
            compensateAndFail(cancelRequest, payment.getMerchantId(), cancelAmount, "PG 취소 실패");
            return cancelRequest;
        }

        if (pgResult.isPending()) {
            log.warn("PG 취소 PENDING 상태. cancelRequestId={}", cancelRequest.getId());
            return cancelRequest;
        }

        // TX3: PaymentItem + Payment + COMPLETED + Outbox
        CancelRequest savedTx3 = cancelTxWriter.saveTx3(cancelRequest, payment, command.cancelPaymentItemIds());
        recordHistory(savedTx3.getId(), CancelStatus.COMPLETED, null);
        return savedTx3;
    }

    private void tryCompensate(CancelRequest cancelRequest, long merchantId, BigDecimal amount) {
        try {
            riskManagementPort.compensate(cancelRequest.getId(), merchantId, amount);
        } catch (Exception ex) {
            log.error("보상 트랜잭션 실패. cancelRequestId={}", cancelRequest.getId(), ex);
            compensationRetryRepository.save(cancelRequest.getId(), merchantId, amount);
        }
    }

    private void compensateAndFail(
        CancelRequest cancelRequest, long merchantId, BigDecimal amount, String reason
    ) {
        tryCompensate(cancelRequest, merchantId, amount);
        markFailed(cancelRequest, reason);
    }

    private void markFailed(CancelRequest cancelRequest, String reason) {
        cancelRequest.toFailed(reason);
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.FAILED, reason);
    }

    private void recordHistory(Long cancelRequestId, CancelStatus status, String reason) {
        try {
            historyRepository.record(cancelRequestId, status, reason);
        } catch (Exception e) {
            log.warn("이력 기록 실패 (비즈니스 영향 없음). cancelRequestId={}", cancelRequestId, e);
        }
    }

    private void validateTargetItemsActive(List<PaymentItem> items, List<Long> targetIds) {
        items.stream()
            .filter(i -> targetIds.contains(i.getId()))
            .filter(i -> !i.isCancellable())
            .findFirst()
            .ifPresent(i -> {
                throw new com.example.payment.domain.exception.InvalidPaymentItemStatusException(
                    i.getId(), i.getStatus());
            });
    }

    private BigDecimal calculateCancelAmount(List<PaymentItem> items, List<Long> targetIds) {
        return items.stream()
            .filter(i -> targetIds.contains(i.getId()))
            .map(PaymentItem::getItemAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
