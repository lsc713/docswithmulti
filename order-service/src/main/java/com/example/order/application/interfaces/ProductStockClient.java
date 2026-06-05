package com.example.order.application.interfaces;

public interface ProductStockClient {
    void deduct(long skuId, int quantity);
}
