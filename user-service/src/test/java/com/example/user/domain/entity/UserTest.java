package com.example.user.domain.entity;

import com.example.user.common.exception.domain.SuspendedAccountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("User 도메인 엔티티")
class UserTest {

    @Nested
    @DisplayName("of() 팩토리 메서드")
    class CreateTests {
        @Test
        @DisplayName("USER 역할로 생성 — ACTIVE 상태, merchantId null")
        void shouldCreateUserWithActiveStatus() {
            User user = User.of("test@example.com", "hashedPw", "홍길동", "010-1234-5678", UserRole.USER, null);
            assertEquals("test@example.com", user.getEmail());
            assertEquals(UserRole.USER, user.getRole());
            assertEquals(UserStatus.ACTIVE, user.getStatus());
            assertNull(user.getMerchantId());
        }

        @Test
        @DisplayName("MERCHANT 역할로 생성 — merchantId 포함")
        void shouldCreateMerchantWithMerchantId() {
            User user = User.of("merchant@example.com", "hashedPw", "김상인", "010-9999-0000", UserRole.MERCHANT, 100L);
            assertEquals(UserRole.MERCHANT, user.getRole());
            assertEquals(100L, user.getMerchantId());
        }
    }

    @Nested
    @DisplayName("상태 전환")
    class StatusTransitionTests {
        @Test
        @DisplayName("ACTIVE → SUSPENDED")
        void shouldSuspend() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            user.suspend();
            assertEquals(UserStatus.SUSPENDED, user.getStatus());
        }

        @Test
        @DisplayName("SUSPENDED → ACTIVE")
        void shouldActivate() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            user.suspend();
            user.activate();
            assertEquals(UserStatus.ACTIVE, user.getStatus());
        }

        @Test
        @DisplayName("ACTIVE → WITHDRAWN")
        void shouldWithdraw() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            user.withdraw();
            assertEquals(UserStatus.WITHDRAWN, user.getStatus());
        }
    }

    @Nested
    @DisplayName("validateActive()")
    class ValidateActiveTests {
        @Test
        @DisplayName("ACTIVE 상태 — 예외 없음")
        void shouldPassWhenActive() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            assertDoesNotThrow(user::validateActive);
        }

        @Test
        @DisplayName("SUSPENDED 상태 — SuspendedAccountException")
        void shouldThrowWhenSuspended() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            user.suspend();
            assertThrows(SuspendedAccountException.class, user::validateActive);
        }
    }

    @Nested
    @DisplayName("프로필 수정")
    class UpdateProfileTests {
        @Test
        @DisplayName("이름과 전화번호 변경")
        void shouldUpdateProfile() {
            User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
            user.updateProfile("새이름", "010-1111-1111");
            assertEquals("새이름", user.getName());
            assertEquals("010-1111-1111", user.getPhone());
        }
    }

    @Test
    @DisplayName("비밀번호 변경")
    void shouldChangePassword() {
        User user = User.of("test@example.com", "oldPw", "이름", "010-0000-0000", UserRole.USER, null);
        user.changePassword("newHashedPw");
        assertEquals("newHashedPw", user.getPassword());
    }

    @Test
    @DisplayName("역할 변경")
    void shouldChangeRole() {
        User user = User.of("test@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null);
        user.changeRole(UserRole.ADMIN);
        assertEquals(UserRole.ADMIN, user.getRole());
    }
}
