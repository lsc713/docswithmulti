package com.example.user.application.service;

import com.example.user.application.exception.AddressNotFoundException;
import com.example.user.application.interfaces.AddressRepository;
import com.example.user.application.usecase.AddressUseCase;
import com.example.user.domain.entity.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService implements AddressUseCase {
    private final AddressRepository addressRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Address> getAddresses(long userId) { return addressRepository.findAllByUserId(userId); }

    @Override
    @Transactional
    public Address create(long userId, CreateCommand command) {
        Address address = Address.of(userId, command.label(), command.recipient(), command.phone(),
                command.zipCode(), command.address(), command.addressDetail(), command.isDefault());
        return addressRepository.save(address);
    }

    @Override
    @Transactional
    public Address update(long userId, long addressId, UpdateCommand command) {
        Address address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        address.update(command.label(), command.recipient(), command.phone(),
                command.zipCode(), command.address(), command.addressDetail(), command.isDefault());
        return addressRepository.save(address);
    }

    @Override
    @Transactional
    public void delete(long userId, long addressId) {
        addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        addressRepository.deleteById(addressId);
    }
}
