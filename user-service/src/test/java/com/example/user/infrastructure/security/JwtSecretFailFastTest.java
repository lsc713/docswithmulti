package com.example.user.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-P1-3 회귀: jwt.secret 은 비-local 프로파일에서 {@code ${JWT_SECRET}}(기본값 없음)로만 바인딩되고
 * dev 기본값은 local 프로파일 문서에만 존재한다(application.yml). 따라서 JWT_SECRET 미주입 상태로
 * 비-local 프로파일 컨텍스트를 기동하면 placeholder 미해결로 빈 생성이 실패 → 컨텍스트 기동 실패(fail-fast).
 *
 * 실 application.yml 을 그대로 로드하기 위해 ConfigDataApplicationContextInitializer 를 쓰고,
 * jwt.secret 을 소비하는 최소 빈만 등록한다(DataSource/Flyway 오토컨피그 비의존 → DB 불필요).
 * PropertySourcesPlaceholderConfigurer 를 등록해 실제 Boot 컨텍스트와 동일하게 {@code @Value} 가
 * 중첩 placeholder 를 재귀 해석하고 미해결 시 예외를 던지도록 한다.
 * 하드코딩 시크릿을 테스트에 넣지 않는다(CLAUDE.md).
 *
 * 전제: 실행 환경에 JWT_SECRET 환경변수가 없어야 한다(CI 기본). 있으면 prod 케이스가 기동 성공해버린다.
 */
@DisplayName("D-P1-3: JWT_SECRET fail-fast")
class JwtSecretFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(JwtSecretConsumer.class);

    @Test
    @DisplayName("비-local 프로파일 + JWT_SECRET 미주입 → 컨텍스트 기동 실패(placeholder 미해결)")
    void nonLocalProfileWithoutSecretFailsFast() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("대조: local 프로파일은 dev 기본값으로 정상 기동")
    void localProfileStartsWithDevDefault() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    static class JwtSecretConsumer {
        // 실 Boot 컨텍스트와 동일한 @Value 재귀 해석/미해결 예외 동작을 위해 등록.
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        String jwtSecretHolder(@Value("${jwt.secret}") String secret) {
            return secret;
        }
    }
}
