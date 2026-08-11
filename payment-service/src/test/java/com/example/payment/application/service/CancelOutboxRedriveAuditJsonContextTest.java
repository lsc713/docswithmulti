package com.example.payment.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CancelOutboxRedriveAuditJsonContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(AuditJsonConfiguration.class);

    @Test
    void bootJacksonAutoConfigurationConstructsAuditJsonComponent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context).hasSingleBean(CancelOutboxRedriveAuditJson.class);
            assertThat(context.getBean(CancelOutboxRedriveAuditJson.class).alreadyAppliedOutcome())
                .isEqualTo("{\"outcome\":\"ALREADY_APPLIED\"}");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CancelOutboxRedriveAuditJson.class)
    static class AuditJsonConfiguration {}
}
