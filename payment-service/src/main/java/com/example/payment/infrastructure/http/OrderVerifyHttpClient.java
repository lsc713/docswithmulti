package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.OrderVerifyPort;
import com.example.payment.common.exception.ErrorCode;
import com.example.payment.infrastructure.exception.OrderVerifyRejectedException;
import com.example.payment.infrastructure.exception.OrderVerifyUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * order-service `POST /v1/orders/items:verify` 동기 검증 클라이언트 (PLINK-01/03).
 * ProductStockHttpClient의 RestTemplate + Resilience4j CircuitBreaker 구조 복제.
 * X-User-Id를 헤더로 포워딩(요청자 신원, T-02-02 소유 검증 위임).
 */
@Slf4j
@Component
public class OrderVerifyHttpClient implements OrderVerifyPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public OrderVerifyHttpClient(
        RestTemplate restTemplate,
        @Value("${external.order-service.url}") String baseUrl,
        CircuitBreaker orderServiceCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = orderServiceCircuitBreaker;
    }

    @Override
    public long verify(long userId, List<Long> orderItemIds) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/orders/items:verify";
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-User-Id", String.valueOf(userId));
                HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(Map.of("orderItemIds", orderItemIds), headers);

                ResponseEntity<VerifyResponse> response =
                    restTemplate.postForEntity(url, entity, VerifyResponse.class);
                // 명시적 2xx 가드(ProductStockHttpClient WR-03 규율): 커스텀 에러 핸들러 하에서
                // 2xx 아닌 응답이 예외 없이 반환돼도 성공으로 오인하지 않는다.
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new OrderVerifyUnavailableException("order-service 응답 오류: " + response.getStatusCode());
                }
                return response.getBody().orderId();
            });
        } catch (OrderVerifyUnavailableException | OrderVerifyRejectedException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            // 404/409/403 = order-service 검증 거부(존재/단일order/소유). 그 외 4xx는 요청 결함 → 장애로 취급해 거부.
            int status = e.getStatusCode().value();
            if (status == 404) {
                throw new OrderVerifyRejectedException(ErrorCode.ORDER_ITEM_NOT_FOUND,
                    "주문 항목을 찾을 수 없습니다: " + e.getStatusCode(), e);
            }
            if (status == 409) {
                throw new OrderVerifyRejectedException(ErrorCode.ORDER_ITEMS_MULTIPLE_ORDERS,
                    "요청된 항목이 여러 주문에 걸쳐 있습니다: " + e.getStatusCode(), e);
            }
            if (status == 403) {
                throw new OrderVerifyRejectedException(ErrorCode.ORDER_OWNERSHIP_MISMATCH,
                    "해당 주문에 대한 권한이 없습니다: " + e.getStatusCode(), e);
            }
            throw new OrderVerifyUnavailableException("order-service 요청 오류: " + e.getStatusCode(), e);
        } catch (Error e) {
            // OutOfMemoryError 등 Error는 정상 예외 흐름으로 위장하지 않는다.
            throw e;
        } catch (Throwable t) {
            // 5xx/타임아웃/CallNotPermittedException(CB OPEN) 포함 → 장애 = 거부(fail-closed).
            log.error("order-service verify 실패. userId={}", userId, t);
            throw new OrderVerifyUnavailableException("order-service 서비스 오류", t);
        }
    }

    // 패키지 프라이빗(테스트 동일 패키지에서 직접 구성 — OrderVerifyHttpClientTest 미러 패턴).
    record VerifyResponse(long orderId) {}
}
