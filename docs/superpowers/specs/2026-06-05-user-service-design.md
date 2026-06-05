# user-service 설계 스펙

## 개요

패션 이커머스 결제 취소 시스템에 유저 도메인 모듈을 추가한다.
user-service는 회원가입/로그인/JWT 발급/프로필 관리를 담당하고,
취소 인가는 payment-service가 JWT claims 기반으로 자체 수행한다.

---

## 1. 모듈 책임 & 포트

| 항목 | 내용 |
|------|------|
| 모듈명 | `user-service` |
| 포트 | `8085` |
| 책임 | 회원가입, 로그인, JWT 발급/갱신, 프로필 관리, 배송지 관리, 결제수단 관리 |

인가 원칙: user-service는 인증(JWT 발급)만 담당한다.
인가(역할별 권한 체크)는 각 서비스가 JWT claims에서 role을 꺼내 자체 수행한다.

---

## 2. 인증 설계 (JWT, HMAC-SHA256)

### JWT Claims

```json
{
  "sub": "userId (Long)",
  "role": "USER | MERCHANT | ADMIN",
  "merchantId": 123,
  "iat": 1717580000,
  "exp": 1717583600
}
```

- `merchantId`: MERCHANT 역할일 때만 포함
- Access Token 만료: 1시간
- Refresh Token 만료: 7일, DB 저장 (로그아웃 시 무효화)

### 전달 방식

```
Authorization: Bearer {accessToken}
```

### 다른 서비스의 JWT 검증

- 공유 시크릿을 환경 변수(`JWT_SECRET`)로 각 서비스에 주입
- `JwtAuthenticationFilter`를 Spring Security 필터로 추가
- 토큰에서 꺼낸 userId, role, merchantId를 `SecurityContext`에 저장

---

## 3. 도메인 엔티티

### User

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | AUTO_INCREMENT |
| email | String | UNIQUE, 로그인 ID |
| password | String | BCrypt 해시 |
| name | String | 실명 |
| phone | String | 전화번호 |
| role | Enum | USER, MERCHANT, ADMIN |
| merchantId | Long | MERCHANT 역할일 때만, nullable |
| status | Enum | ACTIVE, SUSPENDED, WITHDRAWN |
| createdAt | Instant | |
| updatedAt | Instant | |

### Address (배송지)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| userId | Long (FK) | |
| label | String | "집", "회사" 등 |
| recipient | String | 수령인 |
| phone | String | 수령인 연락처 |
| zipCode | String | 우편번호 |
| address | String | 기본 주소 |
| addressDetail | String | 상세 주소 |
| isDefault | Boolean | 기본 배송지 여부 |

### PaymentMethod (결제수단)

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| userId | Long (FK) | |
| type | Enum | CARD, BANK_TRANSFER, VIRTUAL_ACCOUNT |
| cardNumber | String | 마스킹 저장 (끝 4자리만) |
| cardCompany | String | |
| bankName | String | |
| accountNumber | String | 마스킹 저장 |
| isDefault | Boolean | 기본 결제수단 여부 |

### RefreshToken

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| userId | Long (FK) | |
| token | String | UNIQUE, 리프레시 토큰 값 |
| expiresAt | Instant | 만료 시점 |
| createdAt | Instant | |

---

## 4. API 엔드포인트

### 인증

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| POST | `/v1/auth/signup` | 회원가입 | 불필요 |
| POST | `/v1/auth/login` | 로그인 → Access + Refresh Token 반환 | 불필요 |
| POST | `/v1/auth/refresh` | Access Token 재발급 | Refresh Token |
| POST | `/v1/auth/logout` | Refresh Token 무효화 | Bearer Token |

### 프로필

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/v1/users/me` | 내 정보 조회 | Bearer Token |
| PATCH | `/v1/users/me` | 내 정보 수정 (이름, 전화번호) | Bearer Token |
| PATCH | `/v1/users/me/password` | 비밀번호 변경 | Bearer Token |
| DELETE | `/v1/users/me` | 회원 탈퇴 (WITHDRAWN 상태 전환) | Bearer Token |

### 배송지

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/v1/users/me/addresses` | 배송지 목록 조회 | Bearer Token |
| POST | `/v1/users/me/addresses` | 배송지 추가 | Bearer Token |
| PATCH | `/v1/users/me/addresses/{id}` | 배송지 수정 | Bearer Token |
| DELETE | `/v1/users/me/addresses/{id}` | 배송지 삭제 | Bearer Token |

### 결제수단

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/v1/users/me/payment-methods` | 결제수단 목록 조회 | Bearer Token |
| POST | `/v1/users/me/payment-methods` | 결제수단 등록 | Bearer Token |
| DELETE | `/v1/users/me/payment-methods/{id}` | 결제수단 삭제 | Bearer Token |

### ADMIN 전용

| Method | Path | 설명 | 인증 |
|--------|------|------|------|
| GET | `/v1/admin/users` | 유저 목록 조회 | ADMIN |
| PATCH | `/v1/admin/users/{id}/status` | 유저 상태 변경 (정지/복구) | ADMIN |
| PATCH | `/v1/admin/users/{id}/role` | 역할 변경 | ADMIN |

---

## 5. payment-service 인가 적용

### 인가 규칙

| 역할 | 취소 가능 범위 | 검증 로직 |
|------|---------------|----------|
| USER | 본인 결제만 | `JWT.sub == Payment.userId` |
| MERCHANT | 자기 가맹점 결제 | `JWT.merchantId == Payment.merchantId` |
| ADMIN | 전체 | 제한 없음 |

### 적용 위치

```
CancelPaymentController.cancel()
  → JwtAuthenticationFilter가 SecurityContext에 userId, role, merchantId 세팅
  → CancelPaymentUseCase 호출 전에 인가 검증
  → 권한 없으면 403 FORBIDDEN
```

### 기존 payment-service 변경 범위

| 변경 대상 | 내용 |
|----------|------|
| `build.gradle` | Spring Security + JWT 라이브러리 의존성 추가 |
| `JwtAuthenticationFilter` | 신규. 토큰 파싱 → SecurityContext 세팅 |
| `SecurityConfig` | 신규. 필터 체인 설정 |
| `CancelPaymentController` | 인가 검증 로직 추가 (역할별 분기) |
| `application.yml` | `jwt.secret` 환경 변수 바인딩 |

기존 취소 플로우(TX 1→2→3, 멱등성, 스케줄러)는 변경 없음.

---

## 6. DB 스키마

user-service 전용 DB. Flyway 파일: `V1__create_user_core.sql`

```sql
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

---

## 7. 에러 코드

### user-service

| 코드 | HTTP | 설명 |
|------|------|------|
| USER_001 | 409 | 이미 등록된 이메일 |
| USER_002 | 401 | 이메일 또는 비밀번호 불일치 |
| USER_003 | 404 | 유저를 찾을 수 없음 |
| USER_004 | 403 | 정지된 계정 |
| USER_005 | 401 | 만료된 토큰 |
| USER_006 | 401 | 유효하지 않은 토큰 |
| USER_007 | 401 | 만료된 리프레시 토큰 |
| USER_008 | 404 | 배송지를 찾을 수 없음 |
| USER_009 | 404 | 결제수단을 찾을 수 없음 |
| USER_010 | 403 | 권한 없음 (ADMIN 전용 API) |

### payment-service 추가

| 코드 | HTTP | 설명 |
|------|------|------|
| PAY_AUTH_001 | 401 | 인증 토큰 없음 |
| PAY_AUTH_002 | 403 | 해당 결제 취소 권한 없음 |

---

## 8. 테스트 전략

### user-service

| 레이어 | 대상 | 방식 |
|--------|------|------|
| domain | User 엔티티 (상태 전환, role 검증) | 단위 테스트 |
| application | 회원가입/로그인/토큰 갱신 유스케이스 | 단위 테스트 (Mockito) |
| infrastructure | JPA 저장소, JWT 생성/파싱 | 통합 테스트 (Testcontainers) |
| presentation | 컨트롤러 요청/응답, 인증 필터 | MockMvc |

### payment-service (추가분)

| 대상 | 방식 |
|------|------|
| JwtAuthenticationFilter | MockMvc (유효/만료/누락 토큰) |
| 인가 검증 (역할별 취소 범위) | 통합 테스트 (USER→본인만, MERCHANT→가맹점, ADMIN→전체) |

---

## 9. 전체 흐름

```
[유저]
  POST /v1/auth/login (user-service:8085)
  ← Access Token + Refresh Token

[취소 요청]
  POST /v1/payments/{paymentKey}/cancel (payment-service:8080)
  Header: Authorization: Bearer {accessToken}

  payment-service 내부:
    1. JwtAuthenticationFilter → 토큰 검증 → SecurityContext(userId, role, merchantId)
    2. CancelPaymentController → 인가 검증
       - USER: Payment.userId == JWT.sub
       - MERCHANT: Payment.merchantId == JWT.merchantId
       - ADMIN: pass
    3. 기존 취소 플로우 그대로 실행 (TX1 → TX2 → TX3)

[토큰 만료 시]
  POST /v1/auth/refresh (user-service:8085)
  Body: { "refreshToken": "..." }
  ← 새 Access Token
```

---

## 10. 패키지 구조 (user-service)

```
user-service
└── src/main/java/com/example/userservice
    ├── common
    │   └── exception       BusinessException
    ├── domain
    │   ├── entity          User, Address, PaymentMethod, RefreshToken
    │   ├── service         도메인 서비스
    │   └── exception       비즈니스 규칙 위반 예외
    ├── application
    │   ├── usecase          AuthUseCase, UserUseCase, AddressUseCase, PaymentMethodUseCase
    │   ├── service          유스케이스 구현체
    │   └── exception        리소스 없음 예외
    ├── infrastructure
    │   ├── persistence      JPA Repository
    │   ├── security         JwtTokenProvider, JwtAuthenticationFilter, SecurityConfig
    │   ├── config           Spring 설정
    │   └── exception        인프라 예외
    └── presentation
        ├── controller       AuthController, UserController, AddressController, PaymentMethodController, AdminController
        └── dto              요청/응답 DTO
```
