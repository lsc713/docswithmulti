// FilterConfig.java — 등록 순서 CORS(최우선) → CSRF
package com.example.gateway.config;

import com.example.gateway.filter.CsrfFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class FilterConfig {
    @Bean
    FilterRegistrationBean<CorsFilter> corsRegistration(CorsFilter corsFilter) {
        var reg = new FilterRegistrationBean<>(corsFilter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
    @Bean
    FilterRegistrationBean<CsrfFilter> csrfRegistration() {
        var reg = new FilterRegistrationBean<>(new CsrfFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);  // CORS 다음
        return reg;
    }
}
