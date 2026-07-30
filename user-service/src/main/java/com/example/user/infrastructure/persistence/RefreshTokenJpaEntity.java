package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.RefreshToken;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 512, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected RefreshTokenJpaEntity() {}

    public static RefreshTokenJpaEntity from(RefreshToken refreshToken) {
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        entity.id = refreshToken.getId();
        entity.userId = refreshToken.getUserId();
        entity.token = refreshToken.getToken();
        entity.expiresAt = LocalDateTime.ofInstant(refreshToken.getExpiresAt(), ZoneOffset.UTC);
        entity.createdAt = LocalDateTime.ofInstant(refreshToken.getCreatedAt(), ZoneOffset.UTC);
        return entity;
    }

    public RefreshToken toDomain() {
        return RefreshToken.reconstruct(
                id, userId, token,
                expiresAt.toInstant(ZoneOffset.UTC),
                createdAt.toInstant(ZoneOffset.UTC)
        );
    }
}
