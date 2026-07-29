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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 결제 취소 유스케이스 구현체
 *
 * 플로우: TX1(PENDING INSERT) → Risk HTTP → TX2(PROCESSING) → PG HTTP → TX3(COMPLETED + ApplicationEvent)
 * 이력은 각 TX 커밋 후 별도 트랜잭션으로 기록 (실패해도 비즈니스 영향 없음)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPaymentService implements CancelPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelRequestRepository cancelRequestRepository;
    private final CancelHistoryRecorder cancelHistoryRecorder;
    private final CompensationRetryRepository compensationRetryRepository;
    private final RiskManagementPort riskManagementPort;
    private final PgCancelPort pgCancelPort;
    private final CancelDomainService cancelDomainService;
    private final CancelTxWriter cancelTxWriter;

    @Override
    public CancelRequest cancel(CancelPaymentCommand command) {
        try {
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
        } finally {
            cancelHistoryRecorder.flush();
        }
    }

    /** 기존 cancel_request 상태별 처리 */
    private CancelRequest handleExistingRequest(
        CancelRequest cancelRequest, CancelPaymentCommand command,
        Payment payment, List<PaymentItem> items
    ) {
        return switch (cancelRequest.getStatus()) {
            case COMPLETED, PENDING, PROCESSING -> cancelRequest;
            case FAILED -> {
                // 사전 검증 (risk 호출 전 차단) — 신규 경로(executeCancel)와 동일하게 재적용
                payment.validateCancellable();
                validateTargetItemsActive(items, command.cancelPaymentItemIds());

                cancelRequest.raiseToPending();
                cancelRequest = cancelRequestRepository.save(cancelRequest);
                recordHistory(cancelRequest.getId(), CancelStatus.PENDING, "FAILED 재시도");
                // CR-01: 새 INSERT 없이 방금 UPDATE된 기존 행(id 보유)을 그대로 이어받아 risk부터 재개.
                // (executeCancel을 다시 타면 동일 (payment_id, request_hash)로 재INSERT를 시도해
                //  자기 자신과 UK 충돌 → risk/PG 재호출 없이 조용히 무시되는 버그가 있었다.)
                yield proceedFromRisk(payment, cancelRequest, command);
            }
        };
    }

    /** 신규: TX1(PENDING INSERT) 이후 risk부터 진행 */
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
            payment.getId(), requestHash, cancelAmount, command.cancelReason(),
            command.cancelPaymentItemIds(), null);
        try {
            cancelRequest = cancelTxWriter.saveTx1(cancelRequest);
        } catch (DataIntegrityViolationException e) {
            // 레이스 패자: 다른 파드가 동일 (payment_id, request_hash)로 이미 INSERT함(UK 위반).
            // 새 응답 형태를 만들지 않고 기존 handleExistingRequest 상태 스위치로 위임(D-03).
            CancelRequest winner = cancelRequestRepository
                .findByPaymentIdAndRequestHash(payment.getId(), requestHash)
                .orElseThrow(() -> e); // 이론상 불가(방금 위반났으므로 존재) — 방어적 재throw
            return handleExistingRequest(winner, command, payment, items);
        }
        recordHistory(cancelRequest.getId(), CancelStatus.PENDING, null);

        return proceedFromRisk(payment, cancelRequest, command);
    }

    /**
     * Risk → TX2 → PG → TX3.
     * 신규 INSERT 직후(executeCancel) 또는 FAILED→PENDING 재시도 직후(handleExistingRequest)의
     * 공용 진입점 — 어느 경로든 새 INSERT 없이 이미 존재하는 cancelRequest(id 보유)를 이어받는다.
     */
    private CancelRequest proceedFromRisk(
        Payment payment, CancelRequest cancelRequest, CancelPaymentCommand command
    ) {
        BigDecimal cancelAmount = cancelRequest.getCancelAmount();

        // Risk 호출
        try {
            LocalDate kstDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
            riskManagementPort.validateAndReserve(
                payment.getMerchantId(), cancelRequest.getId(), cancelAmount, kstDate);
        } catch (Exception e) {
            // CR-02: risk의 "명확한 에러(한도초과 등, 차감 없음)"와 "타임아웃/유실(차감 여부 불확실)"을
            // isCharged()로 구분 — 실제 차감된 경우에만 compensate 호출(cancel-design.md 97-113행).
            // 명확히 미차감(charged=false)이면 compensate 없이 바로 FAILED만 기록.
            // isCharged() 확인 자체가 실패하면(risk 이중 장애) 차감 여부를 알 수 없으므로
            // 안전한 기본값(compensate 진행)으로 폴백 — 미보상 누락보다 과잉 보상 시도가 안전.
            boolean shouldCompensate = true;
            try {
                shouldCompensate = riskManagementPort.isCharged(cancelRequest.getId());
            } catch (Exception checkEx) {
                log.warn("risk isCharged 확인 실패 — 안전하게 compensate 진행. cancelRequestId={}: {}",
                    cancelRequest.getId(), checkEx.getMessage());
            }
            if (shouldCompensate) {
                tryCompensate(cancelRequest, payment.getMerchantId(), cancelAmount);
            }
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

        // TX3: PaymentItem + Payment + COMPLETED + ApplicationEvent
        // D-01: PG(Toss) transactionKey를 saveTx3 이전에 세팅 — saveTx3는 CancelRequest를 재조회하지
        // 않고 전달받은 객체를 그대로 toCompleted() 후 저장하므로 여기서 세팅하면 그대로 반영된다.
        if (pgResult.pgTransactionId() != null) {
            cancelRequest.assignPgTransactionKey(pgResult.pgTransactionId());
        }
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
        cancelRequest.toFailed();
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.FAILED, reason);
    }

    private void recordHistory(Long cancelRequestId, CancelStatus status, String reason) {
        cancelHistoryRecorder.add(cancelRequestId, status, reason);
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
