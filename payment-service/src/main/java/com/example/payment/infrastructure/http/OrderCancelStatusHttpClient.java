package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.OrderCancelStatusPort;
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
public class OrderCancelStatusHttpClient implements OrderCancelStatusPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public OrderCancelStatusHttpClient(
        @Qualifier("cancelOutboxInspectionRestTemplate") RestTemplate restTemplate,
        @Value("${external.order-service.url}") String baseUrl,
        @Qualifier("orderCancelStatusCircuitBreaker") CircuitBreaker circuitBreaker
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
            log.warn("order cancel restore inspection unavailable. cancelRequestId={}",
                command.cancelRequestId(), t);
            return unknown();
        }
    }

    private CancelRestoreLegSnapshot inspectDownstream(Command command) {
        String url = baseUrl + "/internal/cancel-restores/"
            + command.cancelRequestId() + ":inspect";
        ResponseEntity<InspectResponse> response = restTemplate.postForEntity(
            url, new InspectRequest(command.orderItemIds()), InspectResponse.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                "order inspection response is not successful: " + response.getStatusCode());
        }
        InspectResponse body = response.getBody();
        List<CancelRestoreLegSnapshot.Evidence> evidence = body.evidence() == null
            ? List.of()
            : body.evidence().stream()
                .map(item -> new CancelRestoreLegSnapshot.Evidence(
                    item.targetId(), item.currentStatus(), null, null))
                .toList();
        return new CancelRestoreLegSnapshot(
            CancelRestoreLegStatus.valueOf(body.status()), evidence);
    }

    private CancelRestoreLegSnapshot unknown() {
        return new CancelRestoreLegSnapshot(CancelRestoreLegStatus.UNKNOWN, List.of());
    }

    record InspectRequest(List<Long> orderItemIds) {
        InspectRequest {
            orderItemIds = List.copyOf(orderItemIds);
        }
    }

    record InspectResponse(String status, List<EvidenceResponse> evidence) {}

    record EvidenceResponse(long targetId, String currentStatus) {}
}
