package com.example.payment.infrastructure.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossPaymentHttpClientTest {
    @Test
    void confirm_sends_basic_auth_idempotency_key_and_integer_amount() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic dGVzdF9zazo="))
            .andExpect(header("Idempotency-Key", "request-1"))
            .andExpect(jsonPath("$.paymentKey").value("toss_key"))
            .andExpect(jsonPath("$.orderId").value("request-1"))
            .andExpect(jsonPath("$.amount").value(20000))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        new TossPaymentHttpClient(
            restTemplate, "https://api.tosspayments.com", "test_sk")
            .confirm("toss_key", "request-1", BigDecimal.valueOf(20_000));

        server.verify();
    }
}
