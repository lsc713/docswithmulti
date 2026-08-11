package com.example.payment.infrastructure.config;

import com.example.payment.infrastructure.http.OrderCancelStatusHttpClient;
import com.example.payment.infrastructure.http.StockRestoreStatusHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class CancelOutboxInspectionHttpClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(
            HttpClientConfig.class,
            OrderCancelStatusHttpClient.class,
            StockRestoreStatusHttpClient.class,
            TestDependencies.class)
        .withPropertyValues(
            "external.order-service.url=http://order-service",
            "external.product-service.url=http://product-service");

    @Test
    void defaultInspectionReadTimeoutBoundsARealNonResponsiveServer() throws Exception {
        try (NonResponsiveServer server = new NonResponsiveServer()) {
            contextRunner.run(context -> {
                RestTemplate inspection = context.getBean(
                    "cancelOutboxInspectionRestTemplate", RestTemplate.class);

                long startedAt = System.nanoTime();
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> inspection.getForObject(server.url(), String.class))
                        .isInstanceOf(RestClientException.class));
                assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isBetween(Duration.ofMillis(500), Duration.ofMillis(2_500));
                assertThat(server.awaitAccepted()).isTrue();
            });
        }
    }

    @Test
    void nonPositiveInspectionTimeoutsFailFast() {
        contextRunner
            .withPropertyValues("cancel.redrive.inspection.connect-timeout-ms=0")
            .run(context -> assertThat(context).hasFailed());

        contextRunner
            .withPropertyValues("cancel.redrive.inspection.read-timeout-ms=-1")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void inspectionClientsUseQualifiedTemplateWhileUnrelatedClientUsesPrimaryDefault() {
        contextRunner.run(context -> {
            RestTemplate defaultTemplate = context.getBean("restTemplate", RestTemplate.class);
            RestTemplate inspectionTemplate = context.getBean(
                "cancelOutboxInspectionRestTemplate", RestTemplate.class);

            assertThat(ReflectionTestUtils.getField(
                context.getBean(OrderCancelStatusHttpClient.class), "restTemplate"))
                .isSameAs(inspectionTemplate);
            assertThat(ReflectionTestUtils.getField(
                context.getBean(StockRestoreStatusHttpClient.class), "restTemplate"))
                .isSameAs(inspectionTemplate);
            assertThat(context.getBean(UnrelatedClient.class).restTemplate)
                .isSameAs(defaultTemplate)
                .isNotSameAs(inspectionTemplate);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        RestTemplateBuilder restTemplateBuilder() {
            return new RestTemplateBuilder();
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean("orderCancelStatusCircuitBreaker")
        CircuitBreaker orderCancelStatusCircuitBreaker(CircuitBreakerRegistry registry) {
            return registry.circuitBreaker("order-status-test");
        }

        @Bean("stockRestoreStatusCircuitBreaker")
        CircuitBreaker stockRestoreStatusCircuitBreaker(CircuitBreakerRegistry registry) {
            return registry.circuitBreaker("stock-status-test");
        }

        @Bean
        UnrelatedClient unrelatedClient(RestTemplate restTemplate) {
            return new UnrelatedClient(restTemplate);
        }
    }

    static final class UnrelatedClient {
        private final RestTemplate restTemplate;

        private UnrelatedClient(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
        }
    }

    private static final class NonResponsiveServer implements AutoCloseable {
        private final ServerSocket server;
        private final CountDownLatch accepted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;
        private volatile Socket client;

        private NonResponsiveServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = Thread.ofPlatform().start(() -> {
                try {
                    client = server.accept();
                    accepted.countDown();
                    release.await();
                } catch (IOException | InterruptedException ignored) {
                    // Closing the server socket is the test cleanup signal.
                }
            });
        }

        private String url() {
            return "http://" + server.getInetAddress().getHostAddress() + ":"
                + server.getLocalPort() + "/inspection";
        }

        private boolean awaitAccepted() throws InterruptedException {
            return accepted.await(1, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            if (client != null) {
                client.close();
            }
            server.close();
            thread.join(1_000);
        }
    }
}
