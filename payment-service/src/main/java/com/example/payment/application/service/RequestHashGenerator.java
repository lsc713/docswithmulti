package com.example.payment.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public class RequestHashGenerator {

    private RequestHashGenerator() {}

    /**
     * SHA-256(paymentKey + paymentItemIds 오름차순 정렬)
     * domain-rules.md 5-1: 멱등성 규칙
     */
    public static String generate(String paymentKey, List<Long> paymentItemIds) {
        List<Long> sorted = paymentItemIds.stream().sorted().toList();
        String raw = paymentKey + sorted.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
