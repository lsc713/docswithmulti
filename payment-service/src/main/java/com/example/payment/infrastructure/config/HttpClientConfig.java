package com.example.payment.infrastructure.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    // RestTemplateBuilder 로 생성해야 Spring Boot 의 http.client.requests 자동 계측(관측 커스터마이저)이 붙는다.
    // new RestTemplate() 은 이 계측을 받지 못한다.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
