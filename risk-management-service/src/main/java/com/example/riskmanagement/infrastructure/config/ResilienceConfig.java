package com.example.riskmanagement.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ResilienceConfig {

    @Bean("merchantLimitHttpClient")
    public RestClient merchantLimitRestClient(
        @Value("${external.merchant-limit.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
