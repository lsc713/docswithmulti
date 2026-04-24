package com.example.order.application.interfaces;

public interface ProcessedCancelEventRepository {
    boolean existsByCancelRequestId(String cancelRequestId);
    void save(String cancelRequestId);
}
