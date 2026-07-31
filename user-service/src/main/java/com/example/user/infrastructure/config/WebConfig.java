package com.example.user.infrastructure.config;

import com.example.user.infrastructure.security.JwtTokenProvider;
import com.example.user.presentation.support.AuthCookieFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {
    @Bean
    AuthCookieFactory authCookieFactory(
            JwtTokenProvider jwtTokenProvider,
            @Value("${auth.cookie.secure:true}") boolean secure,
            @Value("${auth.cookie.domain:}") String domain) {
        return new AuthCookieFactory(secure, domain, jwtTokenProvider.getRefreshTokenExpiry());
    }
}
