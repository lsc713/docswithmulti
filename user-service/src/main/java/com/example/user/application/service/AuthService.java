package com.example.user.application.service;

import com.example.user.common.exception.application.DuplicateEmailException;
import com.example.user.common.exception.application.InvalidTokenException;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.RefreshTokenRepository;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AuthUseCase;
import com.example.user.common.exception.ErrorCode;
import com.example.user.domain.entity.RefreshToken;
import com.example.user.domain.entity.User;
import com.example.user.common.exception.domain.InvalidCredentialsException;
import com.example.user.infrastructure.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService implements AuthUseCase {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    @Transactional
    public TokenResult signup(SignupCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new DuplicateEmailException(command.email());
        }
        String hashedPassword = passwordEncoder.encode(command.password());
        User user = User.of(command.email(), hashedPassword, command.name(),
                command.phone(), command.role(), command.merchantId());
        User saved = userRepository.save(user);
        return createTokens(saved);
    }

    @Override
    @Transactional
    public TokenResult login(LoginCommand command) {
        User user = userRepository.findByEmail(command.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(command.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }
        user.validateActive();
        refreshTokenRepository.deleteByUserId(user.getId());
        return createTokens(user);
    }

    @Override
    @Transactional(readOnly = true)
    public String refresh(String refreshToken) {
        RefreshToken rt = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(InvalidTokenException::new);
        if (rt.isExpired()) {
            throw new InvalidTokenException(ErrorCode.EXPIRED_REFRESH_TOKEN);
        }
        User user = userRepository.findById(rt.getUserId())
                .orElseThrow(() -> new UserNotFoundException(rt.getUserId()));
        return jwtTokenProvider.createAccessToken(user.getId(), user.getRole(), user.getMerchantId());
    }

    @Override
    @Transactional
    public void logout(long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private TokenResult createTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole(), user.getMerchantId());
        String refreshTokenValue = jwtTokenProvider.createRefreshToken();
        Instant expiresAt = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpiry());
        RefreshToken refreshToken = RefreshToken.of(user.getId(), refreshTokenValue, expiresAt);
        refreshTokenRepository.save(refreshToken);
        return new TokenResult(accessToken, refreshTokenValue);
    }
}
