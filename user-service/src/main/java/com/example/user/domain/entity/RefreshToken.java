package com.example.user.domain.entity;

import java.time.Instant;
import java.util.Objects;

public class RefreshToken {

    private Long id;
    private Long userId;
    private String token;
    private Instant expiresAt;
    private Instant createdAt;

    private RefreshToken() {}

    public static RefreshToken of(long userId, String token, Instant expiresAt) {
        RefreshToken rt = new RefreshToken();
        rt.userId = userId;
        rt.token = token;
        rt.expiresAt = expiresAt;
        rt.createdAt = Instant.now();
        return rt;
    }

    public static RefreshToken reconstruct(Long id, long userId, String token, Instant expiresAt, Instant createdAt) {
        RefreshToken rt = new RefreshToken();
        rt.id = id;
        rt.userId = userId;
        rt.token = token;
        rt.expiresAt = expiresAt;
        rt.createdAt = createdAt;
        return rt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    // Getters
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getToken() { return token; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, token);
    }
}
