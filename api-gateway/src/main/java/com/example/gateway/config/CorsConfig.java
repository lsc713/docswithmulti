package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/** 게이트웨이 CORS(우회 없이 정식). spring-security 미사용 — spring-web CorsFilter. */
@Configuration
public class CorsConfig {

    public UrlBasedCorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);              // 명시 화이트리스트(*금지)
        cfg.setAllowCredentials(true);
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    CorsFilter corsFilter(@Value("${gateway.cors.allowed-origins}") List<String> allowedOrigins) {
        return new CorsFilter(corsConfigurationSource(allowedOrigins));
    }

    // CorsFilter는 CSRF/라우팅보다 먼저 실행돼야 preflight가 단락됨 → 등록 순서는 FilterConfig(Task 8)에서 보장.
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
}
