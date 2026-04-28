package com.example.payment.application.service;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * TX 경계 전담 클래스.
 *
 * TX1: CancelRequest PENDING INSERT
 * TX2: CancelRequest PROCESSING UPDATE
 * TX3: PaymentItem + Payment + CancelRequest(COMPLETED) + ApplicationEvent 발행
 *      → AFTER_COMMIT 리스너(CancelEventPublisher)가 Kafka 발행
 */
@Service
@RequiredArgsConstructor
public class CancelTxWriter {

    private final CancelRequestRepository cancelRequestRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final CancelDomainService cancelDomainService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx1(CancelRequest cancelRequest) {
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx2(CancelRequest cancelRequest) {
        cancelRequest.toProcessing();
        return cancelRequestRepository.save(cancelRequest);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CancelRequest saveTx3(
        CancelRequest cancelRequest, Payment payment, List<Long> targetItemIds
    ) {
        List<PaymentItem> freshItems =
            paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId());

        List<CancelItemCommand> commands = targetItemIds.stream()
            .map(CancelItemCommand::of)
            .toList();

        cancelDomainService.apply(payment, commands, freshItems);
        paymentItemRepository.saveAll(freshItems);
        paymentRepository.save(payment);

        cancelRequest.toCompleted();
        cancelRequest = cancelRequestRepository.save(cancelRequest);

        // Outbox 대신 ApplicationEvent 발행 → AFTER_COMMIT 리스너가 Kafka 발행
        List<CancelCompletedEvent.CancelledItemData> eventItems = freshItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED
                && targetItemIds.contains(i.getId()))
            .map(i -> new CancelCompletedEvent.CancelledItemData(
                i.getId(), i.getOrderItemId(), i.getItemAmount()))
            .toList();

        applicationEventPublisher.publishEvent(new CancelCompletedEvent(
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            cancelRequest.getCompletedAt() != null ? cancelRequest.getCompletedAt() : Instant.now(),
            eventItems
        ));

        return cancelRequest;
    }
}
