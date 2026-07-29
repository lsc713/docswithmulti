package com.example.payment.infrastructure.http;

import com.example.payment.infrastructure.config.HttpClientConfig;
import com.example.payment.infrastructure.config.ResilienceConfig;
import com.example.payment.infrastructure.http.dto.TossCancel;
import com.example.payment.infrastructure.http.dto.TossPaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Cardinality 불변식 테스트: PG 클라이언트가 http.client.requests 의 uri 태그를
 * paymentKey 가 확장된 경로가 아닌 URI 템플릿 형태로 기록하는지 검증한다.
 *
 * URI 템플릿 `/v1/payments/{paymentKey}/cancel` 로 postForEntity 를 호출해야만
 * Micrometer 가 `{paymentKey}` 플레이스홀더를 uri 태그에 보존한다.
 * 구체적으로 확장된 paymentKey 가 태그로 새어나오면 포화 부하 테스트(185rps) 중
 * 타이머 시리즈가 paymentKey 수만큼 무한 증가한다.
 *
 * 패턴: HttpClientConfigMetricsTest 와 동일한 @SpringBootTest 슬라이스
 * (DB/JPA/Flyway/Redis 자동구성 제외). MockRestServiceServer 로 실제 PG 불필요.
 */
@SpringBootTest(
    classes = PgCancelHttpClientCardinalityTest.TestApp.class,
    properties = {
        "external.pg.url=http://pg-stub",
        "spring.main.allow-bean-definition-overriding=true"
    })
class PgCancelHttpClientCardinalityTest {

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PgCancelHttpClient pgCancelHttpClient;

    MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void tearDown() {
        mockServer.reset();
    }

    @Test
    void pgCancel_records_uri_tag_as_template_not_expanded_paymentKey() throws Exception {
        // Given: PG 스텁 — paymentKey 가 확장된 실제 경로로 요청이 온다 (실 Toss 계약 응답 형태, D-01)
        String paymentKey = "pay_UNIQUE_KEY_12345_abc";
        BigDecimal cancelAmount = new BigDecimal("10000");
        TossPaymentResponse approved = new TossPaymentResponse(
            "CANCELED", BigDecimal.ZERO,
            java.util.List.of(new TossCancel(
                cancelAmount, "테스트 취소", BigDecimal.ZERO, BigDecimal.ZERO,
                "tx-ok", "2026-07-29T00:00:00", "DONE")));
        String responseJson = new ObjectMapper().writeValueAsString(approved);

        mockServer.expect(requestTo("http://pg-stub/v1/payments/" + paymentKey + "/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // When: cancel 호출
        pgCancelHttpClient.cancel(paymentKey, cancelAmount, "테스트 취소");

        mockServer.verify();

        // Then: uri 태그는 템플릿 형태 — paymentKey 값이 태그에 노출되면 안 된다
        var timer = meterRegistry.find("http.client.requests")
            .tag("uri", "/v1/payments/{paymentKey}/cancel")
            .timer();

        assertThat(timer)
            .as("http.client.requests uri 태그는 URI 템플릿(/v1/payments/{paymentKey}/cancel)이어야 한다 — "
                + "paymentKey 값이 노출되면 포화 부하 시 cardinality 가 무한 증가한다")
            .isNotNull();

        assertThat(timer.count()).isGreaterThanOrEqualTo(1L);

        // 추가 확인: 확장된 paymentKey 경로는 타이머 태그에 없어야 한다
        var expandedTimer = meterRegistry.find("http.client.requests")
            .tag("uri", "/v1/payments/" + paymentKey + "/cancel")
            .timer();

        assertThat(expandedTimer)
            .as("확장된 paymentKey 경로(/v1/payments/" + paymentKey + "/cancel)가 uri 태그로 기록되면 안 된다")
            .isNull();
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
    @Import({HttpClientConfig.class, ResilienceConfig.class, PgCancelHttpClient.class})
    static class TestApp {
    }
}
