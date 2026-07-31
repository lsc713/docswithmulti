package com.example.product.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * orphan 복구용 plain RestTemplate (D-P3-3).
 * CircuitBreaker 불요 — 배경 스케줄러의 best-effort 조회이며 실패는 skip으로 흡수한다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
