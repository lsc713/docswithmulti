package com.example.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 홉 지연 계측 회귀 테스트. RestTemplate 이 RestTemplateBuilder 로 생성돼야
 * Spring Boot 의 http.client.requests 자동 계측이 붙는다. new RestTemplate() 이면 미발행.
 * 인프라(DB/JPA/Kafka/Redis/Flyway) 자동구성은 제외해 컨테이너 없이 부팅한다.
 *
 * Spring Boot 4.x 기준: 자동구성 클래스가 별도 모듈로 분리됨
 * - org.springframework.boot.jdbc.autoconfigure.*
 * - org.springframework.boot.hibernate.autoconfigure.*
 * - org.springframework.boot.flyway.autoconfigure.*
 * - org.springframework.boot.data.redis.autoconfigure.*
 * Kafka autoconfigure(spring-boot-kafka)는 이 프로젝트 클래스패스에 없으므로 제외 불필요.
 */
@SpringBootTest(
    classes = HttpClientConfigMetricsTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpClientConfigMetricsTest {

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void restTemplateEmitsHttpClientRequestsMetric() {
        restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);

        assertThat(meterRegistry.find("http.client.requests").timer())
            .as("RestTemplateBuilder 로 만든 RestTemplate 은 http.client.requests 를 발행해야 한다")
            .isNotNull();
        assertThat(meterRegistry.get("http.client.requests").timer().count())
            .isGreaterThanOrEqualTo(1L);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        DataRedisAutoConfiguration.class,
        DataRedisRepositoriesAutoConfiguration.class
    })
    @Import(HttpClientConfig.class)
    static class TestApp {

        // PingController 는 @RestController 로 컴포넌트 스캔 대상이므로 별도 @Bean 등록 불필요
        @RestController
        static class PingController {
            @GetMapping("/ping")
            String ping() {
                return "ok";
            }
        }
    }
}
