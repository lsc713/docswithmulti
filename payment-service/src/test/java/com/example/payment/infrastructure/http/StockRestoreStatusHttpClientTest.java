package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot.Evidence;
import com.example.payment.application.model.CancelRestoreLegStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockRestoreStatusHttpClientTest {

    private RestTemplate restTemplate;
    private CircuitBreaker circuitBreaker;
    private StockRestoreStatusHttpClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        var config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build();
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("stock-inspection-test");
        client = new StockRestoreStatusHttpClient(
            restTemplate, "http://product-service", circuitBreaker);
    }

    @Test
    void postsExactInspectionContractAndMapsQuantityEvidence() {
        var downstream = new StockRestoreStatusHttpClient.InspectResponse(
            "INCONSISTENT",
            List.of(new StockRestoreStatusHttpClient.EvidenceResponse(
                8L, "RESERVED", 1, 2)));
        when(restTemplate.postForEntity(
            eq("http://product-service/internal/cancel-restores/27:inspect"),
            any(), eq(StockRestoreStatusHttpClient.InspectResponse.class)))
            .thenReturn(ResponseEntity.ok(downstream));
        var command = new StockRestoreStatusPort.Command(
            "27", "pay_1", List.of(new StockRestoreStatusPort.Item(8L, 2)));

        var result = client.inspect(command);

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new Evidence(8L, "RESERVED", 1, 2));
        var body = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(
            eq("http://product-service/internal/cancel-restores/27:inspect"),
            body.capture(), eq(StockRestoreStatusHttpClient.InspectResponse.class));
        assertThat(body.getValue()).isEqualTo(new StockRestoreStatusHttpClient.InspectRequest(
            "pay_1", List.of(new StockRestoreStatusHttpClient.ItemRequest(8L, 2))));
    }

    @Test
    void dependencyFailureReturnsUnknown() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(StockRestoreStatusHttpClient.InspectResponse.class)))
            .thenThrow(new RuntimeException("timeout"));

        var result = client.inspect(command());

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void openCircuitReturnsUnknownWithoutThirdHttpCall() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(StockRestoreStatusHttpClient.InspectResponse.class)))
            .thenThrow(new RuntimeException("connection refused"));

        client.inspect(command());
        client.inspect(command());
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(client.inspect(command()).status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        verify(restTemplate, times(2)).postForEntity(
            any(String.class), any(), eq(StockRestoreStatusHttpClient.InspectResponse.class));
    }

    private static StockRestoreStatusPort.Command command() {
        return new StockRestoreStatusPort.Command(
            "27", "pay_1", List.of(new StockRestoreStatusPort.Item(8L, 2)));
    }
}
