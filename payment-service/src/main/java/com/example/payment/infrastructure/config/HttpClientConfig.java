package com.example.payment.infrastructure.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class HttpClientConfig {

    // RestTemplateBuilder 로 생성해야 Spring Boot 의 http.client.requests 자동 계측(관측 커스터마이저)이 붙는다.
    // new RestTemplate() 은 이 계측을 받지 못한다.
    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean("cancelOutboxInspectionRestTemplate")
    public RestTemplate cancelOutboxInspectionRestTemplate(
        RestTemplateBuilder builder,
        @org.springframework.beans.factory.annotation.Value(
            "${cancel.redrive.inspection.connect-timeout-ms:1000}") long connectTimeoutMs,
        @org.springframework.beans.factory.annotation.Value(
            "${cancel.redrive.inspection.read-timeout-ms:1000}") long readTimeoutMs
    ) {
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeoutMs must be greater than 0");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("readTimeoutMs must be greater than 0");
        }
        return builder
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .readTimeout(Duration.ofMillis(readTimeoutMs))
            .build();
    }
}
