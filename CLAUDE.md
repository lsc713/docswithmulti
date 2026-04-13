# CLAUDE.md

Claude Code가 세션 시작 시 가장 먼저 읽는 파일이다.
이 파일을 읽은 후 아래 참조 문서를 순서대로 읽어라.

---

## 프로젝트 개요

패션 이커머스 결제 취소 시스템.
핵심은 결제 취소 플로우의 멱등성, 동시성, 부분취소 처리다.

### 모듈 구성

| 모듈 | 역할 | 포트 |
|------|------|------|
| `payment-service` | 결제 취소 핵심 로직 | 8080 |
| `order-service` | 주문/주문아이템 상태 동기화 | 8081 |
| `merchant-limit-service` | 가맹점별 일일 취소한도 원본 관리 | 8082 |
| `risk-management-service` | 취소 가능 여부 검증 + 소진 한도 관리 | 8083 |
| `product-service` | 상품/SKU/재고 관리 | 8084 |

### 기술 스택

| 항목 | 기술 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.x |
| ORM | Spring Data JPA + QueryDSL |
| DB | MySQL 8.0 (모듈별 독립) |
| 마이그레이션 | Flyway |
| 메시징 | Kafka 3.x (3-broker 클러스터) |
| 빌드 | Gradle |
| 테스트 | JUnit 5 + Mockito + Testcontainers |

---

## 필수 참조 문서

작업 시작 전 반드시 아래 순서로 읽어라.
코드보다 문서가 먼저다.

```
@docs/domain-rules.md      비즈니스 규칙 원본
@docs/error-catalog.md     에러 코드 전체 목록
@docs/api-spec.md          API 요청/응답 스펙
@docs/db-schema.md         DB 규칙 및 인덱스 전략
@docs/kafka-design.md      Kafka 설계 및 운영
@docs/architecture.md      전체 시스템 설계
@docs/contributing.md      코드 작성 기준
@docs/agent.md             작업 행동 규칙
```

DDL 확인 시 직접 파일을 읽어라.

```
payment-service:         db/migration/V1__create_payment_core.sql ~ V7
order-service:           db/migration/V1__create_order_core.sql
merchant-limit-service:  db/migration/V1__create_merchant_limit_core.sql
risk-management-service: db/migration/V1__create_risk_core.sql
product-service:         db/migration/V1__create_product_core.sql
```

---

## 핵심 실행 명령어

### 빌드 및 실행

```bash
# 전체 빌드
./gradlew build

# 특정 모듈 빌드
./gradlew :payment-service:build

# 테스트 실행
./gradlew test

# 특정 모듈 테스트
./gradlew :payment-service:test

# 로컬 실행 (Docker Compose)
docker-compose up -d
```

### DB 마이그레이션

```bash
# 마이그레이션 실행 (애플리케이션 시작 시 자동 실행)
./gradlew :payment-service:flywayMigrate

# 마이그레이션 상태 확인
./gradlew :payment-service:flywayInfo
```

### Kafka

```bash
# Kafka UI 접근
http://localhost:8989

# 토픽 목록 확인
docker exec -it kafka1 kafka-topics.sh --list --bootstrap-server localhost:9092

# 메시지 실시간 확인
docker exec -it kafka1 kafka-console-consumer.sh \
  --topic payment.cancelled \
  --bootstrap-server localhost:9092 \
  --from-beginning
```

---

## 패키지 구조

각 모듈은 아래 레이어 구조를 따른다.

```
{module}
└── src/main/java/com/example/{module}
    ├── domain
    │   ├── entity          엔티티, 값객체
    │   ├── service         도메인 서비스
    │   └── policy          정책 객체
    ├── application
    │   ├── usecase         유스케이스 인터페이스
    │   ├── service         유스케이스 구현체
    │   └── interfaces      외부 시스템 계약 (인터페이스)
    ├── infrastructure
    │   ├── persistence     JPA 구현체
    │   ├── messaging       Kafka Producer/Consumer
    │   ├── http            외부 HTTP 클라이언트
    │   └── config          Spring 설정
    └── presentation
        ├── controller      REST 컨트롤러
        └── dto             요청/응답 DTO
```

의존 방향: presentation → application → domain
infrastructure → domain (단방향, 역방향 금지)

---

## 현재 작업 상태

### 완료

- [x] 전체 시스템 설계
- [x] 도메인 규칙 확정
- [x] 에러 카탈로그 확정
- [x] API 스펙 확정
- [x] Kafka 설계 확정
- [x] 전체 모듈 DDL 작성 (Flyway V1~V7)

### 진행 중

- [ ] payment-service 구현
- [ ] order-service 구현
- [ ] merchant-limit-service 구현
- [ ] risk-management-service 구현
- [ ] product-service 구현

### 구현 우선순위

```
1. payment-service  핵심 취소 플로우
2. risk-management-service  취소 검증 + 한도 소진
3. merchant-limit-service   한도 원본 관리
4. order-service            Kafka Consumer + 상태 동기화
5. product-service          상품/SKU/재고
```

---

## 절대 하지 말아야 할 것

```
- 운영 DB에 직접 DDL 실행 금지 (Flyway를 통해서만)
- Flyway로 적용된 파일 수정 금지 (새 버전 파일로 추가)
- 모듈 간 DB 직접 접근 금지 (HTTP 또는 Kafka 경유)
- domain 레이어에 Spring/JPA 어노테이션 추가 금지
- 시크릿/비밀번호 코드에 하드코딩 금지
- 테스트 없이 구현 완료 처리 금지
- domain-rules.md를 확인하지 않고 비즈니스 로직 작성 금지
```

---

## 모호한 요구사항 처리

요구사항이 불명확할 때:
1. domain-rules.md에서 관련 규칙 먼저 확인
2. 문서에 없으면 가정을 1문장으로 명시 후 진행
3. 가정 내용을 응답 첫 줄에 표시

예시:
```
가정: 취소 기간 초과 여부는 Payment.created_at 기준으로 판단한다.
```