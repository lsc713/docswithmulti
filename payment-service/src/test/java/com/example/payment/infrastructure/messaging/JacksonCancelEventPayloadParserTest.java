package com.example.payment.infrastructure.messaging;

import com.example.payment.application.exception.InvalidCancelEventPayloadException;
import com.example.payment.application.model.CancelEventPayload.Item;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonCancelEventPayloadParserTest {

    private JacksonCancelEventPayloadParser parser;

    @BeforeEach
    void setUp() {
        parser = new JacksonCancelEventPayloadParser(new ObjectMapper());
    }

    @Test
    void parsesCanonicalCancelPayload() {
        var payload = parser.parse("""
            {"cancelRequestId":27,"paymentKey":"pay_1","merchantId":1,
             "cancelledItems":[{"paymentItemId":1,"orderItemId":10,
             "itemAmount":1000,"skuId":8,"quantity":2}],
             "cancelledAt":"2026-08-10T00:00:00Z"}
            """);

        assertThat(payload.cancelRequestId()).isEqualTo(27L);
        assertThat(payload.paymentKey()).isEqualTo("pay_1");
        assertThat(payload.items()).containsExactly(new Item(10L, 8L, 2));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("not-json"))
            .isInstanceOf(InvalidCancelEventPayloadException.class);
    }

    @Test
    void rejectsMissingRequiredTargetFields() {
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[
              {"paymentItemId":1,"skuId":8,"quantity":2}]}
            """);
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[
              {"paymentItemId":1,"orderItemId":10,"skuId":null,"quantity":2}]}
            """);
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[
              {"paymentItemId":1,"orderItemId":10,"skuId":8,"quantity":0}]}
            """);
    }

    @Test
    void rejectsEmptyItemsAndBlankPaymentKey() {
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[]}
            """);
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":" ","cancelledItems":[
              {"paymentItemId":1,"orderItemId":10,"skuId":8,"quantity":2}]}
            """);
    }

    @Test
    void rejectsDuplicateOrderItemOrSkuTargets() {
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[
              {"paymentItemId":1,"orderItemId":10,"skuId":8,"quantity":2},
              {"paymentItemId":2,"orderItemId":10,"skuId":9,"quantity":1}]}
            """);
        assertInvalid("""
            {"cancelRequestId":27,"paymentKey":"pay_1","cancelledItems":[
              {"paymentItemId":1,"orderItemId":10,"skuId":8,"quantity":2},
              {"paymentItemId":2,"orderItemId":11,"skuId":8,"quantity":1}]}
            """);
    }

    private void assertInvalid(String json) {
        assertThatThrownBy(() -> parser.parse(json))
            .isInstanceOf(InvalidCancelEventPayloadException.class);
    }
}
