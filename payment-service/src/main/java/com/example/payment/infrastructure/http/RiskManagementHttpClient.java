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
        } catch (Error e) {
            // WR-05: OutOfMemoryError 등 Error까지 삼켜 정상 예외 흐름으로 위장하지 않는다.
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
                ResponseEntity<Void> response = restTemplate.postForEntity(url, request, Void.class);
                // WR-03: 다른 메서드(validateAndReserve/isCharged)와 동일하게 명시적 상태 코드 가드.
                // RestTemplate 기본 에러 핸들러의 암묵적 예외 발생에만 의존하지 않는다 —
                // compensate는 금전(한도 사용량 복원)에 직결되므로 200 OK + 실패 바디 규약으로
                // 바뀌어도 보상 실패를 성공으로 오인하지 않아야 한다.
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new RiskServiceException("risk-management 보상 응답 오류: " + response.getStatusCode());
                }
                return response;
            });
        } catch (RiskServiceException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            log.error("risk-management compensate 실패. cancelRequestId={}", cancelRequestId, t);
            throw new RiskServiceException("risk-management 보상 트랜잭션 실패", t);
        }
    }

    @Override
    public boolean isCharged(long cancelRequestId) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/internal/cancel-limit/check?cancelRequestId={cancelRequestId}";
                ResponseEntity<com.example.payment.application.dto.CheckChargeResponseDto> response =
                    restTemplate.getForEntity(
                        url, com.example.payment.application.dto.CheckChargeResponseDto.class, cancelRequestId);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RiskServiceException("risk-management 차감조회 응답 오류: " + response.getStatusCode());
                }
                return response.getBody().charged();
            });
        } catch (RiskServiceException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            log.error("risk-management isCharged 조회 실패. cancelRequestId={}", cancelRequestId, t);
            throw new RiskServiceException("risk-management 서비스 오류", t);
        }
    }
}
