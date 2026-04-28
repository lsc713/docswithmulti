package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.infrastructure.exception.RiskServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
public class RiskManagementHttpClient implements RiskManagementPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public RiskManagementHttpClient(
        RestTemplate restTemplate,
        @Value("${external.risk-management.url}") String baseUrl,
        CircuitBreaker riskManagementCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = riskManagementCircuitBreaker;
    }

    @Override
    public RiskReserveResult validateAndReserve(
        long merchantId, long cancelRequestId, BigDecimal cancelAmount, LocalDate kstDate
    ) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/internal/cancel-limit/validate-and-reserve";
                Map<String, Object> request = Map.of(
                    "merchantId", merchantId,
                    "cancelRequestId", String.valueOf(cancelRequestId),
                    "cancelAmount", cancelAmount,
                    "kstDate", kstDate.toString()
                );
                ResponseEntity<RiskReserveResult> response =
                    restTemplate.postForEntity(url, request, RiskReserveResult.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RiskServiceException("risk-management 응답 오류: " + response.getStatusCode());
                }
                return response.getBody();
            });
        } catch (RiskServiceException e) {
            throw e;
        } catch (Throwable t) {
            log.error("risk-management validateAndReserve 실패. merchantId={}", merchantId, t);
            throw new RiskServiceException("risk-management 서비스 오류", t);
        }
    }

    @Override
    public void compensate(long cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        try {
            circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/internal/cancel-limit/compensate";
                Map<String, Object> request = Map.of(
                    "cancelRequestId", cancelRequestId,
                    "merchantId", merchantId,
                    "restoreAmount", restoreAmount
                );
                return restTemplate.postForEntity(url, request, Void.class);
            });
        } catch (Throwable t) {
            log.error("risk-management compensate 실패. cancelRequestId={}", cancelRequestId, t);
            throw new RiskServiceException("risk-management 보상 트랜잭션 실패", t);
        }
    }

    @Override
    public boolean isCharged(long cancelRequestId) {
        // TODO: implement in Task 6
        throw new UnsupportedOperationException("isCharged not yet implemented");
    }
}
