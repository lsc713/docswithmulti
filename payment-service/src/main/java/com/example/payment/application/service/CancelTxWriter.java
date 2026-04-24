package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * TX 경계 전담 클래스.
 *
 * 별도 Spring Bean으로 분리하여 같은 클래스 내부 호출(self-invocation) 문제를 해결한다.
 * 각 TX 메서드는 호출 시점에 새 트랜잭션을 시작한다.
 *
 * cancel-design.md TX 설계:
 *   TX1: CancelRequest PENDING INSERT
 *   TX2: CancelRequest PROCESSING UPDATE
 *   TX3: PaymentItem + Payment + CancelRequest(COMPLETED) + Outbox (원자적)
 */
@Service
@RequiredArgsConstructor
public class CancelTxWriter {

    private final CancelRequestRepository cancelRequestRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final CancelEventOutboxRepository outboxRepository;
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

        outboxRepository.insertIfAbsent(cancelRequest, payment,
            freshItems.stream()
                .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED
                    && targetItemIds.contains(i.getId()))
                .toList());

        return cancelRequest;
    }
}
