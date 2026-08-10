package com.example.payment.infrastructure.messaging;

import com.example.payment.application.exception.InvalidCancelEventPayloadException;
import com.example.payment.application.interfaces.CancelEventPayloadParser;
import com.example.payment.application.model.CancelEventPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class JacksonCancelEventPayloadParser implements CancelEventPayloadParser {

    private final ObjectMapper objectMapper;

    public JacksonCancelEventPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CancelEventPayload parse(String payload) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidCancelEventPayloadException("cancel event payload is not valid JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw invalid("payload root must be an object");
        }
        long cancelRequestId = positiveLong(root.get("cancelRequestId"), "cancelRequestId");
        String paymentKey = requiredText(root.get("paymentKey"), "paymentKey");
        JsonNode cancelledItems = root.get("cancelledItems");
        if (cancelledItems == null || !cancelledItems.isArray() || cancelledItems.isEmpty()) {
            throw invalid("cancelledItems must be a non-empty array");
        }

        Set<Long> orderItemIds = new HashSet<>();
        Set<Long> skuIds = new HashSet<>();
        List<CancelEventPayload.Item> items = new ArrayList<>();
        for (JsonNode item : cancelledItems) {
            if (!item.isObject()) {
                throw invalid("cancelledItems entry must be an object");
            }
            long orderItemId = positiveLong(item.get("orderItemId"), "orderItemId");
            long skuId = positiveLong(item.get("skuId"), "skuId");
            int quantity = positiveInt(item.get("quantity"), "quantity");
            if (!orderItemIds.add(orderItemId) || !skuIds.add(skuId)) {
                throw invalid("cancelledItems contains duplicate target ids");
            }
            items.add(new CancelEventPayload.Item(orderItemId, skuId, quantity));
        }
        return new CancelEventPayload(cancelRequestId, paymentKey, items);
    }

    private long positiveLong(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        long value = node.longValue();
        if (value <= 0) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    private int positiveInt(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        int value = node.intValue();
        if (value <= 0) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return node.textValue();
    }

    private InvalidCancelEventPayloadException invalid(String message) {
        return new InvalidCancelEventPayloadException(message);
    }
}
