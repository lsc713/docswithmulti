package com.example.user.application.usecase;

import com.example.user.domain.entity.Address;
import java.util.List;

public interface AddressUseCase {
    record CreateCommand(String label, String recipient, String phone, String zipCode,
                         String address, String addressDetail, boolean isDefault) {}
    record UpdateCommand(String label, String recipient, String phone, String zipCode,
                         String address, String addressDetail, boolean isDefault) {}

    List<Address> getAddresses(long userId);
    Address create(long userId, CreateCommand command);
    Address update(long userId, long addressId, UpdateCommand command);
    void delete(long userId, long addressId);
}
