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
import com.example.user.domain.entity.UserRole;
import com.example.user.infrastructure.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService implements AuthUseCase {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    // 첫 ADMIN 부트스트랩: 서버 설정(app.admin.bootstrap-emails)만이 결정 — 클라 role 입력은 여전히 무시(D-P1-2).
    // 콤마 분리 후 각 엔트리 trim + 빈 값 제거 (예: "a@x.com, b@x.com" → {"a@x.com","b@x.com"}).
    private final Set<String> bootstrapEmails;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                        PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                        @Value("${app.admin.bootstrap-emails:}") List<String> bootstrapEmails) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.bootstrapEmails = bootstrapEmails.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public TokenResult signup(SignupCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new DuplicateEmailException(command.email());
        }
        String hashedPassword = passwordEncoder.encode(command.password());
        // D-P1-2: command.role()(클라 유래)는 신뢰하지 않는다 — 서버 설정(bootstrapEmails)만이 ADMIN 여부를 결정.
        UserRole role = bootstrapEmails.contains(command.email()) ? UserRole.ADMIN : UserRole.USER;
        User user = User.of(command.email(), hashedPassword, command.name(),
                command.phone(), role, command.merchantId());
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
