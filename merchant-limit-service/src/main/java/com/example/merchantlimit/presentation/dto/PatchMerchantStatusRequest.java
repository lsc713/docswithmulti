package com.example.merchantlimit.presentation.dto;

import jakarta.validation.constraints.NotNull;

public record PatchMerchantStatusRequest(
    @NotNull String status
) {}
