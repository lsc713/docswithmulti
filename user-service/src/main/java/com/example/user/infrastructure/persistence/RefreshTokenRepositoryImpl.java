package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.RefreshTokenRepository;
import com.example.user.domain.entity.RefreshToken;

import java.util.Optional;

public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpa;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return jpa.findByToken(token).map(RefreshTokenJpaEntity::toDomain);
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        return jpa.save(RefreshTokenJpaEntity.from(refreshToken)).toDomain();
    }

    @Override
    public void deleteByUserId(long userId) {
        jpa.deleteByUserId(userId);
    }

    @Override
    public void deleteByToken(String token) {
        jpa.deleteByToken(token);
    }
}
