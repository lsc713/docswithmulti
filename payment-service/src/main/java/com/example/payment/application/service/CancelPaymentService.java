package com.example.payment.application.service;

import com.example.payment.application.dto.CancelPaymentRequest;
import com.example.payment.application.dto.CancelPaymentResponse;
import com.example.payment.application.exception.IdempotentDuplicationException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.CancelEventOutboxManager;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.IdempotencyKeyManager;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.interfaces.RiskManagementService;
import com.example.payment.application.usecase.CancelPaymentUseCase;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.CancellerType;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 취소 유스케이스 구현체
 *
 * 책임:
 * 1. Idempotency-Key 중복 체크
 * 2. Payment & PaymentItem 조회
 * 3. CancelDomainService로 비즈니스 연산 위임
 * 4. RiskManagementService 호출 (동기 HTTP)
 * 5. CancelRequest 상태 전이 (PENDING → PROCESSING → COMPLETED)
 * 6. PaymentItem DB 업데이트
 * 7. CancelEventOutbox 생성
 * 8. DB 저장
 *
 * 트랜잭션 경계: 프레젠테이션 계층에서 @Transactional로 관리
 *
 * 의존성: application/interfaces에만 의존
 */
public class CancelPaymentService implements CancelPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final CancelRequestRepository cancelRequestRepository;
    private final IdempotencyKeyManager idempotencyKeyManager;
    private final RiskManagementService riskManagementService;
    private final CancelEventOutboxManager cancelEventOutboxManager;
    private final CancelDomainService cancelDomainService;

    public CancelPaymentService(
        PaymentRepository paymentRepository,
        PaymentItemRepository paymentItemRepository,
        CancelRequestRepository cancelRequestRepository,
        IdempotencyKeyManager idempotencyKeyManager,
        RiskManagementService riskManagementService,
        CancelEventOutboxManager cancelEventOutboxManager,
        CancelDomainService cancelDomainService
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentItemRepository = paymentItemRepository;
        this.cancelRequestRepository = cancelRequestRepository;
        this.idempotencyKeyManager = idempotencyKeyManager;
        this.riskManagementService = riskManagementService;
        this.cancelEventOutboxManager = cancelEventOutboxManager;
        this.cancelDomainService = cancelDomainService;
    }

    @Override
    public CancelPaymentResponse cancel(
        String paymentKey,
        Long userId,
        String idempotencyKey,
        CancelPaymentRequest request
    ) {
        // 1단계: 멱등성 체크 (기존 요청 있으면 응답 재사용)
        var existingCancelRequestId = idempotencyKeyManager.getCancelRequestId(idempotencyKey);
        if (existingCancelRequestId.isPresent()) {
            CancelRequest existing = cancelRequestRepository.findById(existingCancelRequestId.get())
                .orElseThrow(() -> new RuntimeException("멱등키는 있는데 취소 요청이 없음"));
            throw new IdempotentDuplicationException(
                String.valueOf(existing.getId()),
                existing.getStatus()
            );
        }

        // 2단계: Payment 조회
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
            .orElseThrow(() -> new PaymentNotFoundException(paymentKey));

        // 3단계: PaymentItem 전체 조회
        List<PaymentItem> allPaymentItems = paymentItemRepository.findAllByPaymentId(payment.getId());

        // 4단계: 취소 항목을 도메인 서비스에 전달할 커맨드로 변환
        List<CancelItemCommand> cancelItemCommands = request.cancelItems().stream()
            .map(item -> CancelItemCommand.of(item.paymentItemId(), item.cancelAmount()))
            .collect(Collectors.toList());

        // 5단계: 도메인 서비스로 비즈니스 검증 및 처리
        PaymentStatus newPaymentStatus = cancelDomainService.apply(
            payment,
            cancelItemCommands,
            allPaymentItems
        );

        // 6단계: CancelRequest 생성 및 상태 전이
        CancelRequest cancelRequest = CancelRequest.create(
            payment.getId(),
            idempotencyKey,
            request.cancelAmount(),
            request.cancelReason(),
            CancellerType.USER,
            userId
        );

        // 7단계: 멱등키 기록 (실패하면 IdempotentDuplicationException)
        // (DB UK 제약으로 동시성 보호)
        CancelRequest savedCancelRequest = cancelRequestRepository.save(cancelRequest);
        try {
            idempotencyKeyManager.recordIdempotencyKey(idempotencyKey, savedCancelRequest.getId());
        } catch (IdempotentDuplicationException e) {
            // 동시성으로 인해 다른 스레드가 먼저 기록한 경우
            throw e;
        }

        // 8단계: 한도 검증 및 선차감 (동기 HTTP)
        riskManagementService.validateAndReserveLimit(
            payment.getMerchantId(),
            payment.getId(),
            request.cancelAmount()
        );

        // 9단계: CancelRequest 상태 전이 (PENDING → PROCESSING → COMPLETED)
        cancelRequest.toProcessing();

        // 10단계: PaymentItem 상태 업데이트 (도메인 서비스에서 이미 상태 변경함)
        paymentItemRepository.saveAll(allPaymentItems);

        // 11단계: Payment 상태 업데이트
        paymentRepository.save(payment);

        // 12단계: CancelRequest 완료
        cancelRequest.toCompleted();
        CancelRequest completedCancelRequest = cancelRequestRepository.save(cancelRequest);

        // 13단계: Kafka Outbox에 취소 이벤트 기록
        List<Long> cancelledItemIds = allPaymentItems.stream()
            .map(PaymentItem::getOrderItemId)
            .collect(Collectors.toList());

        cancelEventOutboxManager.recordCancelEvent(
            completedCancelRequest.getId(),
            paymentKey,
            request.cancelAmount(),
            cancelledItemIds
        );

        // 14단계: 응답 생성
        return buildResponse(completedCancelRequest, payment, allPaymentItems, request.cancelReason());
    }

    /**
     * CancelPaymentResponse 생성
     */
    private CancelPaymentResponse buildResponse(
        CancelRequest cancelRequest,
        Payment payment,
        List<PaymentItem> paymentItems,
        String cancelReason
    ) {
        List<CancelPaymentResponse.CancelledItem> cancelledItems = paymentItems.stream()
            .map(item -> new CancelPaymentResponse.CancelledItem(
                item.getOrderItemId(),
                item.getCancelledAmount(),
                item.getStatus().toString()
            ))
            .collect(Collectors.toList());

        return new CancelPaymentResponse(
            String.valueOf(cancelRequest.getId()),
            payment.getPaymentKey(),
            cancelRequest.getCancelAmount(),
            payment.getCurrency(),
            cancelRequest.getStatus().toString(),
            cancelRequest.getCancellerType().toString(),
            cancelRequest.getCancelReason(),
            cancelledItems,
            cancelRequest.getCompletedAt()
        );
    }
}
