package com.example.product.infrastructure.messaging;

import com.example.product.application.usecase.StockRestoreUseCase;
import com.example.product.application.usecase.StockRestoreUseCase.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledStockConsumer {

    private final StockRestoreUseCase stockRestoreUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${kafka.topic.payment-cancelled}", groupId = "product-service")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            long cancelRequestId = root.get("cancelRequestId").asLong();
            List<CancelledItem> items = new ArrayList<>();
            for (JsonNode item : root.get("cancelledItems")) {
                items.add(new CancelledItem(item.get("skuId").asLong(), item.get("quantity").asInt()));
            }
            stockRestoreUseCase.execute(new Command(cancelRequestId, items));
        } catch (Exception e) {
            log.error("payment.cancelled 메시지 처리 실패. offset={}", record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
