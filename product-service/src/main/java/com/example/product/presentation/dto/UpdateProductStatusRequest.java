package com.example.product.presentation.dto;

import com.example.product.domain.entity.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateProductStatusRequest(
        @NotNull ProductStatus status
) {
}
