package com.example.riskmanagement.infrastructure.http;

import com.example.riskmanagement.common.exception.application.MerchantCancelLimitNotFoundException;
import com.example.riskmanagement.common.exception.application.ServiceUnavailableException;
import com.example.riskmanagement.application.interfaces.MerchantLimitClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
public class MerchantLimitRestClient implements MerchantLimitClient {

    private final RestClient merchantLimitRestClient;

    public MerchantLimitRestClient(@Qualifier("merchantLimitHttpClient") RestClient merchantLimitRestClient) {
        this.merchantLimitRestClient = merchantLimitRestClient;
    }

    @CircuitBreaker(name = "merchant-limit", fallbackMethod = "fetchDailyLimitFallback")
    @Override
    public BigDecimal fetchDailyLimit(long merchantId, LocalDate kstDate) {
        MerchantLimitResponse response = merchantLimitRestClient.get()
            .uri("/internal/merchants/{merchantId}/cancel-limit", merchantId)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                if (res.getStatusCode().value() == 404)
                    throw new MerchantCancelLimitNotFoundException(merchantId);
                throw new RuntimeException("merchant-limit 4xx: " + res.getStatusCode());
            })
            .body(MerchantLimitResponse.class);
        if (response == null) {
            throw ServiceUnavailableException.merchantLimitUnavailable();
        }
        return response.dailyLimit();
    }

    // CB OPEN 또는 5xx / 타임아웃 시 호출됨
    // ignoreExceptions에 등록된 MerchantCancelLimitNotFoundException은 이 fallback을 거치지 않고 직접 전파됨
    private BigDecimal fetchDailyLimitFallback(long merchantId, LocalDate kstDate, Exception e) {
        log.warn("merchant-limit CircuitBreaker fallback. merchantId={}, cause={}", merchantId, e.getMessage());
        throw ServiceUnavailableException.merchantLimitUnavailable();
    }
}
