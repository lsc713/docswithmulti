package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.Address;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "addresses", indexes = {
        @Index(name = "idx_addresses_user_id", columnList = "user_id")
})
public class AddressJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false)
    private String address;

    @Column(name = "address_detail")
    private String addressDetail;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AddressJpaEntity() {}

    public static AddressJpaEntity from(Address address) {
        AddressJpaEntity entity = new AddressJpaEntity();
        entity.id = address.getId();
        entity.userId = address.getUserId();
        entity.label = address.getLabel();
        entity.recipient = address.getRecipient();
        entity.phone = address.getPhone();
        entity.zipCode = address.getZipCode();
        entity.address = address.getAddress();
        entity.addressDetail = address.getAddressDetail();
        entity.isDefault = address.isDefault();
        entity.createdAt = LocalDateTime.ofInstant(address.getCreatedAt(), ZoneOffset.UTC);
        entity.updatedAt = LocalDateTime.ofInstant(address.getUpdatedAt(), ZoneOffset.UTC);
        return entity;
    }

    public Address toDomain() {
        return Address.reconstruct(
                id, userId, label, recipient, phone, zipCode, address, addressDetail, isDefault,
                createdAt.toInstant(ZoneOffset.UTC),
                updatedAt.toInstant(ZoneOffset.UTC)
        );
    }
}
