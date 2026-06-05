package com.example.user.domain.entity;

import java.time.Instant;
import java.util.Objects;

public class Address {

    private Long id;
    private Long userId;
    private String label;
    private String recipient;
    private String phone;
    private String zipCode;
    private String address;
    private String addressDetail;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    private Address() {}

    public static Address of(long userId, String label, String recipient, String phone,
                             String zipCode, String address, String addressDetail, boolean isDefault) {
        Address a = new Address();
        a.userId = userId;
        a.label = label;
        a.recipient = recipient;
        a.phone = phone;
        a.zipCode = zipCode;
        a.address = address;
        a.addressDetail = addressDetail;
        a.isDefault = isDefault;
        a.createdAt = Instant.now();
        a.updatedAt = Instant.now();
        return a;
    }

    public static Address reconstruct(Long id, long userId, String label, String recipient, String phone,
                                      String zipCode, String address, String addressDetail, boolean isDefault,
                                      Instant createdAt, Instant updatedAt) {
        Address a = new Address();
        a.id = id;
        a.userId = userId;
        a.label = label;
        a.recipient = recipient;
        a.phone = phone;
        a.zipCode = zipCode;
        a.address = address;
        a.addressDetail = addressDetail;
        a.isDefault = isDefault;
        a.createdAt = createdAt;
        a.updatedAt = updatedAt;
        return a;
    }

    public void update(String label, String recipient, String phone,
                       String zipCode, String address, String addressDetail, boolean isDefault) {
        this.label = label;
        this.recipient = recipient;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.isDefault = isDefault;
        this.updatedAt = Instant.now();
    }

    public void clearDefault() {
        this.isDefault = false;
        this.updatedAt = Instant.now();
    }

    // Getters
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getRecipient() { return recipient; }
    public String getPhone() { return phone; }
    public String getZipCode() { return zipCode; }
    public String getAddress() { return address; }
    public String getAddressDetail() { return addressDetail; }
    public boolean isDefault() { return isDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Address other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
