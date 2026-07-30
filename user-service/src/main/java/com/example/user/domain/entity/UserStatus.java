package com.example.user.domain.entity;

public enum UserStatus {
    ACTIVE, SUSPENDED, WITHDRAWN;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
