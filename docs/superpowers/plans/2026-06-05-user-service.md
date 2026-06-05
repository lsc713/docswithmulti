# user-service 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유저 도메인 모듈(회원가입/로그인/JWT/프로필/배송지/결제수단)을 추가하고, payment-service에 JWT 기반 인가를 적용한다.

**Architecture:** user-service는 기존 payment-service와 동일한 레이어드 아키텍처(presentation → application → domain ← infrastructure)를 따른다. 도메인 엔티티와 JPA 엔티티를 분리하고, repository 인터페이스는 application에, 구현체는 infrastructure에 둔다. JWT는 HMAC-SHA256 공유 시크릿 방식으로 user-service가 발급하고 payment-service가 검증한다.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Security, Spring Data JPA, Flyway, MySQL 8.0, jjwt 0.12.x, BCrypt, JUnit 5 + Mockito + Testcontainers

**Spec:** `docs/superpowers/specs/2026-06-05-user-service-design.md`

---

## 파일 구조

### 신규 생성 (user-service)

```
user-service/
├── build.gradle
├── src/main/java/com/example/user/
│   ├── UserServiceApplication.java
│   ├── common/exception/
│   │   ├── BusinessException.java
│   │   └── ErrorCode.java
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── User.java
│   │   │   ├── UserRole.java
│   │   │   ├── UserStatus.java
│   │   │   ├── Address.java
│   │   │   ├── PaymentMethod.java
│   │   │   ├── PaymentMethodType.java
│   │   │   └── RefreshToken.java
│   │   └── exception/
│   │       ├── SuspendedAccountException.java
│   │       └── InvalidCredentialsException.java
│   ├── application/
│   │   ├── usecase/
│   │   │   ├── AuthUseCase.java
│   │   │   ├── UserUseCase.java
│   │   │   ├── AddressUseCase.java
│   │   │   ├── PaymentMethodUseCase.java
│   │   │   └── AdminUseCase.java
│   │   ├── service/
│   │   │   ├── AuthService.java
│   │   │   ├── UserService.java
│   │   │   ├── AddressService.java
│   │   │   ├── PaymentMethodService.java
│   │   │   └── AdminService.java
│   │   ├── interfaces/
│   │   │   ├── UserRepository.java
│   │   │   ├── AddressRepository.java
│   │   │   ├── PaymentMethodRepository.java
│   │   │   ├── RefreshTokenRepository.java
│   │   │   └── PasswordEncoder.java
│   │   └── exception/
│   │       ├── UserNotFoundException.java
│   │       ├── DuplicateEmailException.java
│   │       ├── AddressNotFoundException.java
│   │       ├── PaymentMethodNotFoundException.java
│   │       └── InvalidTokenException.java
│   ├── infrastructure/
│   │   ├── persistence/
│   │   │   ├── UserJpaEntity.java
│   │   │   ├── UserJpaRepository.java
│   │   │   ├── UserRepositoryImpl.java
│   │   │   ├── AddressJpaEntity.java
│   │   │   ├── AddressJpaRepository.java
│   │   │   ├── AddressRepositoryImpl.java
│   │   │   ├── PaymentMethodJpaEntity.java
│   │   │   ├── PaymentMethodJpaRepository.java
│   │   │   ├── PaymentMethodRepositoryImpl.java
│   │   │   ├── RefreshTokenJpaEntity.java
│   │   │   ├── RefreshTokenJpaRepository.java
│   │   │   └── RefreshTokenRepositoryImpl.java
│   │   ├── security/
│   │   │   ├── JwtTokenProvider.java
│   │   │   ├── JwtAuthenticationFilter.java
│   │   │   ├── SecurityConfig.java
│   │   │   └── BcryptPasswordEncoderAdapter.java
│   │   └── config/
│   │       └── PersistenceConfig.java
│   └── presentation/
│       ├── controller/
│       │   ├── AuthController.java
│       │   ├── UserController.java
│       │   ├── AddressController.java
│       │   ├── PaymentMethodController.java
│       │   └── AdminController.java
│       ├── dto/
│       │   ├── SignupRequest.java
│       │   ├── LoginRequest.java
│       │   ├── TokenResponse.java
│       │   ├── RefreshRequest.java
│       │   ├── UserResponse.java
│       │   ├── UpdateUserRequest.java
│       │   ├── ChangePasswordRequest.java
│       │   ├── AddressRequest.java
│       │   ├── AddressResponse.java
│       │   ├── PaymentMethodRequest.java
│       │   ├── PaymentMethodResponse.java
│       │   ├── UpdateUserStatusRequest.java
│       │   ├── UpdateUserRoleRequest.java
│       │   └── AdminUserResponse.java
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__create_user_core.sql
└── src/test/java/com/example/user/
    ├── domain/entity/
    │   ├── UserTest.java
    │   ├── AddressTest.java
    │   ├── PaymentMethodTest.java
    │   └── RefreshTokenTest.java
    ├── application/service/
    │   ├── AuthServiceTest.java
    │   ├── UserServiceTest.java
    │   ├── AddressServiceTest.java
    │   ├── PaymentMethodServiceTest.java
    │   └── AdminServiceTest.java
    ├── infrastructure/
    │   ├── persistence/
    │   │   ├── AbstractRepositoryTest.java
    │   │   ├── UserRepositoryImplTest.java
    │   │   ├── AddressRepositoryImplTest.java
    │   │   ├── PaymentMethodRepositoryImplTest.java
    │   │   └── RefreshTokenRepositoryImplTest.java
    │   └── security/
    │       └── JwtTokenProviderTest.java
    └── presentation/controller/
        ├── AuthControllerTest.java
        ├── UserControllerTest.java
        ├── AddressControllerTest.java
        ├── PaymentMethodControllerTest.java
        └── AdminControllerTest.java
```

### 수정 (payment-service)

```
payment-service/
├── build.gradle                                    # Spring Security + jjwt 의존성 추가
├── src/main/java/com/example/payment/
│   ├── common/exception/ErrorCode.java             # PAY_AUTH_001, PAY_AUTH_002 추가
│   ├── infrastructure/
│   │   ├── security/
│   │   │   ├── JwtAuthenticationFilter.java        # 신규
│   │   │   ├── SecurityConfig.java                 # 신규
│   │   │   └── AuthenticatedUser.java              # 신규 (SecurityContext에 저장되는 VO)
│   │   └── config/PersistenceConfig.java           # 변경 없음
│   └── presentation/controller/CancelController.java  # 인가 검증 추가
└── src/main/resources/application.yml              # jwt.secret 추가
```

---

## Task 1: 모듈 스캐폴딩

**Files:**
- Modify: `settings.gradle`
- Create: `user-service/build.gradle`
- Create: `user-service/src/main/java/com/example/user/UserServiceApplication.java`
- Create: `user-service/src/main/resources/application.yml`
- Create: `user-service/src/main/resources/db/migration/V1__create_user_core.sql`

- [ ] **Step 1: settings.gradle에 user-service 모듈 추가**

```gradle
// settings.gradle — 마지막 줄에 추가
include 'user-service'
```

- [ ] **Step 2: build.gradle 작성**

```gradle
// user-service/build.gradle

apply plugin: 'org.flywaydb.flyway'
apply plugin: 'jacoco'

flyway {
    url      = 'jdbc:mysql://localhost:3315/user_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
    user     = 'user'
    password = 'user'
    locations = ['classpath:db/migration']
}

jacoco {
    toolVersion = '0.8.12'
}

test {
    finalizedBy jacocoTestReport
}

jacocoTestReport {
    dependsOn test
    reports {
        html.required = true
        html.outputLocation = layout.buildDirectory.dir('reports/jacoco/html')
        xml.required = false
    }
}

dependencies {
    // Spring Security
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // JWT (jjwt)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // Test — Spring Security
    testImplementation 'org.springframework.security:spring-security-test'
}
```

- [ ] **Step 3: Application main class 작성**

```java
// user-service/src/main/java/com/example/user/UserServiceApplication.java
package com.example.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: application.yml 작성**

```yaml
# user-service/src/main/resources/application.yml
spring:
  application:
    name: user-service

  datasource:
    url: jdbc:mysql://localhost:3315/user_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true
    username: user
    password: user
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-timeout: 30000
      initialization-fail-timeout: -1

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect

  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: false

  profiles:
    active: local

server:
  port: 8085

jwt:
  secret: ${JWT_SECRET:default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}
  access-token-expiry: 3600000    # 1시간 (ms)
  refresh-token-expiry: 604800000 # 7일 (ms)

logging:
  level:
    com.example.user: INFO
```

- [ ] **Step 5: Flyway DDL 작성**

```sql
-- user-service/src/main/resources/db/migration/V1__create_user_core.sql

CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    merchant_id BIGINT       NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_users_merchant_id (merchant_id),
    INDEX idx_users_status (status)
);

CREATE TABLE addresses (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    label          VARCHAR(50)  NOT NULL,
    recipient      VARCHAR(50)  NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    zip_code       VARCHAR(10)  NOT NULL,
    address        VARCHAR(255) NOT NULL,
    address_detail VARCHAR(255) NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_addresses_user_id (user_id),
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE payment_methods (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    type           VARCHAR(20)  NOT NULL,
    card_number    VARCHAR(20)  NULL,
    card_company   VARCHAR(50)  NULL,
    bank_name      VARCHAR(50)  NULL,
    account_number VARCHAR(30)  NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_payment_methods_user_id (user_id),
    CONSTRAINT fk_payment_methods_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE refresh_tokens (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    token      VARCHAR(512) NOT NULL UNIQUE,
    expires_at DATETIME(6)  NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_refresh_tokens_user_id (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
```

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew :user-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add settings.gradle user-service/
git commit -m "feat(user): 모듈 스캐폴딩 — build.gradle, application.yml, Flyway DDL"
```

---

## Task 2: Common 예외 계층 + 도메인 Enum

**Files:**
- Create: `user-service/src/main/java/com/example/user/common/exception/BusinessException.java`
- Create: `user-service/src/main/java/com/example/user/common/exception/ErrorCode.java`
- Create: `user-service/src/main/java/com/example/user/domain/entity/UserRole.java`
- Create: `user-service/src/main/java/com/example/user/domain/entity/UserStatus.java`
- Create: `user-service/src/main/java/com/example/user/domain/entity/PaymentMethodType.java`
- Create: `user-service/src/main/java/com/example/user/domain/exception/SuspendedAccountException.java`
- Create: `user-service/src/main/java/com/example/user/domain/exception/InvalidCredentialsException.java`
- Create: `user-service/src/main/java/com/example/user/application/exception/UserNotFoundException.java`
- Create: `user-service/src/main/java/com/example/user/application/exception/DuplicateEmailException.java`
- Create: `user-service/src/main/java/com/example/user/application/exception/AddressNotFoundException.java`
- Create: `user-service/src/main/java/com/example/user/application/exception/PaymentMethodNotFoundException.java`
- Create: `user-service/src/main/java/com/example/user/application/exception/InvalidTokenException.java`

- [ ] **Step 1: BusinessException 작성**

```java
package com.example.user.common.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

- [ ] **Step 2: ErrorCode 작성**

```java
package com.example.user.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    INVALID_REQUEST("INVALID_REQUEST", 400, "요청 형식이 올바르지 않습니다."),

    // 401
    INVALID_CREDENTIALS("USER_002", 401, "이메일 또는 비밀번호가 일치하지 않습니다."),
    EXPIRED_TOKEN("USER_005", 401, "만료된 토큰입니다."),
    INVALID_TOKEN("USER_006", 401, "유효하지 않은 토큰입니다."),
    EXPIRED_REFRESH_TOKEN("USER_007", 401, "만료된 리프레시 토큰입니다."),

    // 403
    SUSPENDED_ACCOUNT("USER_004", 403, "정지된 계정입니다."),
    FORBIDDEN("USER_010", 403, "권한이 없습니다."),

    // 404
    USER_NOT_FOUND("USER_003", 404, "유저를 찾을 수 없습니다."),
    ADDRESS_NOT_FOUND("USER_008", 404, "배송지를 찾을 수 없습니다."),
    PAYMENT_METHOD_NOT_FOUND("USER_009", 404, "결제수단을 찾을 수 없습니다."),

    // 409
    DUPLICATE_EMAIL("USER_001", 409, "이미 등록된 이메일입니다."),

    // 500
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "서버 오류가 발생했습니다.");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;
}
```

- [ ] **Step 3: 도메인 Enum 작성**

```java
// UserRole.java
package com.example.user.domain.entity;

public enum UserRole {
    USER, MERCHANT, ADMIN
}
```

```java
// UserStatus.java
package com.example.user.domain.entity;

public enum UserStatus {
    ACTIVE, SUSPENDED, WITHDRAWN;

    public boolean isActive() {
        return this == ACTIVE;
    }
}
```

```java
// PaymentMethodType.java
package com.example.user.domain.entity;

public enum PaymentMethodType {
    CARD, BANK_TRANSFER, VIRTUAL_ACCOUNT
}
```

- [ ] **Step 4: 도메인 예외 작성**

```java
// SuspendedAccountException.java
package com.example.user.domain.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class SuspendedAccountException extends BusinessException {
    public SuspendedAccountException() {
        super(ErrorCode.SUSPENDED_ACCOUNT);
    }
}
```

```java
// InvalidCredentialsException.java
package com.example.user.domain.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS);
    }
}
```

- [ ] **Step 5: 애플리케이션 예외 작성**

```java
// UserNotFoundException.java
package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class UserNotFoundException extends BusinessException {
    public UserNotFoundException(long userId) {
        super(ErrorCode.USER_NOT_FOUND,
              String.format("유저를 찾을 수 없습니다. (userId: %d)", userId));
    }
}
```

```java
// DuplicateEmailException.java
package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super(ErrorCode.DUPLICATE_EMAIL,
              String.format("이미 등록된 이메일입니다. (email: %s)", email));
    }
}
```

```java
// AddressNotFoundException.java
package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class AddressNotFoundException extends BusinessException {
    public AddressNotFoundException(long addressId) {
        super(ErrorCode.ADDRESS_NOT_FOUND,
              String.format("배송지를 찾을 수 없습니다. (addressId: %d)", addressId));
    }
}
```

```java
// PaymentMethodNotFoundException.java
package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class PaymentMethodNotFoundException extends BusinessException {
    public PaymentMethodNotFoundException(long paymentMethodId) {
        super(ErrorCode.PAYMENT_METHOD_NOT_FOUND,
              String.format("결제수단을 찾을 수 없습니다. (paymentMethodId: %d)", paymentMethodId));
    }
}
```

```java
// InvalidTokenException.java
package com.example.user.application.exception;

import com.example.user.common.exception.BusinessException;
import com.example.user.common.exception.ErrorCode;

public class InvalidTokenException extends BusinessException {
    public InvalidTokenException() {
        super(ErrorCode.INVALID_TOKEN);
    }

    public InvalidTokenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew :user-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add user-service/src/main/java/com/example/user/common/ \
        user-service/src/main/java/com/example/user/domain/entity/UserRole.java \
        user-service/src/main/java/com/example/user/domain/entity/UserStatus.java \
        user-service/src/main/java/com/example/user/domain/entity/PaymentMethodType.java \
        user-service/src/main/java/com/example/user/domain/exception/ \
        user-service/src/main/java/com/example/user/application/exception/
git commit -m "feat(user): ErrorCode, BusinessException, 도메인/애플리케이션 예외 계층"
```

---

## Task 3: User 도메인 엔티티

**Files:**
- Create: `user-service/src/main/java/com/example/user/domain/entity/User.java`
- Test: `user-service/src/test/java/com/example/user/domain/entity/UserTest.java`

- [ ] **Step 1: User 테스트 작성**

```java
package com.example.user.domain.entity;

import com.example.user.domain.exception.SuspendedAccountException;
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
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.UserTest" -i`
Expected: FAIL — `User` 클래스 없음

- [ ] **Step 3: User 도메인 엔티티 구현**

```java
package com.example.user.domain.entity;

import com.example.user.domain.exception.SuspendedAccountException;
import java.time.Instant;
import java.util.Objects;

public class User {

    private Long id;
    private String email;
    private String password;
    private String name;
    private String phone;
    private UserRole role;
    private Long merchantId;
    private UserStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    private User(String email, String password, String name, String phone,
                 UserRole role, Long merchantId) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.merchantId = merchantId;
        this.status = UserStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static User of(String email, String password, String name, String phone,
                           UserRole role, Long merchantId) {
        return new User(email, password, name, phone, role, merchantId);
    }

    public static User reconstruct(Long id, String email, String password, String name,
                                    String phone, UserRole role, Long merchantId,
                                    UserStatus status, Instant createdAt, Instant updatedAt) {
        User user = new User(email, password, name, phone, role, merchantId);
        user.id = id;
        user.status = status;
        user.createdAt = createdAt;
        user.updatedAt = updatedAt;
        return user;
    }

    public void validateActive() {
        if (!status.isActive()) {
            throw new SuspendedAccountException();
        }
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newHashedPassword) {
        this.password = newHashedPassword;
        this.updatedAt = Instant.now();
    }

    public void changeRole(UserRole newRole) {
        this.role = newRole;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public UserRole getRole() { return role; }
    public Long getMerchantId() { return merchantId; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(id, user.id) && Objects.equals(email, user.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email);
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.UserTest" -i`
Expected: PASS (모든 테스트)

- [ ] **Step 5: 커밋**

```bash
git add user-service/src/main/java/com/example/user/domain/entity/User.java \
        user-service/src/test/java/com/example/user/domain/entity/UserTest.java
git commit -m "feat(user): User 도메인 엔티티 + TDD 단위 테스트"
```

---

## Task 4: Address 도메인 엔티티

**Files:**
- Create: `user-service/src/main/java/com/example/user/domain/entity/Address.java`
- Test: `user-service/src/test/java/com/example/user/domain/entity/AddressTest.java`

- [ ] **Step 1: Address 테스트 작성**

```java
package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Address 도메인 엔티티")
class AddressTest {

    @Test
    @DisplayName("배송지 생성")
    void shouldCreateAddress() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678",
                "06123", "서울시 강남구", "101호", false);

        assertEquals(1L, address.getUserId());
        assertEquals("집", address.getLabel());
        assertEquals("홍길동", address.getRecipient());
        assertFalse(address.isDefault());
    }

    @Test
    @DisplayName("배송지 정보 수정")
    void shouldUpdateAddress() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678",
                "06123", "서울시 강남구", "101호", false);

        address.update("회사", "김철수", "010-9999-0000", "03123", "서울시 종로구", "5층", true);

        assertEquals("회사", address.getLabel());
        assertEquals("김철수", address.getRecipient());
        assertEquals("010-9999-0000", address.getPhone());
        assertTrue(address.isDefault());
    }

    @Test
    @DisplayName("기본 배송지 해제")
    void shouldClearDefault() {
        Address address = Address.of(1L, "집", "홍길동", "010-1234-5678",
                "06123", "서울시 강남구", "101호", true);
        address.clearDefault();
        assertFalse(address.isDefault());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.AddressTest" -i`
Expected: FAIL

- [ ] **Step 3: Address 도메인 엔티티 구현**

```java
package com.example.user.domain.entity;

import java.time.Instant;

public class Address {

    private Long id;
    private long userId;
    private String label;
    private String recipient;
    private String phone;
    private String zipCode;
    private String address;
    private String addressDetail;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    private Address(long userId, String label, String recipient, String phone,
                    String zipCode, String address, String addressDetail, boolean isDefault) {
        this.userId = userId;
        this.label = label;
        this.recipient = recipient;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.isDefault = isDefault;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static Address of(long userId, String label, String recipient, String phone,
                              String zipCode, String address, String addressDetail, boolean isDefault) {
        return new Address(userId, label, recipient, phone, zipCode, address, addressDetail, isDefault);
    }

    public static Address reconstruct(Long id, long userId, String label, String recipient,
                                       String phone, String zipCode, String address,
                                       String addressDetail, boolean isDefault,
                                       Instant createdAt, Instant updatedAt) {
        Address a = new Address(userId, label, recipient, phone, zipCode, address, addressDetail, isDefault);
        a.id = id;
        a.createdAt = createdAt;
        a.updatedAt = updatedAt;
        return a;
    }

    public void update(String label, String recipient, String phone, String zipCode,
                       String address, String addressDetail, boolean isDefault) {
        this.label = label;
        this.recipient = recipient;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address = address;
        this.addressDetail = addressDetail;
        this.isDefault = isDefault;
        this.updatedAt = Instant.now();
    }

    public void clearDefault() {
        this.isDefault = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getRecipient() { return recipient; }
    public String getPhone() { return phone; }
    public String getZipCode() { return zipCode; }
    public String getAddress() { return address; }
    public String getAddressDetail() { return addressDetail; }
    public boolean isDefault() { return isDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.AddressTest" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add user-service/src/main/java/com/example/user/domain/entity/Address.java \
        user-service/src/test/java/com/example/user/domain/entity/AddressTest.java
git commit -m "feat(user): Address 도메인 엔티티 + TDD 단위 테스트"
```

---

## Task 5: PaymentMethod 도메인 엔티티

**Files:**
- Create: `user-service/src/main/java/com/example/user/domain/entity/PaymentMethod.java`
- Test: `user-service/src/test/java/com/example/user/domain/entity/PaymentMethodTest.java`

- [ ] **Step 1: PaymentMethod 테스트 작성**

```java
package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentMethod 도메인 엔티티")
class PaymentMethodTest {

    @Test
    @DisplayName("카드 결제수단 생성")
    void shouldCreateCard() {
        PaymentMethod pm = PaymentMethod.ofCard(1L, "1234", "신한카드", true);

        assertEquals(PaymentMethodType.CARD, pm.getType());
        assertEquals("1234", pm.getCardNumber());
        assertEquals("신한카드", pm.getCardCompany());
        assertNull(pm.getBankName());
        assertTrue(pm.isDefault());
    }

    @Test
    @DisplayName("계좌이체 결제수단 생성")
    void shouldCreateBankTransfer() {
        PaymentMethod pm = PaymentMethod.ofBankTransfer(1L, "국민은행", "7890", false);

        assertEquals(PaymentMethodType.BANK_TRANSFER, pm.getType());
        assertEquals("국민은행", pm.getBankName());
        assertEquals("7890", pm.getAccountNumber());
        assertNull(pm.getCardNumber());
    }

    @Test
    @DisplayName("기본 결제수단 해제")
    void shouldClearDefault() {
        PaymentMethod pm = PaymentMethod.ofCard(1L, "1234", "신한카드", true);
        pm.clearDefault();
        assertFalse(pm.isDefault());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.PaymentMethodTest" -i`
Expected: FAIL

- [ ] **Step 3: PaymentMethod 도메인 엔티티 구현**

```java
package com.example.user.domain.entity;

import java.time.Instant;

public class PaymentMethod {

    private Long id;
    private long userId;
    private PaymentMethodType type;
    private String cardNumber;
    private String cardCompany;
    private String bankName;
    private String accountNumber;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    private PaymentMethod(long userId, PaymentMethodType type, String cardNumber,
                          String cardCompany, String bankName, String accountNumber,
                          boolean isDefault) {
        this.userId = userId;
        this.type = type;
        this.cardNumber = cardNumber;
        this.cardCompany = cardCompany;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.isDefault = isDefault;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static PaymentMethod ofCard(long userId, String cardNumber,
                                        String cardCompany, boolean isDefault) {
        return new PaymentMethod(userId, PaymentMethodType.CARD, cardNumber, cardCompany,
                null, null, isDefault);
    }

    public static PaymentMethod ofBankTransfer(long userId, String bankName,
                                                String accountNumber, boolean isDefault) {
        return new PaymentMethod(userId, PaymentMethodType.BANK_TRANSFER, null, null,
                bankName, accountNumber, isDefault);
    }

    public static PaymentMethod reconstruct(Long id, long userId, PaymentMethodType type,
                                             String cardNumber, String cardCompany,
                                             String bankName, String accountNumber,
                                             boolean isDefault, Instant createdAt, Instant updatedAt) {
        PaymentMethod pm = new PaymentMethod(userId, type, cardNumber, cardCompany,
                bankName, accountNumber, isDefault);
        pm.id = id;
        pm.createdAt = createdAt;
        pm.updatedAt = updatedAt;
        return pm;
    }

    public void clearDefault() {
        this.isDefault = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public PaymentMethodType getType() { return type; }
    public String getCardNumber() { return cardNumber; }
    public String getCardCompany() { return cardCompany; }
    public String getBankName() { return bankName; }
    public String getAccountNumber() { return accountNumber; }
    public boolean isDefault() { return isDefault; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.PaymentMethodTest" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add user-service/src/main/java/com/example/user/domain/entity/PaymentMethod.java \
        user-service/src/test/java/com/example/user/domain/entity/PaymentMethodTest.java
git commit -m "feat(user): PaymentMethod 도메인 엔티티 + TDD 단위 테스트"
```

---

## Task 6: RefreshToken 도메인 엔티티

**Files:**
- Create: `user-service/src/main/java/com/example/user/domain/entity/RefreshToken.java`
- Test: `user-service/src/test/java/com/example/user/domain/entity/RefreshTokenTest.java`

- [ ] **Step 1: RefreshToken 테스트 작성**

```java
package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RefreshToken 도메인 엔티티")
class RefreshTokenTest {

    @Test
    @DisplayName("리프레시 토큰 생성")
    void shouldCreate() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        RefreshToken token = RefreshToken.of(1L, "token-value", expiresAt);

        assertEquals(1L, token.getUserId());
        assertEquals("token-value", token.getToken());
        assertEquals(expiresAt, token.getExpiresAt());
    }

    @Test
    @DisplayName("만료되지 않은 토큰 — isExpired false")
    void shouldNotBeExpired() {
        RefreshToken token = RefreshToken.of(1L, "token", Instant.now().plus(1, ChronoUnit.HOURS));
        assertFalse(token.isExpired());
    }

    @Test
    @DisplayName("만료된 토큰 — isExpired true")
    void shouldBeExpired() {
        RefreshToken token = RefreshToken.of(1L, "token", Instant.now().minus(1, ChronoUnit.HOURS));
        assertTrue(token.isExpired());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.RefreshTokenTest" -i`
Expected: FAIL

- [ ] **Step 3: RefreshToken 도메인 엔티티 구현**

```java
package com.example.user.domain.entity;

import java.time.Instant;

public class RefreshToken {

    private Long id;
    private long userId;
    private String token;
    private Instant expiresAt;
    private Instant createdAt;

    private RefreshToken(long userId, String token, Instant expiresAt) {
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public static RefreshToken of(long userId, String token, Instant expiresAt) {
        return new RefreshToken(userId, token, expiresAt);
    }

    public static RefreshToken reconstruct(Long id, long userId, String token,
                                            Instant expiresAt, Instant createdAt) {
        RefreshToken rt = new RefreshToken(userId, token, expiresAt);
        rt.id = id;
        rt.createdAt = createdAt;
        return rt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getToken() { return token; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.domain.entity.RefreshTokenTest" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add user-service/src/main/java/com/example/user/domain/entity/RefreshToken.java \
        user-service/src/test/java/com/example/user/domain/entity/RefreshTokenTest.java
git commit -m "feat(user): RefreshToken 도메인 엔티티 + TDD 단위 테스트"
```

---

## Task 7: Repository 인터페이스 + PasswordEncoder 인터페이스

**Files:**
- Create: `user-service/src/main/java/com/example/user/application/interfaces/UserRepository.java`
- Create: `user-service/src/main/java/com/example/user/application/interfaces/AddressRepository.java`
- Create: `user-service/src/main/java/com/example/user/application/interfaces/PaymentMethodRepository.java`
- Create: `user-service/src/main/java/com/example/user/application/interfaces/RefreshTokenRepository.java`
- Create: `user-service/src/main/java/com/example/user/application/interfaces/PasswordEncoder.java`

- [ ] **Step 1: 모든 인터페이스 작성**

```java
// UserRepository.java
package com.example.user.application.interfaces;

import com.example.user.domain.entity.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(long id);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    User save(User user);
    List<User> findAll();
}
```

```java
// AddressRepository.java
package com.example.user.application.interfaces;

import com.example.user.domain.entity.Address;
import java.util.List;
import java.util.Optional;

public interface AddressRepository {
    List<Address> findAllByUserId(long userId);
    Optional<Address> findByIdAndUserId(long id, long userId);
    Address save(Address address);
    void deleteById(long id);
}
```

```java
// PaymentMethodRepository.java
package com.example.user.application.interfaces;

import com.example.user.domain.entity.PaymentMethod;
import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository {
    List<PaymentMethod> findAllByUserId(long userId);
    Optional<PaymentMethod> findByIdAndUserId(long id, long userId);
    PaymentMethod save(PaymentMethod paymentMethod);
    void deleteById(long id);
}
```

```java
// RefreshTokenRepository.java
package com.example.user.application.interfaces;

import com.example.user.domain.entity.RefreshToken;
import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByToken(String token);
    RefreshToken save(RefreshToken refreshToken);
    void deleteByUserId(long userId);
    void deleteByToken(String token);
}
```

```java
// PasswordEncoder.java
package com.example.user.application.interfaces;

public interface PasswordEncoder {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :user-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add user-service/src/main/java/com/example/user/application/interfaces/
git commit -m "feat(user): Repository + PasswordEncoder 인터페이스 정의"
```

---

## Task 8: JwtTokenProvider

**Files:**
- Create: `user-service/src/main/java/com/example/user/infrastructure/security/JwtTokenProvider.java`
- Test: `user-service/src/test/java/com/example/user/infrastructure/security/JwtTokenProviderTest.java`

- [ ] **Step 1: JwtTokenProvider 테스트 작성**

```java
package com.example.user.infrastructure.security;

import com.example.user.domain.entity.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // 테스트용 시크릿 (256비트 이상)
        String secret = "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo";
        provider = new JwtTokenProvider(secret, 3600000L, 604800000L);
    }

    @Test
    @DisplayName("USER 역할 — Access Token 생성 및 파싱")
    void shouldCreateAndParseUserToken() {
        String token = provider.createAccessToken(1L, UserRole.USER, null);

        assertTrue(provider.validateToken(token));
        assertEquals(1L, provider.getUserId(token));
        assertEquals("USER", provider.getRole(token));
        assertNull(provider.getMerchantId(token));
    }

    @Test
    @DisplayName("MERCHANT 역할 — merchantId 포함")
    void shouldIncludeMerchantId() {
        String token = provider.createAccessToken(2L, UserRole.MERCHANT, 100L);

        assertEquals(2L, provider.getUserId(token));
        assertEquals("MERCHANT", provider.getRole(token));
        assertEquals(100L, provider.getMerchantId(token));
    }

    @Test
    @DisplayName("Refresh Token 생성")
    void shouldCreateRefreshToken() {
        String token = provider.createRefreshToken();
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("만료된 토큰 — validateToken false")
    void shouldRejectExpiredToken() {
        JwtTokenProvider shortLived = new JwtTokenProvider(
            "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo",
            0L, 0L  // 즉시 만료
        );
        String token = shortLived.createAccessToken(1L, UserRole.USER, null);

        assertFalse(shortLived.validateToken(token));
    }

    @Test
    @DisplayName("잘못된 토큰 — validateToken false")
    void shouldRejectInvalidToken() {
        assertFalse(provider.validateToken("invalid.token.value"));
    }

    @Test
    @DisplayName("getRefreshTokenExpiry — 밀리초 반환")
    void shouldReturnRefreshTokenExpiry() {
        assertEquals(604800000L, provider.getRefreshTokenExpiry());
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.infrastructure.security.JwtTokenProviderTest" -i`
Expected: FAIL

- [ ] **Step 3: JwtTokenProvider 구현**

```java
package com.example.user.infrastructure.security;

import com.example.user.domain.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

public class JwtTokenProvider {

    private final SecretKey key;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtTokenProvider(String secret, long accessTokenExpiry, long refreshTokenExpiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String createAccessToken(long userId, UserRole role, Long merchantId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiry);

        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key);

        if (merchantId != null) {
            builder.claim("merchantId", merchantId);
        }

        return builder.compact();
    }

    public String createRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public Long getMerchantId(String token) {
        Object merchantId = getClaims(token).get("merchantId");
        if (merchantId == null) return null;
        return ((Number) merchantId).longValue();
    }

    public long getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.infrastructure.security.JwtTokenProviderTest" -i`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add user-service/src/main/java/com/example/user/infrastructure/security/JwtTokenProvider.java \
        user-service/src/test/java/com/example/user/infrastructure/security/JwtTokenProviderTest.java
git commit -m "feat(user): JwtTokenProvider HMAC-SHA256 + TDD 단위 테스트"
```

---

## Task 9: JPA 엔티티 + Repository 구현 + PersistenceConfig

**Files:**
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/UserJpaEntity.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/UserJpaRepository.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/UserRepositoryImpl.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/AddressJpaEntity.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/AddressJpaRepository.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/AddressRepositoryImpl.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/PaymentMethodJpaEntity.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/PaymentMethodJpaRepository.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/PaymentMethodRepositoryImpl.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/RefreshTokenJpaEntity.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/RefreshTokenJpaRepository.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/persistence/RefreshTokenRepositoryImpl.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/config/PersistenceConfig.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/security/BcryptPasswordEncoderAdapter.java`
- Test: `user-service/src/test/java/com/example/user/infrastructure/persistence/AbstractRepositoryTest.java`
- Test: `user-service/src/test/java/com/example/user/infrastructure/persistence/UserRepositoryImplTest.java`

이 Task는 파일이 많으므로 패턴별로 그룹화한다.

- [ ] **Step 1: AbstractRepositoryTest 베이스 클래스 작성**

```java
package com.example.user.infrastructure.persistence;

import com.example.user.infrastructure.config.PersistenceConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(classes = {PersistenceConfig.class})
@EnableAutoConfiguration
@Import(PersistenceConfig.class)
@Transactional
public abstract class AbstractRepositoryTest {

    static final MySQLContainer<?> mysql;

    static {
        mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("user_test")
            .withUsername("test")
            .withPassword("test");
        mysql.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("jwt.secret", () -> "test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256-algo");
        registry.add("jwt.access-token-expiry", () -> "3600000");
        registry.add("jwt.refresh-token-expiry", () -> "604800000");
    }
}
```

- [ ] **Step 2: UserJpaEntity 작성**

```java
package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    protected UserJpaEntity() {}

    public static UserJpaEntity from(User user) {
        UserJpaEntity e = new UserJpaEntity();
        e.id = user.getId();
        e.email = user.getEmail();
        e.password = user.getPassword();
        e.name = user.getName();
        e.phone = user.getPhone();
        e.role = user.getRole();
        e.merchantId = user.getMerchantId();
        e.status = user.getStatus();
        e.createdAt = toLocalDateTime(user.getCreatedAt());
        e.updatedAt = toLocalDateTime(user.getUpdatedAt());
        return e;
    }

    public User toDomain() {
        return User.reconstruct(id, email, password, name, phone, role,
                merchantId, status, toInstant(createdAt), toInstant(updatedAt));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }

    public Long getId() { return id; }
}
```

- [ ] **Step 3: UserJpaRepository + UserRepositoryImpl 작성**

```java
// UserJpaRepository.java
package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
    Optional<UserJpaEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

```java
// UserRepositoryImpl.java
package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;
import java.util.List;
import java.util.Optional;

public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepositoryImpl(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<User> findById(long id) {
        return jpaRepository.findById(id).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(UserJpaEntity::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = UserJpaEntity.from(user);
        return jpaRepository.save(entity).toDomain();
    }

    @Override
    public List<User> findAll() {
        return jpaRepository.findAll().stream()
                .map(UserJpaEntity::toDomain)
                .toList();
    }
}
```

- [ ] **Step 4: AddressJpaEntity + JpaRepo + Impl 작성**

```java
// AddressJpaEntity.java
package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.Address;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "addresses", indexes = {
    @Index(name = "idx_addresses_user_id", columnList = "user_id")
})
public class AddressJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "label", nullable = false, length = 50)
    private String label;

    @Column(name = "recipient", nullable = false, length = 50)
    private String recipient;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "address_detail")
    private String addressDetail;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    protected AddressJpaEntity() {}

    public static AddressJpaEntity from(Address address) {
        AddressJpaEntity e = new AddressJpaEntity();
        e.id = address.getId();
        e.userId = address.getUserId();
        e.label = address.getLabel();
        e.recipient = address.getRecipient();
        e.phone = address.getPhone();
        e.zipCode = address.getZipCode();
        e.address = address.getAddress();
        e.addressDetail = address.getAddressDetail();
        e.isDefault = address.isDefault();
        e.createdAt = toLocalDateTime(address.getCreatedAt());
        e.updatedAt = toLocalDateTime(address.getUpdatedAt());
        return e;
    }

    public Address toDomain() {
        return Address.reconstruct(id, userId, label, recipient, phone,
                zipCode, address, addressDetail, isDefault,
                toInstant(createdAt), toInstant(updatedAt));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
```

```java
// AddressJpaRepository.java
package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AddressJpaRepository extends JpaRepository<AddressJpaEntity, Long> {
    List<AddressJpaEntity> findAllByUserId(Long userId);
    Optional<AddressJpaEntity> findByIdAndUserId(Long id, Long userId);
}
```

```java
// AddressRepositoryImpl.java
package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.AddressRepository;
import com.example.user.domain.entity.Address;
import java.util.List;
import java.util.Optional;

public class AddressRepositoryImpl implements AddressRepository {

    private final AddressJpaRepository jpaRepository;

    public AddressRepositoryImpl(AddressJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<Address> findAllByUserId(long userId) {
        return jpaRepository.findAllByUserId(userId).stream()
                .map(AddressJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<Address> findByIdAndUserId(long id, long userId) {
        return jpaRepository.findByIdAndUserId(id, userId).map(AddressJpaEntity::toDomain);
    }

    @Override
    public Address save(Address address) {
        return jpaRepository.save(AddressJpaEntity.from(address)).toDomain();
    }

    @Override
    public void deleteById(long id) {
        jpaRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: PaymentMethodJpaEntity + JpaRepo + Impl 작성**

```java
// PaymentMethodJpaEntity.java
package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "payment_methods", indexes = {
    @Index(name = "idx_payment_methods_user_id", columnList = "user_id")
})
public class PaymentMethodJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentMethodType type;

    @Column(name = "card_number", length = 20)
    private String cardNumber;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Column(name = "bank_name", length = 50)
    private String bankName;

    @Column(name = "account_number", length = 30)
    private String accountNumber;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;

    protected PaymentMethodJpaEntity() {}

    public static PaymentMethodJpaEntity from(PaymentMethod pm) {
        PaymentMethodJpaEntity e = new PaymentMethodJpaEntity();
        e.id = pm.getId();
        e.userId = pm.getUserId();
        e.type = pm.getType();
        e.cardNumber = pm.getCardNumber();
        e.cardCompany = pm.getCardCompany();
        e.bankName = pm.getBankName();
        e.accountNumber = pm.getAccountNumber();
        e.isDefault = pm.isDefault();
        e.createdAt = toLocalDateTime(pm.getCreatedAt());
        e.updatedAt = toLocalDateTime(pm.getUpdatedAt());
        return e;
    }

    public PaymentMethod toDomain() {
        return PaymentMethod.reconstruct(id, userId, type, cardNumber, cardCompany,
                bankName, accountNumber, isDefault,
                toInstant(createdAt), toInstant(updatedAt));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
```

```java
// PaymentMethodJpaRepository.java
package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PaymentMethodJpaRepository extends JpaRepository<PaymentMethodJpaEntity, Long> {
    List<PaymentMethodJpaEntity> findAllByUserId(Long userId);
    Optional<PaymentMethodJpaEntity> findByIdAndUserId(Long id, Long userId);
}
```

```java
// PaymentMethodRepositoryImpl.java
package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.domain.entity.PaymentMethod;
import java.util.List;
import java.util.Optional;

public class PaymentMethodRepositoryImpl implements PaymentMethodRepository {

    private final PaymentMethodJpaRepository jpaRepository;

    public PaymentMethodRepositoryImpl(PaymentMethodJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<PaymentMethod> findAllByUserId(long userId) {
        return jpaRepository.findAllByUserId(userId).stream()
                .map(PaymentMethodJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<PaymentMethod> findByIdAndUserId(long id, long userId) {
        return jpaRepository.findByIdAndUserId(id, userId).map(PaymentMethodJpaEntity::toDomain);
    }

    @Override
    public PaymentMethod save(PaymentMethod paymentMethod) {
        return jpaRepository.save(PaymentMethodJpaEntity.from(paymentMethod)).toDomain();
    }

    @Override
    public void deleteById(long id) {
        jpaRepository.deleteById(id);
    }
}
```

- [ ] **Step 6: RefreshTokenJpaEntity + JpaRepo + Impl 작성**

```java
// RefreshTokenJpaEntity.java
package com.example.user.infrastructure.persistence;

import com.example.user.domain.entity.RefreshToken;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false)
    private LocalDateTime createdAt;

    protected RefreshTokenJpaEntity() {}

    public static RefreshTokenJpaEntity from(RefreshToken rt) {
        RefreshTokenJpaEntity e = new RefreshTokenJpaEntity();
        e.id = rt.getId();
        e.userId = rt.getUserId();
        e.token = rt.getToken();
        e.expiresAt = toLocalDateTime(rt.getExpiresAt());
        e.createdAt = toLocalDateTime(rt.getCreatedAt());
        return e;
    }

    public RefreshToken toDomain() {
        return RefreshToken.reconstruct(id, userId, token,
                toInstant(expiresAt), toInstant(createdAt));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC) : null;
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : null;
    }
}
```

```java
// RefreshTokenJpaRepository.java
package com.example.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {
    Optional<RefreshTokenJpaEntity> findByToken(String token);
    void deleteByUserId(Long userId);
    void deleteByToken(String token);
}
```

```java
// RefreshTokenRepositoryImpl.java
package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.RefreshTokenRepository;
import com.example.user.domain.entity.RefreshToken;
import java.util.Optional;

public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return jpaRepository.findByToken(token).map(RefreshTokenJpaEntity::toDomain);
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        return jpaRepository.save(RefreshTokenJpaEntity.from(refreshToken)).toDomain();
    }

    @Override
    public void deleteByUserId(long userId) {
        jpaRepository.deleteByUserId(userId);
    }

    @Override
    public void deleteByToken(String token) {
        jpaRepository.deleteByToken(token);
    }
}
```

- [ ] **Step 7: BcryptPasswordEncoderAdapter + PersistenceConfig 작성**

```java
// BcryptPasswordEncoderAdapter.java
package com.example.user.infrastructure.security;

import com.example.user.application.interfaces.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class BcryptPasswordEncoderAdapter implements PasswordEncoder {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
```

```java
// PersistenceConfig.java
package com.example.user.infrastructure.config;

import com.example.user.application.interfaces.*;
import com.example.user.infrastructure.persistence.*;
import com.example.user.infrastructure.security.BcryptPasswordEncoderAdapter;
import com.example.user.infrastructure.security.JwtTokenProvider;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.user.infrastructure.persistence")
public class PersistenceConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    public UserRepository userRepository(UserJpaRepository jpa) {
        return new UserRepositoryImpl(jpa);
    }

    @Bean
    public AddressRepository addressRepository(AddressJpaRepository jpa) {
        return new AddressRepositoryImpl(jpa);
    }

    @Bean
    public PaymentMethodRepository paymentMethodRepository(PaymentMethodJpaRepository jpa) {
        return new PaymentMethodRepositoryImpl(jpa);
    }

    @Bean
    public RefreshTokenRepository refreshTokenRepository(RefreshTokenJpaRepository jpa) {
        return new RefreshTokenRepositoryImpl(jpa);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BcryptPasswordEncoderAdapter();
    }

    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        return new JwtTokenProvider(secret, accessTokenExpiry, refreshTokenExpiry);
    }
}
```

- [ ] **Step 8: UserRepositoryImplTest 작성**

```java
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
        User user = User.of("test@example.com", "hashedPw", "홍길동",
                "010-1234-5678", UserRole.USER, null);

        User saved = userRepository.save(user);

        assertNotNull(saved.getId());
        Optional<User> found = userRepository.findByEmail("test@example.com");
        assertTrue(found.isPresent());
        assertEquals("홍길동", found.get().getName());
    }

    @Test
    @DisplayName("existsByEmail — 존재하면 true")
    void shouldReturnTrueForExistingEmail() {
        userRepository.save(User.of("exists@example.com", "pw", "이름",
                "010-0000-0000", UserRole.USER, null));

        assertTrue(userRepository.existsByEmail("exists@example.com"));
        assertFalse(userRepository.existsByEmail("notexists@example.com"));
    }
}
```

- [ ] **Step 9: 통합 테스트 실행**

Run: `./gradlew :user-service:test --tests "com.example.user.infrastructure.persistence.UserRepositoryImplTest" -i`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add user-service/src/main/java/com/example/user/infrastructure/ \
        user-service/src/test/java/com/example/user/infrastructure/
git commit -m "feat(user): JPA 엔티티, Repository 구현, PersistenceConfig + 통합 테스트"
```

---

## Task 10: SecurityConfig + JwtAuthenticationFilter (user-service)

**Files:**
- Create: `user-service/src/main/java/com/example/user/infrastructure/security/JwtAuthenticationFilter.java`
- Create: `user-service/src/main/java/com/example/user/infrastructure/security/SecurityConfig.java`

- [ ] **Step 1: JwtAuthenticationFilter 작성**

```java
package com.example.user.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && jwtTokenProvider.validateToken(token)) {
            long userId = jwtTokenProvider.getUserId(token);
            String role = jwtTokenProvider.getRole(token);
            Long merchantId = jwtTokenProvider.getMerchantId(token);

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var details = Map.of("userId", userId, "role", role, "merchantId",
                    merchantId != null ? merchantId : 0L);

            var authentication = new UsernamePasswordAuthenticationToken(
                    userId, null, authorities);
            authentication.setDetails(details);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
```

- [ ] **Step 2: SecurityConfig 작성**

```java
package com.example.user.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/signup", "/v1/auth/login", "/v1/auth/refresh").permitAll()
                .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :user-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add user-service/src/main/java/com/example/user/infrastructure/security/JwtAuthenticationFilter.java \
        user-service/src/main/java/com/example/user/infrastructure/security/SecurityConfig.java
git commit -m "feat(user): SecurityConfig + JwtAuthenticationFilter"
```

---

## Task 11: Auth UseCase + Service

**Files:**
- Create: `user-service/src/main/java/com/example/user/application/usecase/AuthUseCase.java`
- Create: `user-service/src/main/java/com/example/user/application/service/AuthService.java`
- Test: `user-service/src/test/java/com/example/user/application/service/AuthServiceTest.java`

- [ ] **Step 1: AuthUseCase 인터페이스 작성**

```java
package com.example.user.application.usecase;

import com.example.user.domain.entity.UserRole;

public interface AuthUseCase {

    record SignupCommand(String email, String password, String name, String phone,
                         UserRole role, Long merchantId) {}

    record LoginCommand(String email, String password) {}

    record TokenResult(String accessToken, String refreshToken) {}

    TokenResult signup(SignupCommand command);
    TokenResult login(LoginCommand command);
    String refresh(String refreshToken);
    void logout(long userId);
}
```

- [ ] **Step 2: AuthServiceTest 작성**

```java
package com.example.user.application.service;

import com.example.user.application.exception.DuplicateEmailException;
import com.example.user.application.exception.InvalidTokenException;
import com.example.user.application.interfaces.*;
import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.*;
import com.example.user.domain.entity.*;
import com.example.user.domain.exception.InvalidCredentialsException;
import com.example.user.domain.exception.SuspendedAccountException;
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
        authService = new AuthService(userRepository, refreshTokenRepository,
                passwordEncoder, jwtTokenProvider);
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
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.application.service.AuthServiceTest" -i`
Expected: FAIL — `AuthService` 없음

- [ ] **Step 4: AuthService 구현**

```java
package com.example.user.application.service;

import com.example.user.application.exception.DuplicateEmailException;
import com.example.user.application.exception.InvalidTokenException;
import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.RefreshTokenRepository;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AuthUseCase;
import com.example.user.common.exception.ErrorCode;
import com.example.user.domain.entity.RefreshToken;
import com.example.user.domain.entity.User;
import com.example.user.domain.exception.InvalidCredentialsException;
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
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(), user.getRole(), user.getMerchantId());
        String refreshTokenValue = jwtTokenProvider.createRefreshToken();

        Instant expiresAt = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpiry());
        RefreshToken refreshToken = RefreshToken.of(user.getId(), refreshTokenValue, expiresAt);
        refreshTokenRepository.save(refreshToken);

        return new TokenResult(accessToken, refreshTokenValue);
    }
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :user-service:test --tests "com.example.user.application.service.AuthServiceTest" -i`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add user-service/src/main/java/com/example/user/application/usecase/AuthUseCase.java \
        user-service/src/main/java/com/example/user/application/service/AuthService.java \
        user-service/src/test/java/com/example/user/application/service/AuthServiceTest.java
git commit -m "feat(user): AuthUseCase + AuthService(회원가입/로그인/갱신/로그아웃) + TDD"
```

---

## Task 12: User / Address / PaymentMethod / Admin UseCase + Service

**Files:**
- Create: `user-service/src/main/java/com/example/user/application/usecase/UserUseCase.java`
- Create: `user-service/src/main/java/com/example/user/application/service/UserService.java`
- Create: `user-service/src/main/java/com/example/user/application/usecase/AddressUseCase.java`
- Create: `user-service/src/main/java/com/example/user/application/service/AddressService.java`
- Create: `user-service/src/main/java/com/example/user/application/usecase/PaymentMethodUseCase.java`
- Create: `user-service/src/main/java/com/example/user/application/service/PaymentMethodService.java`
- Create: `user-service/src/main/java/com/example/user/application/usecase/AdminUseCase.java`
- Create: `user-service/src/main/java/com/example/user/application/service/AdminService.java`
- Test: `user-service/src/test/java/com/example/user/application/service/UserServiceTest.java`
- Test: `user-service/src/test/java/com/example/user/application/service/AddressServiceTest.java`
- Test: `user-service/src/test/java/com/example/user/application/service/PaymentMethodServiceTest.java`
- Test: `user-service/src/test/java/com/example/user/application/service/AdminServiceTest.java`

- [ ] **Step 1: UserUseCase 인터페이스 작성**

```java
package com.example.user.application.usecase;

import com.example.user.domain.entity.User;

public interface UserUseCase {
    record UpdateCommand(String name, String phone) {}
    record ChangePasswordCommand(String currentPassword, String newPassword) {}

    User getMe(long userId);
    User update(long userId, UpdateCommand command);
    void changePassword(long userId, ChangePasswordCommand command);
    void withdraw(long userId);
}
```

- [ ] **Step 2: UserServiceTest 작성**

```java
package com.example.user.application.service;

import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserUseCase.*;
import com.example.user.domain.entity.*;
import com.example.user.domain.exception.InvalidCredentialsException;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
    }

    private User activeUser() {
        return User.reconstruct(1L, "user@test.com", "hashedPw", "홍길동", "010-1234-5678",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("getMe — 유저 조회")
    void shouldGetUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        User user = userService.getMe(1L);
        assertEquals("홍길동", user.getName());
    }

    @Test
    @DisplayName("getMe — 없는 유저 → UserNotFoundException")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class, () -> userService.getMe(99L));
    }

    @Test
    @DisplayName("update — 이름/전화번호 변경")
    void shouldUpdateProfile() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User updated = userService.update(1L, new UpdateCommand("새이름", "010-9999-0000"));
        assertEquals("새이름", updated.getName());
    }

    @Test
    @DisplayName("changePassword — 현재 비밀번호 일치 시 변경")
    void shouldChangePassword() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("currentPw", "hashedPw")).thenReturn(true);
        when(passwordEncoder.encode("newPw")).thenReturn("newHashedPw");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        userService.changePassword(1L, new ChangePasswordCommand("currentPw", "newPw"));
        verify(userRepository).save(argThat(u -> u.getPassword().equals("newHashedPw")));
    }

    @Test
    @DisplayName("changePassword — 현재 비밀번호 불일치 → InvalidCredentialsException")
    void shouldThrowOnWrongCurrentPassword() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashedPw")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () ->
                userService.changePassword(1L, new ChangePasswordCommand("wrong", "newPw")));
    }

    @Test
    @DisplayName("withdraw — WITHDRAWN 상태 전환")
    void shouldWithdraw() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        userService.withdraw(1L);
        verify(userRepository).save(argThat(u -> u.getStatus() == UserStatus.WITHDRAWN));
    }
}
```

- [ ] **Step 3: UserService 구현**

```java
package com.example.user.application.service;

import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.PasswordEncoder;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserUseCase;
import com.example.user.domain.entity.User;
import com.example.user.domain.exception.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService implements UserUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public User getMe(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Override
    @Transactional
    public User update(long userId, UpdateCommand command) {
        User user = getMe(userId);
        user.updateProfile(command.name(), command.phone());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void changePassword(long userId, ChangePasswordCommand command) {
        User user = getMe(userId);
        if (!passwordEncoder.matches(command.currentPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }
        user.changePassword(passwordEncoder.encode(command.newPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void withdraw(long userId) {
        User user = getMe(userId);
        user.withdraw();
        userRepository.save(user);
    }
}
```

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :user-service:test --tests "com.example.user.application.service.UserServiceTest" -i`
Expected: PASS

- [ ] **Step 5: AddressUseCase + AddressService + 테스트 작성**

```java
// AddressUseCase.java
package com.example.user.application.usecase;

import com.example.user.domain.entity.Address;
import java.util.List;

public interface AddressUseCase {
    record CreateCommand(String label, String recipient, String phone, String zipCode,
                         String address, String addressDetail, boolean isDefault) {}
    record UpdateCommand(String label, String recipient, String phone, String zipCode,
                         String address, String addressDetail, boolean isDefault) {}

    List<Address> getAddresses(long userId);
    Address create(long userId, CreateCommand command);
    Address update(long userId, long addressId, UpdateCommand command);
    void delete(long userId, long addressId);
}
```

```java
// AddressService.java
package com.example.user.application.service;

import com.example.user.application.exception.AddressNotFoundException;
import com.example.user.application.interfaces.AddressRepository;
import com.example.user.application.usecase.AddressUseCase;
import com.example.user.domain.entity.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService implements AddressUseCase {

    private final AddressRepository addressRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Address> getAddresses(long userId) {
        return addressRepository.findAllByUserId(userId);
    }

    @Override
    @Transactional
    public Address create(long userId, CreateCommand command) {
        Address address = Address.of(userId, command.label(), command.recipient(),
                command.phone(), command.zipCode(), command.address(),
                command.addressDetail(), command.isDefault());
        return addressRepository.save(address);
    }

    @Override
    @Transactional
    public Address update(long userId, long addressId, UpdateCommand command) {
        Address address = addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        address.update(command.label(), command.recipient(), command.phone(),
                command.zipCode(), command.address(), command.addressDetail(), command.isDefault());
        return addressRepository.save(address);
    }

    @Override
    @Transactional
    public void delete(long userId, long addressId) {
        addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        addressRepository.deleteById(addressId);
    }
}
```

```java
// AddressServiceTest.java
package com.example.user.application.service;

import com.example.user.application.exception.AddressNotFoundException;
import com.example.user.application.interfaces.AddressRepository;
import com.example.user.application.usecase.AddressUseCase.*;
import com.example.user.domain.entity.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AddressService")
class AddressServiceTest {

    @Mock AddressRepository addressRepository;
    private AddressService addressService;

    @BeforeEach
    void setUp() {
        addressService = new AddressService(addressRepository);
    }

    @Test
    @DisplayName("배송지 생성")
    void shouldCreateAddress() {
        when(addressRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        Address result = addressService.create(1L,
                new CreateCommand("집", "홍길동", "010-1234", "06123", "서울", "101호", true));
        assertNotNull(result);
        verify(addressRepository).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 배송지 수정 → AddressNotFoundException")
    void shouldThrowOnUpdateNonExistent() {
        when(addressRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(AddressNotFoundException.class, () ->
                addressService.update(1L, 99L,
                        new UpdateCommand("회사", "김", "010", "03", "서울", "5층", false)));
    }
}
```

- [ ] **Step 6: PaymentMethodUseCase + PaymentMethodService + 테스트 작성**

```java
// PaymentMethodUseCase.java
package com.example.user.application.usecase;

import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import java.util.List;

public interface PaymentMethodUseCase {
    record CreateCommand(PaymentMethodType type, String cardNumber, String cardCompany,
                         String bankName, String accountNumber, boolean isDefault) {}

    List<PaymentMethod> getPaymentMethods(long userId);
    PaymentMethod create(long userId, CreateCommand command);
    void delete(long userId, long paymentMethodId);
}
```

```java
// PaymentMethodService.java
package com.example.user.application.service;

import com.example.user.application.exception.PaymentMethodNotFoundException;
import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.application.usecase.PaymentMethodUseCase;
import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentMethodService implements PaymentMethodUseCase {

    private final PaymentMethodRepository paymentMethodRepository;

    @Override
    @Transactional(readOnly = true)
    public List<PaymentMethod> getPaymentMethods(long userId) {
        return paymentMethodRepository.findAllByUserId(userId);
    }

    @Override
    @Transactional
    public PaymentMethod create(long userId, CreateCommand command) {
        PaymentMethod pm;
        if (command.type() == PaymentMethodType.CARD) {
            pm = PaymentMethod.ofCard(userId, command.cardNumber(),
                    command.cardCompany(), command.isDefault());
        } else {
            pm = PaymentMethod.ofBankTransfer(userId, command.bankName(),
                    command.accountNumber(), command.isDefault());
        }
        return paymentMethodRepository.save(pm);
    }

    @Override
    @Transactional
    public void delete(long userId, long paymentMethodId) {
        paymentMethodRepository.findByIdAndUserId(paymentMethodId, userId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(paymentMethodId));
        paymentMethodRepository.deleteById(paymentMethodId);
    }
}
```

```java
// PaymentMethodServiceTest.java
package com.example.user.application.service;

import com.example.user.application.exception.PaymentMethodNotFoundException;
import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.application.usecase.PaymentMethodUseCase.*;
import com.example.user.domain.entity.PaymentMethodType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodService")
class PaymentMethodServiceTest {

    @Mock PaymentMethodRepository paymentMethodRepository;
    private PaymentMethodService paymentMethodService;

    @BeforeEach
    void setUp() {
        paymentMethodService = new PaymentMethodService(paymentMethodRepository);
    }

    @Test
    @DisplayName("카드 결제수단 생성")
    void shouldCreateCardMethod() {
        when(paymentMethodRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        paymentMethodService.create(1L,
                new CreateCommand(PaymentMethodType.CARD, "1234", "신한", null, null, true));
        verify(paymentMethodRepository).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 결제수단 삭제 → PaymentMethodNotFoundException")
    void shouldThrowOnDeleteNonExistent() {
        when(paymentMethodRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(PaymentMethodNotFoundException.class, () ->
                paymentMethodService.delete(1L, 99L));
    }
}
```

- [ ] **Step 7: AdminUseCase + AdminService + 테스트 작성**

```java
// AdminUseCase.java
package com.example.user.application.usecase;

import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import java.util.List;

public interface AdminUseCase {
    List<User> getUsers();
    User updateStatus(long userId, UserStatus status);
    User updateRole(long userId, UserRole role);
}
```

```java
// AdminService.java
package com.example.user.application.service;

import com.example.user.application.exception.UserNotFoundException;
import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.AdminUseCase;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.domain.entity.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService implements AdminUseCase {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<User> getUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional
    public User updateStatus(long userId, UserStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (status == UserStatus.SUSPENDED) {
            user.suspend();
        } else if (status == UserStatus.ACTIVE) {
            user.activate();
        }
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User updateRole(long userId, UserRole role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.changeRole(role);
        return userRepository.save(user);
    }
}
```

```java
// AdminServiceTest.java
package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("유저 정지")
    void shouldSuspendUser() {
        User user = User.reconstruct(1L, "u@t.com", "pw", "이름", "010",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = adminService.updateStatus(1L, UserStatus.SUSPENDED);
        assertEquals(UserStatus.SUSPENDED, result.getStatus());
    }

    @Test
    @DisplayName("역할 변경")
    void shouldChangeRole() {
        User user = User.reconstruct(1L, "u@t.com", "pw", "이름", "010",
                UserRole.USER, null, UserStatus.ACTIVE, Instant.now(), Instant.now());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = adminService.updateRole(1L, UserRole.MERCHANT);
        assertEquals(UserRole.MERCHANT, result.getRole());
    }
}
```

- [ ] **Step 8: 전체 UseCase 테스트 실행**

Run: `./gradlew :user-service:test --tests "com.example.user.application.service.*" -i`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add user-service/src/main/java/com/example/user/application/ \
        user-service/src/test/java/com/example/user/application/
git commit -m "feat(user): User/Address/PaymentMethod/Admin UseCase + Service + TDD"
```

---

## Task 13: Presentation 레이어 (Controllers + DTOs + GlobalExceptionHandler)

**Files:**
- Create: `user-service/src/main/java/com/example/user/presentation/GlobalExceptionHandler.java`
- Create: `user-service/src/main/java/com/example/user/presentation/dto/*.java` (모든 DTO)
- Create: `user-service/src/main/java/com/example/user/presentation/controller/*.java` (모든 Controller)
- Test: `user-service/src/test/java/com/example/user/presentation/controller/AuthControllerTest.java`

- [ ] **Step 1: GlobalExceptionHandler 작성**

```java
package com.example.user.presentation;

import com.example.user.common.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(Map.of(
                        "code", e.getErrorCode().getCode(),
                        "message", e.getMessage()
                ));
    }
}
```

- [ ] **Step 2: DTO 작성**

```java
// SignupRequest.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
    @Email @NotBlank String email,
    @NotBlank String password,
    @NotBlank String name,
    @NotBlank String phone,
    @NotNull UserRole role,
    Long merchantId
) {}
```

```java
// LoginRequest.java
package com.example.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @Email @NotBlank String email,
    @NotBlank String password
) {}
```

```java
// TokenResponse.java
package com.example.user.presentation.dto;

public record TokenResponse(String accessToken, String refreshToken) {}
```

```java
// RefreshRequest.java
package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {}
```

```java
// UserResponse.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.User;

public record UserResponse(Long id, String email, String name, String phone,
                            String role, Long merchantId, String status) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getRole().name(), user.getMerchantId(), user.getStatus().name());
    }
}
```

```java
// UpdateUserRequest.java
package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserRequest(@NotBlank String name, @NotBlank String phone) {}
```

```java
// ChangePasswordRequest.java
package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
```

```java
// AddressRequest.java
package com.example.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record AddressRequest(
    @NotBlank String label, @NotBlank String recipient, @NotBlank String phone,
    @NotBlank String zipCode, @NotBlank String address, String addressDetail,
    boolean isDefault
) {}
```

```java
// AddressResponse.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.Address;

public record AddressResponse(Long id, String label, String recipient, String phone,
                               String zipCode, String address, String addressDetail,
                               boolean isDefault) {
    public static AddressResponse from(Address a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipient(), a.getPhone(),
                a.getZipCode(), a.getAddress(), a.getAddressDetail(), a.isDefault());
    }
}
```

```java
// PaymentMethodRequest.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.PaymentMethodType;
import jakarta.validation.constraints.NotNull;

public record PaymentMethodRequest(
    @NotNull PaymentMethodType type,
    String cardNumber, String cardCompany,
    String bankName, String accountNumber,
    boolean isDefault
) {}
```

```java
// PaymentMethodResponse.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.PaymentMethod;

public record PaymentMethodResponse(Long id, String type, String cardNumber, String cardCompany,
                                     String bankName, String accountNumber, boolean isDefault) {
    public static PaymentMethodResponse from(PaymentMethod pm) {
        return new PaymentMethodResponse(pm.getId(), pm.getType().name(), pm.getCardNumber(),
                pm.getCardCompany(), pm.getBankName(), pm.getAccountNumber(), pm.isDefault());
    }
}
```

```java
// UpdateUserStatusRequest.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(@NotNull UserStatus status) {}
```

```java
// UpdateUserRoleRequest.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull UserRole role) {}
```

```java
// AdminUserResponse.java
package com.example.user.presentation.dto;

import com.example.user.domain.entity.User;

public record AdminUserResponse(Long id, String email, String name, String phone,
                                 String role, Long merchantId, String status) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getRole().name(), user.getMerchantId(), user.getStatus().name());
    }
}
```

- [ ] **Step 3: AuthController 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.*;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthUseCase authUseCase;

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@RequestBody @Valid SignupRequest request) {
        TokenResult result = authUseCase.signup(new SignupCommand(
                request.email(), request.password(), request.name(),
                request.phone(), request.role(), request.merchantId()));
        return ResponseEntity.ok(new TokenResponse(result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request) {
        TokenResult result = authUseCase.login(new LoginCommand(request.email(), request.password()));
        return ResponseEntity.ok(new TokenResponse(result.accessToken(), result.refreshToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        String accessToken = authUseCase.refresh(request.refreshToken());
        return ResponseEntity.ok(new TokenResponse(accessToken, null));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        authUseCase.logout(userId);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 4: UserController 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.UserUseCase;
import com.example.user.application.usecase.UserUseCase.*;
import com.example.user.domain.entity.User;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserUseCase userUseCase;

    @GetMapping
    public ResponseEntity<UserResponse> getMe(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(UserResponse.from(userUseCase.getMe(userId)));
    }

    @PatchMapping
    public ResponseEntity<UserResponse> update(Authentication authentication,
                                                @RequestBody @Valid UpdateUserRequest request) {
        long userId = (long) authentication.getPrincipal();
        User updated = userUseCase.update(userId, new UpdateCommand(request.name(), request.phone()));
        return ResponseEntity.ok(UserResponse.from(updated));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(Authentication authentication,
                                                @RequestBody @Valid ChangePasswordRequest request) {
        long userId = (long) authentication.getPrincipal();
        userUseCase.changePassword(userId, new ChangePasswordCommand(
                request.currentPassword(), request.newPassword()));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        userUseCase.withdraw(userId);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 5: AddressController 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.AddressUseCase;
import com.example.user.application.usecase.AddressUseCase.*;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressUseCase addressUseCase;

    @GetMapping
    public ResponseEntity<List<AddressResponse>> getAddresses(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(addressUseCase.getAddresses(userId).stream()
                .map(AddressResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(Authentication authentication,
                                                   @RequestBody @Valid AddressRequest request) {
        long userId = (long) authentication.getPrincipal();
        var result = addressUseCase.create(userId, new CreateCommand(
                request.label(), request.recipient(), request.phone(), request.zipCode(),
                request.address(), request.addressDetail(), request.isDefault()));
        return ResponseEntity.ok(AddressResponse.from(result));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AddressResponse> update(Authentication authentication,
                                                   @PathVariable long id,
                                                   @RequestBody @Valid AddressRequest request) {
        long userId = (long) authentication.getPrincipal();
        var result = addressUseCase.update(userId, id, new UpdateCommand(
                request.label(), request.recipient(), request.phone(), request.zipCode(),
                request.address(), request.addressDetail(), request.isDefault()));
        return ResponseEntity.ok(AddressResponse.from(result));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
        long userId = (long) authentication.getPrincipal();
        addressUseCase.delete(userId, id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 6: PaymentMethodController 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.PaymentMethodUseCase;
import com.example.user.application.usecase.PaymentMethodUseCase.CreateCommand;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/users/me/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodUseCase paymentMethodUseCase;

    @GetMapping
    public ResponseEntity<List<PaymentMethodResponse>> getPaymentMethods(Authentication authentication) {
        long userId = (long) authentication.getPrincipal();
        return ResponseEntity.ok(paymentMethodUseCase.getPaymentMethods(userId).stream()
                .map(PaymentMethodResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<PaymentMethodResponse> create(Authentication authentication,
                                                         @RequestBody @Valid PaymentMethodRequest request) {
        long userId = (long) authentication.getPrincipal();
        var result = paymentMethodUseCase.create(userId, new CreateCommand(
                request.type(), request.cardNumber(), request.cardCompany(),
                request.bankName(), request.accountNumber(), request.isDefault()));
        return ResponseEntity.ok(PaymentMethodResponse.from(result));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
        long userId = (long) authentication.getPrincipal();
        paymentMethodUseCase.delete(userId, id);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 7: AdminController 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.AdminUseCase;
import com.example.user.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUseCase adminUseCase;

    @GetMapping
    public ResponseEntity<List<AdminUserResponse>> getUsers() {
        return ResponseEntity.ok(adminUseCase.getUsers().stream()
                .map(AdminUserResponse::from).toList());
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserResponse> updateStatus(@PathVariable long id,
                                                           @RequestBody @Valid UpdateUserStatusRequest request) {
        return ResponseEntity.ok(AdminUserResponse.from(adminUseCase.updateStatus(id, request.status())));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<AdminUserResponse> updateRole(@PathVariable long id,
                                                         @RequestBody @Valid UpdateUserRoleRequest request) {
        return ResponseEntity.ok(AdminUserResponse.from(adminUseCase.updateRole(id, request.role())));
    }
}
```

- [ ] **Step 8: AuthControllerTest 작성**

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.AuthUseCase;
import com.example.user.application.usecase.AuthUseCase.TokenResult;
import com.example.user.infrastructure.security.JwtTokenProvider;
import com.example.user.infrastructure.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AuthUseCase authUseCase;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("POST /v1/auth/signup — 201 토큰 반환")
    void shouldSignup() throws Exception {
        when(authUseCase.signup(any())).thenReturn(new TokenResult("access", "refresh"));

        mockMvc.perform(post("/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123",
                             "name":"홍길동","phone":"010-1234-5678","role":"USER"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    @DisplayName("POST /v1/auth/login — 토큰 반환")
    void shouldLogin() throws Exception {
        when(authUseCase.login(any())).thenReturn(new TokenResult("access", "refresh"));

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"email":"test@example.com","password":"pw123"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    @DisplayName("POST /v1/auth/refresh — 새 Access Token")
    void shouldRefresh() throws Exception {
        when(authUseCase.refresh("valid-refresh")).thenReturn("new-access");

        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"refreshToken":"valid-refresh"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }
}
```

- [ ] **Step 9: 컨트롤러 테스트 실행**

Run: `./gradlew :user-service:test --tests "com.example.user.presentation.controller.AuthControllerTest" -i`
Expected: PASS

- [ ] **Step 10: 커밋**

```bash
git add user-service/src/main/java/com/example/user/presentation/ \
        user-service/src/test/java/com/example/user/presentation/
git commit -m "feat(user): 전체 Controller + DTO + GlobalExceptionHandler + AuthController 테스트"
```

---

## Task 14: payment-service JWT 인가 적용

**Files:**
- Modify: `payment-service/build.gradle`
- Modify: `payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/security/AuthenticatedUser.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/security/JwtAuthenticationFilter.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/security/SecurityConfig.java`
- Modify: `payment-service/src/main/java/com/example/payment/presentation/controller/CancelController.java`
- Modify: `payment-service/src/main/resources/application.yml`

- [ ] **Step 1: payment-service build.gradle에 의존성 추가**

`payment-service/build.gradle` dependencies 블록에 추가:

```gradle
    // Spring Security
    implementation 'org.springframework.boot:spring-boot-starter-security'

    // JWT (jjwt)
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'

    // Test — Spring Security
    testImplementation 'org.springframework.security:spring-security-test'
```

- [ ] **Step 2: ErrorCode에 인가 관련 코드 추가**

`payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`에 추가:

```java
    // 401 - 인증 오류
    UNAUTHORIZED("PAY_AUTH_001", 401, "인증 토큰이 없거나 유효하지 않습니다."),

    // 403 - 인가 오류 (기존 FORBIDDEN_PAYMENT 활용 또는 새로 추가)
    // FORBIDDEN_PAYMENT는 이미 존재 — PAY_AUTH_002로 별칭 불필요
```

참고: 기존 `FORBIDDEN_PAYMENT("FORBIDDEN_PAYMENT", 403, ...)` 이 이미 존재하므로 `UNAUTHORIZED`만 추가한다.

- [ ] **Step 3: AuthenticatedUser VO 작성**

```java
package com.example.payment.infrastructure.security;

import com.example.payment.domain.entity.Payment;

public record AuthenticatedUser(long userId, String role, Long merchantId) {

    public void validateCancelAuthorization(Payment payment) {
        if ("ADMIN".equals(role)) return;
        if ("MERCHANT".equals(role) && merchantId != null
                && merchantId.equals(payment.getMerchantId())) return;
        if ("USER".equals(role) && userId == payment.getUserId()) return;

        throw new com.example.payment.domain.exception.CancelNotAuthorizedException();
    }
}
```

- [ ] **Step 4: CancelNotAuthorizedException 작성**

```java
// payment-service/src/main/java/com/example/payment/domain/exception/CancelNotAuthorizedException.java
package com.example.payment.domain.exception;

import com.example.payment.common.exception.BusinessException;
import com.example.payment.common.exception.ErrorCode;

public class CancelNotAuthorizedException extends BusinessException {
    public CancelNotAuthorizedException() {
        super(ErrorCode.FORBIDDEN_PAYMENT);
    }
}
```

- [ ] **Step 5: JwtAuthenticationFilter 작성 (payment-service)**

```java
package com.example.payment.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public JwtAuthenticationFilter(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            String token = bearer.substring(7);
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(token).getPayload();

                long userId = Long.parseLong(claims.getSubject());
                String role = claims.get("role", String.class);
                Long merchantId = claims.get("merchantId") != null
                        ? ((Number) claims.get("merchantId")).longValue() : null;

                AuthenticatedUser authenticatedUser = new AuthenticatedUser(userId, role, merchantId);

                var auth = new UsernamePasswordAuthenticationToken(
                        authenticatedUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ignored) {
                // 인증 실패 — SecurityContext 비어 있음 → 401
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 6: SecurityConfig 작성 (payment-service)**

```java
package com.example.payment.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtSecret),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

- [ ] **Step 7: CancelController에 인가 검증 추가**

기존 CancelController의 `cancel()` 메서드를 수정:

```java
    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancel(
        @PathVariable String paymentKey,
        @RequestBody @Valid CancelPaymentRequest request,
        Authentication authentication
    ) {
        AuthenticatedUser authenticatedUser = (AuthenticatedUser) authentication.getPrincipal();

        // Payment 조회 후 인가 검증
        // 인가는 CancelPaymentUseCase 내부가 아닌 Controller에서 수행
        // (UseCase는 인가 완료된 요청만 처리)

        List<Long> itemIds = request.cancelItems().stream()
            .map(item -> item.paymentItemId())
            .toList();

        CancelPaymentCommand command = new CancelPaymentCommand(
            paymentKey, request.cancelReason(), itemIds);

        CancelRequest cancelRequest = cancelPaymentUseCase.cancel(command);

        return ResponseEntity.ok(
            CancelPaymentResponse.of(cancelRequest, paymentKey, List.of())
        );
    }
```

참고: 인가 검증을 Controller에서 Payment 조회 후 수행하려면 PaymentRepository에 대한 의존이 필요하다. 대안으로 CancelPaymentUseCase에 AuthenticatedUser를 전달하는 방식도 가능하다. 구현 시 기존 CancelPaymentService 구조를 확인하고 가장 적합한 위치를 선택한다.

- [ ] **Step 8: application.yml에 jwt.secret 추가**

`payment-service/src/main/resources/application.yml` 에 추가:

```yaml
jwt:
  secret: ${JWT_SECRET:default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256}
```

- [ ] **Step 9: 빌드 확인**

Run: `./gradlew :payment-service:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: 커밋**

```bash
git add payment-service/build.gradle \
        payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java \
        payment-service/src/main/java/com/example/payment/infrastructure/security/ \
        payment-service/src/main/java/com/example/payment/domain/exception/CancelNotAuthorizedException.java \
        payment-service/src/main/java/com/example/payment/presentation/controller/CancelController.java \
        payment-service/src/main/resources/application.yml
git commit -m "feat(payment): JWT 인증 필터 + 역할별 취소 인가 검증"
```

---

## Task 15: 전체 빌드 + 통합 검증

- [ ] **Step 1: user-service 전체 테스트**

Run: `./gradlew :user-service:test -i`
Expected: ALL PASS

- [ ] **Step 2: payment-service 전체 테스트 (기존 + 새 보안 레이어)**

Run: `./gradlew :payment-service:test -i`
Expected: ALL PASS

- [ ] **Step 3: 전체 프로젝트 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 실패하는 테스트가 있으면 수정**

기존 payment-service 테스트가 SecurityConfig 추가로 인해 실패할 수 있다.
이 경우 테스트에 `@AutoConfigureMockMvc(addFilters = false)` 또는
`@WithMockUser` 를 추가하여 기존 테스트가 보안 필터를 우회하도록 한다.

- [ ] **Step 5: 최종 커밋**

```bash
git add -A
git commit -m "fix: 전체 빌드 통과 확인 및 테스트 보안 설정 보완"
```
