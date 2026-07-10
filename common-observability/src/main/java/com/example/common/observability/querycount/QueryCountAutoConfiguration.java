package com.example.common.observability.querycount;

import io.micrometer.core.instrument.MeterRegistry;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * loadtest.query-count.enabled=true 일 때만 활성. 평상시/CI 영향 0.
 * DataSource를 datasource-proxy로 래핑하고, 요청당 쿼리 수 필터를 등록한다.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "loadtest.query-count", name = "enabled", havingValue = "true")
public class QueryCountAutoConfiguration {

    @Bean
    public QueryCountReader queryCountReader() {
        return new DataSourceProxyQueryCountReader();
    }

    @Bean
    public QueryCountFilter queryCountFilter(QueryCountReader reader, MeterRegistry registry) {
        return new QueryCountFilter(reader, registry);
    }

    /** DataSource 빈을 ProxyDataSource로 래핑 (static: BPP는 조기 등록돼야 함). */
    @Bean
    public static BeanPostProcessor queryCountDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds)
                            .name("main")
                            .listener(new DataSourceQueryCountListener())
                            .build();
                }
                return bean;
            }
        };
    }
}
