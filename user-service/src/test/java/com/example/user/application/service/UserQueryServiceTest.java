package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import com.example.user.presentation.dto.MeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
