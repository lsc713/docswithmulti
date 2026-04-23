package com.example.riskmanagement.infrastructure.http;

public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(long merchantId) {
        super("가맹점을 찾을 수 없습니다: " + merchantId);
    }
}
