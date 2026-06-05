package com.example.user.application.service;

import com.example.user.common.exception.application.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserUseCase.*;
import com.example.user.domain.entity.*;
import com.example.user.common.exception.domain.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() { userService = new UserService(userRepository, passwordEncoder); }

    private User activeUser() {
        return User.reconstruct(1L, "user@test.com", "hashedPw", "홍길동", "010-1234-5678",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test @DisplayName("getMe — 유저 조회")
    void shouldGetUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        assertEquals("홍길동", userService.getMe(1L).getName());
    }

    @Test @DisplayName("getMe — 없는 유저 → UserNotFoundException")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> userService.getMe(99L));
    }

    @Test @DisplayName("update — 이름/전화번호 변경")
    void shouldUpdateProfile() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals("새이름", userService.update(1L, new UpdateCommand("새이름", "010-9999-0000")).getName());
    }

    @Test @DisplayName("changePassword — 현재 비밀번호 일치 시 변경")
    void shouldChangePassword() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("currentPw", "hashedPw")).thenReturn(true);
        when(passwordEncoder.encode("newPw")).thenReturn("newHashedPw");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        userService.changePassword(1L, new ChangePasswordCommand("currentPw", "newPw"));
        verify(userRepository).save(argThat(u -> u.getPassword().equals("newHashedPw")));
    }

    @Test @DisplayName("changePassword — 불일치 → InvalidCredentialsException")
    void shouldThrowOnWrongCurrentPassword() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(passwordEncoder.matches("wrong", "hashedPw")).thenReturn(false);
        assertThrows(InvalidCredentialsException.class, () ->
                userService.changePassword(1L, new ChangePasswordCommand("wrong", "newPw")));
    }

    @Test @DisplayName("withdraw — WITHDRAWN 상태 전환")
    void shouldWithdraw() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        userService.withdraw(1L);
        verify(userRepository).save(argThat(u -> u.getStatus() == UserStatus.WITHDRAWN));
    }
}
