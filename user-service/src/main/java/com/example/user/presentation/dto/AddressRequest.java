package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressRequest(
    @NotBlank String label, @NotBlank String recipient, @NotBlank String phone,
    @NotBlank String zipCode, @NotBlank String address, String addressDetail, boolean isDefault
) {}
