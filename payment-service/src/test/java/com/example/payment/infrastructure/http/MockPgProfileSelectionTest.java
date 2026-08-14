package com.example.payment.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.application.interfaces.TossPaymentPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockPgProfileSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("mock-pg"))
        .withUserConfiguration(MockTossPaymentClient.class, TossPaymentHttpClient.class);

    @Test
    void mockProfileSelectsOnlyMockTossPort() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TossPaymentPort.class);
            assertThat(context.getBean(TossPaymentPort.class))
                .isInstanceOf(MockTossPaymentClient.class);
        });
    }
}
