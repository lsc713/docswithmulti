package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.ProductStockPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class ProductStockHttpClient implements ProductStockPort {

    public ProductStockHttpClient(
        RestTemplate restTemplate,
        @Value("${external.product-service.url}") String baseUrl,
        CircuitBreaker productServiceCircuitBreaker
    ) {
    }

    @Override
    public void reserve(String paymentKey, List<Item> items) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void release(String paymentKey, List<Item> items) {
        throw new UnsupportedOperationException("not implemented");
    }
}
