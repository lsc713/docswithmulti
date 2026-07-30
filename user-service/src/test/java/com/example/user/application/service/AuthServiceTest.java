package com.example.user.application.service;

import com.example.user.common.exception.application.DuplicateEmailException;
import com.example.user.common.exception.application.InvalidTokenException;
import com.example.user.application.interfaces.*;
import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.*;
import com.example.user.domain.entity.*;
import com.example.user.common.exception.domain.InvalidCredentialsException;
import com.example.user.common.exception.domain.SuspendedAccountException;
import com.example.user.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider);
    }

    @Nested
    @DisplayName("signup")
    class SignupTests {
        @Test
        @DisplayName("정상 회원가입 — 토큰 반환")
        void shouldSignupAndReturnTokens() {
            when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
            when(passwordEncoder.encode("password")).thenReturn("hashedPw");
            User savedUser = User.reconstruct(1L, "new@test.com", "hashedPw", "이름", "010-0000-0000",
                    UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
            when(userRepository.save(any())).thenReturn(savedUser);
            when(jwtTokenProvider.createAccessToken(1L, UserRole.USER, null)).thenReturn("access-token");
            when(jwtTokenProvider.createRefreshToken()).thenReturn("refresh-token");
            when(jwtTokenProvider.getRefreshTokenExpiry()).thenReturn(604800000L);
            when(refreshTokenRepository.save(any())).thenReturn(
                    RefreshToken.of(1L, "refresh-token", Instant.now().plus(7, ChronoUnit.DAYS)));

            TokenResult result = authService.signup(
                    new SignupCommand("new@test.com", "password", "이름", "010-0000-0000", UserRole.USER, null));

            assertEquals("access-token", result.accessToken());
            assertEquals("refresh-token", result.refreshToken());
        }

        @Test
        @DisplayName("이메일 중복 — DuplicateEmailException")
        void shouldThrowOnDuplicateEmail() {
            when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);
            assertThrows(DuplicateEmailException.class, () ->
                    authService.signup(new SignupCommand("dup@test.com", "pw", "이름", "010", UserRole.USER, null)));
        }
    }

    @Nested
    @DisplayName("login")
    class LoginTests {
        @Test
        @DisplayName("정상 로그인 — 토큰 반환")
        void shouldLoginAndReturnTokens() {
            User user = User.reconstruct(1L, "user@test.com", "hashedPw", "이름", "010",
                    UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("password", "hashedPw")).thenReturn(true);
            when(jwtTokenProvider.createAccessToken(1L, UserRole.USER, null)).thenReturn("access");
            when(jwtTokenProvider.createRefreshToken()).thenReturn("refresh");
            when(jwtTokenProvider.getRefreshTokenExpiry()).thenReturn(604800000L);
            when(refreshTokenRepository.save(any())).thenReturn(
                    RefreshToken.of(1L, "refresh", Instant.now().plus(7, ChronoUnit.DAYS)));

            TokenResult result = authService.login(new LoginCommand("user@test.com", "password"));
            assertEquals("access", result.accessToken());
        }

        @Test
        @DisplayName("비밀번호 불일치 — InvalidCredentialsException")
        void shouldThrowOnWrongPassword() {
            User user = User.reconstruct(1L, "user@test.com", "hashedPw", "이름", "010",
                    UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashedPw")).thenReturn(false);
            assertThrows(InvalidCredentialsException.class, () ->
                    authService.login(new LoginCommand("user@test.com", "wrong")));
        }

        @Test
        @DisplayName("정지된 계정 — SuspendedAccountException")
        void shouldThrowOnSuspendedAccount() {
            User user = User.reconstruct(1L, "user@test.com", "hashedPw", "이름", "010",
                    UserRole.USER, null, UserStatus.SUSPENDED, Instant.now(), Instant.now());
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("password", "hashedPw")).thenReturn(true);
            assertThrows(SuspendedAccountException.class, () ->
                    authService.login(new LoginCommand("user@test.com", "password")));
        }
    }

    @Nested
    @DisplayName("refresh")
    class RefreshTests {
        @Test
        @DisplayName("유효한 리프레시 토큰 — 새 Access Token 반환")
        void shouldRefreshAccessToken() {
            RefreshToken rt = RefreshToken.reconstruct(1L, 1L, "valid-refresh",
                    Instant.now().plus(1, ChronoUnit.DAYS), Instant.now());
            User user = User.reconstruct(1L, "user@test.com", "pw", "이름", "010",
                    UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
            when(refreshTokenRepository.findByToken("valid-refresh")).thenReturn(Optional.of(rt));
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(jwtTokenProvider.createAccessToken(1L, UserRole.USER, null)).thenReturn("new-access");

            String newToken = authService.refresh("valid-refresh");
            assertEquals("new-access", newToken);
        }

        @Test
        @DisplayName("만료된 리프레시 토큰 — InvalidTokenException")
        void shouldThrowOnExpiredRefreshToken() {
            RefreshToken rt = RefreshToken.reconstruct(1L, 1L, "expired",
                    Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());
            when(refreshTokenRepository.findByToken("expired")).thenReturn(Optional.of(rt));
            assertThrows(InvalidTokenException.class, () -> authService.refresh("expired"));
        }
    }

    @Test
    @DisplayName("logout — 리프레시 토큰 삭제")
    void shouldDeleteRefreshTokensOnLogout() {
        authService.logout(1L);
        verify(refreshTokenRepository).deleteByUserId(1L);
    }
}
