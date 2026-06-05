package com.example.user.application.interfaces;

import com.example.user.domain.entity.Address;

import java.util.List;
import java.util.Optional;

public interface AddressRepository {
    List<Address> findAllByUserId(long userId);
    Optional<Address> findByIdAndUserId(long id, long userId);
    Address save(Address address);
    void deleteById(long id);
}
