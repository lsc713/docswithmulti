package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.model.CancelRestoreLegStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
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

class OrderCancelStatusHttpClientTest {

    @Test
    void constructorRequiresInspectionRestTemplateQualifier() throws Exception {
        var parameter = OrderCancelStatusHttpClient.class.getConstructors()[0].getParameters()[0];

        assertThat(parameter.getAnnotation(Qualifier.class).value())
            .isEqualTo("cancelOutboxInspectionRestTemplate");
    }

    private RestTemplate restTemplate;
    private CircuitBreaker circuitBreaker;
    private OrderCancelStatusHttpClient client;

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
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("order-inspection-test");
        client = new OrderCancelStatusHttpClient(
            restTemplate, "http://order-service", circuitBreaker);
    }

    @Test
    void postsExactInspectionContractAndMapsEvidence() {
        var downstream = new OrderCancelStatusHttpClient.InspectResponse(
            "INCONSISTENT",
            List.of(new OrderCancelStatusHttpClient.EvidenceResponse(11L, "ACTIVE")));
        when(restTemplate.postForEntity(
            eq("http://order-service/internal/cancel-restores/27:inspect"),
            any(), eq(OrderCancelStatusHttpClient.InspectResponse.class)))
            .thenReturn(ResponseEntity.ok(downstream));

        var result = client.inspect(new OrderCancelStatusPort.Command(
            "27", List.of(10L, 11L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new com.example.payment.application.model.CancelRestoreLegSnapshot.Evidence(
                11L, "ACTIVE", null, null));
        var body = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForEntity(
            eq("http://order-service/internal/cancel-restores/27:inspect"),
            body.capture(), eq(OrderCancelStatusHttpClient.InspectResponse.class));
        assertThat(body.getValue()).isEqualTo(
            new OrderCancelStatusHttpClient.InspectRequest(List.of(10L, 11L)));
    }

    @Test
    void dependencyFailureReturnsUnknown() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(OrderCancelStatusHttpClient.InspectResponse.class)))
            .thenThrow(new RuntimeException("timeout"));

        var result = client.inspect(new OrderCancelStatusPort.Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void openCircuitReturnsUnknownWithoutThirdHttpCall() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(OrderCancelStatusHttpClient.InspectResponse.class)))
            .thenThrow(new RuntimeException("connection refused"));
        var command = new OrderCancelStatusPort.Command("27", List.of(10L));

        client.inspect(command);
        client.inspect(command);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(client.inspect(command).status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        verify(restTemplate, times(2)).postForEntity(
            any(String.class), any(), eq(OrderCancelStatusHttpClient.InspectResponse.class));
    }
}
