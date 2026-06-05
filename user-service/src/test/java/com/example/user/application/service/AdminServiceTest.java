package com.example.user.application.service;

import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService")
class AdminServiceTest {
    @Mock UserRepository userRepository;
    private AdminService adminService;

    @BeforeEach
    void setUp() { adminService = new AdminService(userRepository); }

    private User activeUser() {
        return User.reconstruct(1L, "user@test.com", "hashedPw", "홍길동", "010-1234-5678",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test @DisplayName("getUsers — 전체 유저 목록 조회")
    void shouldGetAllUsers() {
        when(userRepository.findAll()).thenReturn(List.of(activeUser()));
        assertEquals(1, adminService.getUsers().size());
    }

    @Test @DisplayName("updateStatus — SUSPENDED 상태로 변경")
    void shouldSuspendUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        User result = adminService.updateStatus(1L, UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
    }

    @Test @DisplayName("updateStatus — ACTIVE 상태로 변경")
    void shouldActivateUser() {
        User suspended = User.reconstruct(1L, "user@test.com", "hashedPw", "홍길동", "010",
                UserRole.USER, null, UserStatus.SUSPENDED, Instant.now(), Instant.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(suspended));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        User result = adminService.updateStatus(1L, UserStatus.ACTIVE);
        assertEquals(UserStatus.ACTIVE, result.getStatus());
    }

    @Test @DisplayName("updateStatus — 없는 유저 → UserNotFoundException")
    void shouldThrowOnUpdateStatusNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> adminService.updateStatus(99L, UserStatus.SUSPENDED));
    }

    @Test @DisplayName("updateRole — 역할 변경")
    void shouldChangeRole() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        User result = adminService.updateRole(1L, UserRole.ADMIN);
        assertEquals(UserRole.ADMIN, result.getRole());
    }

    @Test @DisplayName("updateRole — 없는 유저 → UserNotFoundException")
    void shouldThrowOnUpdateRoleNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> adminService.updateRole(99L, UserRole.ADMIN));
    }
}
