package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.infrastructure.exception.PgServiceException;
import com.example.payment.infrastructure.http.dto.TossCancel;
import com.example.payment.infrastructure.http.dto.TossPaymentResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!mock-pg")
public class PgCancelHttpClient implements PgCancelPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;
    // WR-04: 조회(getStatus) 전용 CircuitBreaker — cancel(취소 실행)과 분리해 조회 실패율 증가가
    // 취소 실행 호출까지 함께 차단하지 않도록 한다.
    private final CircuitBreaker readCircuitBreaker;

    public PgCancelHttpClient(
        RestTemplate restTemplate,
        @Value("${external.pg.url}") String baseUrl,
        CircuitBreaker pgCancelCircuitBreaker,
        CircuitBreaker pgCancelReadCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = pgCancelCircuitBreaker;
        this.readCircuitBreaker = pgCancelReadCircuitBreaker;
    }

    @Override
    public PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/payments/{paymentKey}/cancel";
                Map<String, Object> request = Map.of(
                    "cancelAmount", cancelAmount,
                    "cancelReason", cancelReason
                );
                ResponseEntity<TossPaymentResponse> response =
                    restTemplate.postForEntity(url, request, TossPaymentResponse.class, paymentKey);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new PgServiceException("PG 취소 응답 오류: " + response.getStatusCode());
                }
                return mapCancelResponse(response.getBody(), cancelAmount);
            });
        } catch (PgServiceException e) {
            throw e;
        } catch (Error e) {
            // WR-05: OutOfMemoryError 등 Error까지 삼켜 정상 예외 흐름으로 위장하지 않는다.
            throw e;
        } catch (Throwable t) {
            log.error("PG cancel 실패. paymentKey={}", paymentKey, t);
            throw new PgServiceException("PG 서비스 오류", t);
        }
    }

    @Override
    public PgCancelResult getStatus(String paymentKey, BigDecimal cancelAmount) {
        try {
            return readCircuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/payments/{paymentKey}";
                ResponseEntity<TossPaymentResponse> response =
                    restTemplate.getForEntity(url, TossPaymentResponse.class, paymentKey);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new PgServiceException("PG 상태조회 응답 오류: " + response.getStatusCode());
                }
                return mapStatus(response.getBody(), cancelAmount);
            });
        } catch (PgServiceException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            log.error("PG 상태조회 실패. paymentKey={}", paymentKey, t);
            throw new PgServiceException("PG 서비스 오류", t);
        }
    }

    /**
     * cancel() 응답(방금 실행한 취소) 매핑.
     * 방금 생성된 취소 = cancels[] 중 cancelAmount 매칭 원소(여러 개면 마지막) 또는,
     * 매칭이 없으면 Toss가 append하는 특성상 마지막 원소.
     */
    private PgCancelResult mapCancelResponse(TossPaymentResponse response, BigDecimal cancelAmount) {
        List<TossCancel> cancels = response.cancels() != null ? response.cancels() : List.of();
        TossCancel matched = findMatchingCancel(cancels, cancelAmount)
            .orElseGet(() -> cancels.isEmpty() ? null : cancels.get(cancels.size() - 1));
        String transactionKey = matched != null ? matched.transactionKey() : null;
        boolean matchedDone = matched != null && "DONE".equals(matched.cancelStatus());

        String status = response.status();
        if ("CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status) || matchedDone) {
            return PgCancelResult.approved(transactionKey);
        }
        if ("WAITING_FOR_DEPOSIT".equals(status) || "IN_PROGRESS".equals(status)) {
            return PgCancelResult.pending(transactionKey);
        }
        throw new PgServiceException("PG 취소 응답 상태 이상: " + status);
    }

    /**
     * getStatus(paymentKey, cancelAmount) 매핑 규칙 1~7 (D-01, 실 Toss 계약).
     * "이 결제에서 우리 취소가 반영됐나?"를 cancels[] + Payment.status로 판단.
     */
    private PgCancelResult mapStatus(TossPaymentResponse response, BigDecimal cancelAmount) {
        List<TossCancel> cancels = response.cancels() != null ? response.cancels() : List.of();
        String status = response.status();

        // 규칙 1: cancelStatus==DONE 이고 금액이 정확히 일치하는 취소 건 존재 → 우리 취소 반영됨
        Optional<TossCancel> matchedDone = cancels.stream()
            .filter(c -> "DONE".equals(c.cancelStatus()) && cancelAmount.compareTo(c.cancelAmount()) == 0)
            .findFirst();
        if (matchedDone.isPresent()) {
            return PgCancelResult.approved(matchedDone.get().transactionKey());
        }

        // 규칙 2: 전액취소 완료 — 매칭 cancel 없으면 마지막 원소(없으면 null)
        if ("CANCELED".equals(status)) {
            String transactionKey = cancels.isEmpty() ? null : cancels.get(cancels.size() - 1).transactionKey();
            return PgCancelResult.approved(transactionKey);
        }

        // 규칙 3: 결제 활성 상태(취소 안 됨) → 복구가 cancel() 재호출하도록 재시도 가능 실패
        if ("DONE".equals(status)) {
            return PgCancelResult.retryableFailed(null);
        }

        // 규칙 4: 진행 중
        if ("IN_PROGRESS".equals(status) || "WAITING_FOR_DEPOSIT".equals(status)) {
            return PgCancelResult.pending(null);
        }

        // 규칙 5: 재시도 불가 — 만료/중단
        if ("ABORTED".equals(status) || "EXPIRED".equals(status)) {
            return PgCancelResult.failed(null);
        }

        // 규칙 6: 부분취소됐지만 우리 취소가 반영 안 됨 → 재시도 가능
        if ("PARTIAL_CANCELED".equals(status)) {
            return PgCancelResult.retryableFailed(null);
        }

        // 규칙 7: 알 수 없는 status(READY 등) — 조용히 무시하지 않고 예외 → PROCESSING 유지, 다음 주기 재시도
        throw new PgServiceException("PG 상태조회 응답 이상: 알 수 없는 status=" + status);
    }

    private Optional<TossCancel> findMatchingCancel(List<TossCancel> cancels, BigDecimal cancelAmount) {
        return cancels.stream()
            .filter(c -> cancelAmount.compareTo(c.cancelAmount()) == 0)
            .reduce((first, last) -> last);
    }
}
