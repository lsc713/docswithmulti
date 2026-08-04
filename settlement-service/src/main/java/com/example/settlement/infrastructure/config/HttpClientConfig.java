package com.example.settlement.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * reconcile HTTP pull 용 plain RestTemplate (product HttpClientConfig 미러).
 * CircuitBreaker 불요 — 주간 best-effort 조회이며 실패는 그대로 전파해 다음 폴에서 재시도(idempotent).
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
