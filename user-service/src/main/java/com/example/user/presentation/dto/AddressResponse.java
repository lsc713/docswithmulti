package com.example.user.presentation.dto;

import com.example.user.domain.entity.Address;

public record AddressResponse(Long id, String label, String recipient, String phone,
                               String zipCode, String address, String addressDetail, boolean isDefault) {
    public static AddressResponse from(Address a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipient(), a.getPhone(),
                a.getZipCode(), a.getAddress(), a.getAddressDetail(), a.isDefault());
    }
}
