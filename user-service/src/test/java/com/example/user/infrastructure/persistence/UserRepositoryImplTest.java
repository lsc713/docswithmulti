package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserRepositoryImpl 통합 테스트")
class UserRepositoryImplTest extends AbstractRepositoryTest {
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("User 저장 후 email로 조회")
    void shouldSaveAndFindByEmail() {
        User user = User.of("test@example.com", "hashedPw", "홍길동", "010-1234-5678", UserRole.USER, null);
        User saved = userRepository.save(user);
        assertNotNull(saved.getId());
        Optional<User> found = userRepository.findByEmail("test@example.com");
        assertTrue(found.isPresent());
        assertEquals("홍길동", found.get().getName());
    }

    @Test
    @DisplayName("existsByEmail — 존재하면 true")
    void shouldReturnTrueForExistingEmail() {
        userRepository.save(User.of("exists@example.com", "pw", "이름", "010-0000-0000", UserRole.USER, null));
        assertTrue(userRepository.existsByEmail("exists@example.com"));
        assertFalse(userRepository.existsByEmail("notexists@example.com"));
    }
}
