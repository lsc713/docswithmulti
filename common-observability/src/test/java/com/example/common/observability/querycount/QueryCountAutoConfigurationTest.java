package com.example.common.observability.querycount;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCountAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QueryCountAutoConfiguration.class))
            .withBean("meterRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void beansPresentWhenEnabled() {
        runner.withPropertyValues("loadtest.query-count.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(QueryCountFilter.class);
                    assertThat(ctx).hasSingleBean(QueryCountReader.class);
                });
    }

    @Test
    void beansAbsentByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(QueryCountFilter.class);
            assertThat(ctx).doesNotHaveBean(QueryCountReader.class);
        });
    }

    @Test
    void beansAbsentWhenDisabled() {
        runner.withPropertyValues("loadtest.query-count.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(QueryCountFilter.class));
    }
}
