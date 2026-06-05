package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.domain.service.CancelItemCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * TX 경계 전담 클래스.
 *
 * TX1: CancelRequest PENDING INSERT
 * TX2: CancelRequest PROCESSING UPDATE
 * TX3: PaymentItem + Payment + CancelRequest(COMPLETED) + Kafka 직접 발행
 *      발행 실패 시 예외 throw → @Transactional 롤백 → CancelRequest PROCESSING 유지
 *      → processing-recovery 스케줄러가 재처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelTxWriter {

    private final CancelRequestRepository cancelRequestRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CancelDomainService cancelDomainService;

    @Value("${kafka.topic.payment-cancelled}")
    private String topic;

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

        // TX3 마지막: Kafka 직접 발행
        // 실패 시 예외 발생 → @Transactional 롤백 → CancelRequest PROCESSING 유지
        // → processing-recovery 스케줄러가 재처리
        String payload = buildPayload(cancelRequest, payment, freshItems, targetItemIds);
        try {
            kafkaTemplate.send(topic, String.valueOf(cancelRequest.getId()), payload)
                .get(5, TimeUnit.SECONDS);
            log.debug("[kafka] TX3 발행 완료. cancelRequestId={}", cancelRequest.getId());
        } catch (Exception e) {
            log.error("[kafka] TX3 발행 실패 → TX3 롤백. cancelRequestId={}", cancelRequest.getId(), e);
            throw new RuntimeException(
                "[kafka] TX3 Kafka 발행 실패. cancelRequestId=" + cancelRequest.getId(), e);
        }

        return cancelRequest;
    }

    private String buildPayload(
        CancelRequest cancelRequest, Payment payment,
        List<PaymentItem> freshItems, List<Long> targetItemIds
    ) {
        String itemsJson = freshItems.stream()
            .filter(i -> i.getStatus() == PaymentItemStatus.CANCELLED
                && targetItemIds.contains(i.getId()))
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s,\"skuId\":%d,\"quantity\":%d}",
                i.getId(), i.getOrderItemId(), i.getItemAmount().toPlainString(),
                i.getSkuId(), i.getQuantity()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        Instant cancelledAt = cancelRequest.getCompletedAt() != null
            ? cancelRequest.getCompletedAt() : Instant.now();

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d," +
            "\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            itemsJson,
            cancelledAt
        );
    }
}
