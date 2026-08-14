package com.example.payment.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.TossPaymentPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

class MockPgProfileSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withPropertyValues(
            "toss.base-url=http://localhost",
            "toss.secret-key=test-secret",
            "external.pg.url=http://localhost")
        .withUserConfiguration(
            ClientsConfiguration.class,
            MockTossPaymentClient.class,
            TossPaymentHttpClient.class,
            MockPgCancelClient.class,
            PgCancelHttpClient.class);

    @Test
    void localMockPgSelectsBothMockPorts() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "mock-pg"))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(TossPaymentPort.class)).isInstanceOf(MockTossPaymentClient.class);
                assertThat(context.getBean(PgCancelPort.class)).isInstanceOf(MockPgCancelClient.class);
            });
    }

    @Test
    void prodSelectsBothRealPorts() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(TossPaymentPort.class)).isInstanceOf(TossPaymentHttpClient.class);
                assertThat(context.getBean(PgCancelPort.class)).isInstanceOf(PgCancelHttpClient.class);
            });
    }

    @Test
    void prodMockPgRejectsStartup() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "mock-pg"))
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientsConfiguration {
        @Bean
        @Primary
        RestTemplate tossRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        CircuitBreaker pgCancelCircuitBreaker() {
            return CircuitBreaker.ofDefaults("cancel");
        }

        @Bean
        CircuitBreaker pgCancelReadCircuitBreaker() {
            return CircuitBreaker.ofDefaults("cancel-read");
        }

        @Bean
        Object requiredPgPorts(TossPaymentPort confirm, PgCancelPort cancel) {
            return new Object();
        }
    }
}
