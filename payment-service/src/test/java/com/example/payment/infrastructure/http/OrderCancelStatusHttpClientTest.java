package com.example.payment.infrastructure.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.model.CancelRestoreLegStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

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
        logger = (Logger) LoggerFactory.getLogger(OrderCancelStatusHttpClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void postsExactInspectionContractAndMapsEvidence() {
        var downstream = new OrderCancelStatusHttpClient.InspectResponse(
            "INCONSISTENT",
            List.of(new OrderCancelStatusHttpClient.EvidenceResponse(11L, "ACTIVE")));
        when(restTemplate.postForEntity(
            eq("http://order-service/internal/cancel-restores/{cancelRequestId}:inspect"),
            any(), eq(OrderCancelStatusHttpClient.InspectResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(downstream));

        var result = client.inspect(new OrderCancelStatusPort.Command(
            "27", List.of(10L, 11L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.INCONSISTENT);
        assertThat(result.evidence()).containsExactly(
            new com.example.payment.application.model.CancelRestoreLegSnapshot.Evidence(
                11L, "ACTIVE", null, null));
        var body = ArgumentCaptor.forClass(Object.class);
        var variables = ArgumentCaptor.forClass(Object[].class);
        verify(restTemplate).postForEntity(
            eq("http://order-service/internal/cancel-restores/{cancelRequestId}:inspect"),
            body.capture(), eq(OrderCancelStatusHttpClient.InspectResponse.class),
            variables.capture());
        assertThat(body.getValue()).isEqualTo(
            new OrderCancelStatusHttpClient.InspectRequest(List.of(10L, 11L)));
        assertThat(variables.getValue()).containsExactly("27");
    }

    @Test
    void dependencyFailureReturnsUnknown() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(OrderCancelStatusHttpClient.InspectResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException("timeout"));

        var result = client.inspect(new OrderCancelStatusPort.Command("27", List.of(10L)));

        assertThat(result.status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void dependencyFailureWarnUsesOnlyBoundedFieldsWithoutThrowableData() {
        String cancelRequestId = "927460381";
        String expandedUrl = "http://order-service/internal/cancel-restores/"
            + cancelRequestId + ":inspect";
        String secretMessage = "Bearer secret-token leaked by " + expandedUrl;
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(OrderCancelStatusHttpClient.InspectResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException(secretMessage));

        assertThat(client.inspect(new OrderCancelStatusPort.Command(
            cancelRequestId, List.of(10L))).status())
            .isEqualTo(CancelRestoreLegStatus.UNKNOWN);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .isEqualTo("Cancel restore inspection unavailable");
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(fields(event)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "event", "cancel_restore_inspection_unavailable",
                "leg", "order",
                "exceptionClass", "RuntimeException"));
            assertThat(event.getFormattedMessage() + fields(event))
                .doesNotContain(secretMessage)
                .doesNotContain(cancelRequestId)
                .doesNotContain(expandedUrl);
        });
    }

    @Test
    void observedHttpMetricUsesTemplatedUriWithoutCancelRequestId() {
        String cancelRequestId = "927460381";
        var meterRegistry = new SimpleMeterRegistry();
        var observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
            new DefaultMeterObservationHandler(meterRegistry));
        var observedRestTemplate = new RestTemplate();
        observedRestTemplate.setObservationRegistry(observationRegistry);
        var server = MockRestServiceServer.bindTo(observedRestTemplate).build();
        server.expect(requestTo("http://order-service/internal/cancel-restores/"
                + cancelRequestId + ":inspect"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"APPLIED\",\"evidence\":[]}",
                MediaType.APPLICATION_JSON));
        var observedClient = new OrderCancelStatusHttpClient(
            observedRestTemplate,
            "http://order-service",
            CircuitBreaker.ofDefaults("order-observation-test"));

        assertThat(observedClient.inspect(new OrderCancelStatusPort.Command(
            cancelRequestId, List.of(10L))).status())
            .isEqualTo(CancelRestoreLegStatus.APPLIED);
        server.verify();

        String uriTemplate = "/internal/cancel-restores/{cancelRequestId}:inspect";
        assertThat(meterRegistry.find("http.client.requests")
            .tag("uri", uriTemplate).timer()).isNotNull();
        assertThat(meterRegistry.find("http.client.requests")
            .tag("uri", "/internal/cancel-restores/" + cancelRequestId + ":inspect")
            .timer()).isNull();
        assertThat(meterRegistry.find("http.client.requests").meters())
            .allSatisfy(meter -> assertThat(meter.getId().getTag("uri"))
                .doesNotContain(cancelRequestId));
    }

    @Test
    void openCircuitReturnsUnknownWithoutThirdHttpCall() {
        when(restTemplate.postForEntity(any(String.class), any(),
            eq(OrderCancelStatusHttpClient.InspectResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection refused"));
        var command = new OrderCancelStatusPort.Command("27", List.of(10L));

        client.inspect(command);
        client.inspect(command);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(client.inspect(command).status()).isEqualTo(CancelRestoreLegStatus.UNKNOWN);
        verify(restTemplate, times(2)).postForEntity(
            any(String.class), any(), eq(OrderCancelStatusHttpClient.InspectResponse.class),
            (Object[]) any());
    }

    private static Map<String, String> fields(ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return Map.of();
        }
        return event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
    }
}
