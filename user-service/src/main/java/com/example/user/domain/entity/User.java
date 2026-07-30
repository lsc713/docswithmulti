package com.example.user.domain.entity;

import com.example.user.common.exception.domain.SuspendedAccountException;

import java.time.Instant;
import java.util.Objects;

public class User {

    private Long id;
    private String email;
    private String password;
    private String name;
    private String phone;
    private UserRole role;
    private Long merchantId;
    private UserStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    private User() {}

    public static User of(String email, String password, String name, String phone, UserRole role, Long merchantId) {
        User user = new User();
        user.email = email;
        user.password = password;
        user.name = name;
        user.phone = phone;
        user.role = role;
        user.merchantId = merchantId;
        user.status = UserStatus.ACTIVE;
        user.createdAt = Instant.now();
        user.updatedAt = Instant.now();
        return user;
    }

    public static User reconstruct(Long id, String email, String password, String name, String phone,
                                   UserRole role, Long merchantId, UserStatus status,
                                   Instant createdAt, Instant updatedAt) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.password = password;
        user.name = name;
        user.phone = phone;
        user.role = role;
        user.merchantId = merchantId;
        user.status = status;
        user.createdAt = createdAt;
        user.updatedAt = updatedAt;
        return user;
    }

    public void validateActive() {
        if (this.status == UserStatus.SUSPENDED) {
            throw new SuspendedAccountException();
        }
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newHashedPassword) {
        this.password = newHashedPassword;
        this.updatedAt = Instant.now();
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
        this.updatedAt = Instant.now();
    }

    // Getters
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public UserRole getRole() { return role; }
    public Long getMerchantId() { return merchantId; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(email, other.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email);
    }
}
