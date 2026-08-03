package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import com.example.user.presentation.dto.MeResponse;
import com.example.user.presentation.dto.UserListResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {
    @Mock UserRepository userRepository;

    @Test
    void getProfile_mapsUserToMeResponse() {
        User u = User.reconstruct(42L, "a@b.com", "hash", "홍길동", "010", UserRole.USER, null,
                UserStatus.ACTIVE, null, null);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        MeResponse r = new UserQueryService(userRepository).getProfile(42L);

        assertThat(r.userId()).isEqualTo(42L);
        assertThat(r.email()).isEqualTo("a@b.com");
        assertThat(r.name()).isEqualTo("홍길동");
        assertThat(r.role()).isEqualTo("USER");
    }

    @Test
    void getProfile_userNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new UserQueryService(userRepository).getProfile(99L))
                .isInstanceOf(com.example.user.common.exception.application.UserNotFoundException.class);
    }

    @Test
    @DisplayName("listUsers — id 오름차순 첫 페이지 + totalElements")
    void shouldListUsersPaged() {
        User u1 = User.reconstruct(1L, "a@x.com", "pw", "A", "010", UserRole.USER,
                null, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        User u2 = User.reconstruct(2L, "b@x.com", "pw", "B", "010", UserRole.ADMIN,
                null, UserStatus.ACTIVE, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        when(userRepository.findAll()).thenReturn(List.of(u2, u1));

        UserListResponse res = new UserQueryService(userRepository).listUsers(0, 1);

        assertThat(res.totalElements()).isEqualTo(2);
        assertThat(res.page()).isEqualTo(0);
        assertThat(res.size()).isEqualTo(1);
        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).id()).isEqualTo(1L);      // id 오름차순
        assertThat(res.content().get(0).role()).isEqualTo("USER");
        assertThat(res.content().get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("listUsers — 두 번째 페이지")
    void shouldReturnSecondPage() {
        User u1 = User.reconstruct(1L, "a@x.com", "pw", "A", "010", UserRole.USER,
                null, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        User u2 = User.reconstruct(2L, "b@x.com", "pw", "B", "010", UserRole.ADMIN,
                null, UserStatus.ACTIVE, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        when(userRepository.findAll()).thenReturn(List.of(u1, u2));

        UserListResponse res = new UserQueryService(userRepository).listUsers(1, 1);

        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).id()).isEqualTo(2L);
    }

    @Test
    @DisplayName("listUsers — 범위 초과 페이지는 빈 목록")
    void shouldReturnEmptyWhenPageOutOfRange() {
        when(userRepository.findAll()).thenReturn(List.of());

        UserListResponse res = new UserQueryService(userRepository).listUsers(5, 20);

        assertThat(res.content()).isEmpty();
        assertThat(res.totalElements()).isZero();
    }

    @Test
    @DisplayName("listUsers — 음수 page는 예외(500) 대신 빈 목록")
    void shouldReturnEmptyWhenPageNegative() {
        when(userRepository.findAll()).thenReturn(List.of());

        UserListResponse res = new UserQueryService(userRepository).listUsers(-1, 20);

        assertThat(res.content()).isEmpty();
        assertThat(res.totalElements()).isZero();
    }

    @Test
    @DisplayName("listUsers — 음수 size는 예외 대신 빈 목록 (500 방지)")
    void shouldReturnEmptyWhenSizeNegative() {
        User u1 = User.reconstruct(1L, "a@x.com", "pw", "A", "010", UserRole.USER,
                null, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findAll()).thenReturn(List.of(u1));

        UserListResponse res = new UserQueryService(userRepository).listUsers(0, -5);

        assertThat(res.content()).isEmpty();
        assertThat(res.totalElements()).isEqualTo(1);
    }
}
