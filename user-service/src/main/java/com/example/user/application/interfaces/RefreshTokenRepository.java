package com.example.user.application.interfaces;

import com.example.user.domain.entity.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByToken(String token);
    RefreshToken save(RefreshToken refreshToken);
    void deleteByUserId(long userId);
    void deleteByToken(String token);
}
