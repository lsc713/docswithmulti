package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AdminUseCase;
import com.example.user.application.usecase.AdminUseCase.RoleChangeResult;
import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService")
class AdminServiceTest {

    @Mock UserRepository userRepository;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(userRepository);
    }

    @Test
    @DisplayName("changeRole — 대상 유저 역할 변경 후 저장")
    void shouldChangeRole() {
        User user = User.reconstruct(1L, "user@test.com", "pw", "이름", "010",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(captor.capture())).thenAnswer(inv -> captor.getValue());

        RoleChangeResult result = adminService.changeRole(1L, UserRole.ADMIN);

        assertEquals(1L, result.userId());
        assertEquals(UserRole.ADMIN, result.role());
        assertEquals(UserRole.ADMIN, captor.getValue().getRole());
    }

    @Test
    @DisplayName("changeRole — 존재하지 않는 유저 id — UserNotFoundException")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> adminService.changeRole(999L, UserRole.ADMIN));
    }
}
