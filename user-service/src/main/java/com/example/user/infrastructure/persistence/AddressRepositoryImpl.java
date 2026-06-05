package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.AddressRepository;
import com.example.user.domain.entity.Address;

import java.util.List;
import java.util.Optional;

public class AddressRepositoryImpl implements AddressRepository {

    private final AddressJpaRepository jpa;

    public AddressRepositoryImpl(AddressJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Address> findAllByUserId(long userId) {
        return jpa.findAllByUserId(userId).stream().map(AddressJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Address> findByIdAndUserId(long id, long userId) {
        return jpa.findByIdAndUserId(id, userId).map(AddressJpaEntity::toDomain);
    }

    @Override
    public Address save(Address address) {
        return jpa.save(AddressJpaEntity.from(address)).toDomain();
    }

    @Override
    public void deleteById(long id) {
        jpa.deleteById(id);
    }
}
