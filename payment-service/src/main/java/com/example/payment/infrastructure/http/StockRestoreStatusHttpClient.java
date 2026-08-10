package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Component
public class StockRestoreStatusHttpClient implements StockRestoreStatusPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public StockRestoreStatusHttpClient(
        RestTemplate restTemplate,
        @Value("${external.product-service.url}") String baseUrl,
        @Qualifier("stockRestoreStatusCircuitBreaker") CircuitBreaker circuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public CancelRestoreLegSnapshot inspect(Command command) {
        try {
            return circuitBreaker.executeSupplier(() -> inspectDownstream(command));
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            log.warn("stock restore inspection unavailable. cancelRequestId={}",
                command.cancelRequestId(), t);
            return unknown();
        }
    }

    private CancelRestoreLegSnapshot inspectDownstream(Command command) {
        String url = baseUrl + "/internal/cancel-restores/"
            + command.cancelRequestId() + ":inspect";
        InspectRequest request = new InspectRequest(
            command.paymentKey(),
            command.items().stream()
                .map(item -> new ItemRequest(item.skuId(), item.quantity()))
                .toList());
        ResponseEntity<InspectResponse> response = restTemplate.postForEntity(
            url, request, InspectResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                "stock inspection response is not successful: " + response.getStatusCode());
        }
        InspectResponse body = response.getBody();
        List<CancelRestoreLegSnapshot.Evidence> evidence = body.evidence() == null
            ? List.of()
            : body.evidence().stream()
                .map(item -> new CancelRestoreLegSnapshot.Evidence(
                    item.skuId(), item.currentStatus(), item.actualQuantity(),
                    item.expectedQuantity()))
                .toList();
        return new CancelRestoreLegSnapshot(
            CancelRestoreLegStatus.valueOf(body.status()), evidence);
    }

    private CancelRestoreLegSnapshot unknown() {
        return new CancelRestoreLegSnapshot(CancelRestoreLegStatus.UNKNOWN, List.of());
    }

    record InspectRequest(String paymentKey, List<ItemRequest> items) {
        InspectRequest {
            items = List.copyOf(items);
        }
    }

    record ItemRequest(long skuId, int quantity) {}

    record InspectResponse(String status, List<EvidenceResponse> evidence) {}

    record EvidenceResponse(
        long skuId,
        String currentStatus,
        Integer actualQuantity,
        int expectedQuantity
    ) {}
}
