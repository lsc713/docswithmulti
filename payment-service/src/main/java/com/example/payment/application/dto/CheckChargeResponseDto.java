package com.example.payment.application.dto;

import java.math.BigDecimal;

public record CheckChargeResponseDto(
    String cancelRequestId,
    boolean charged,
    Long merchantId,
    BigDecimal cancelAmount
) {}
