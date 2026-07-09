# 아키텍처 규약 — 레이어 구조 & 예외 계층

> CLAUDE.md에서 분리 (2026-07-10). 전체 시스템 개요는 `docs/architecture.md` 참조.

## 레이어 구조 (모듈 공통)

각 모듈은 아래 레이어 구조를 따른다.

```
{module}
└── src/main/java/com/example/{module}
    ├── common
    │   └── exception       BusinessException (모든 예외의 부모)
    ├── domain
    │   ├── entity          엔티티, 값객체
    │   ├── service         도메인 서비스
    │   ├── policy          정책 객체
    │   └── exception       비즈니스 규칙 위반 예외만
    ├── application
    │   ├── usecase         유스케이스 인터페이스
    │   ├── service         유스케이스 구현체
    │   ├── interfaces      외부 시스템 계약 (인터페이스)
    │   └── exception       리소스 없음, 멱등 중복 예외
    ├── infrastructure
    │   ├── persistence     JPA 구현체
    │   ├── messaging       Kafka Producer/Consumer
    │   ├── http            외부 HTTP 클라이언트
    │   ├── config          Spring 설정
    │   └── exception       외부 연동 실패 예외
    └── presentation
        ├── controller      REST 컨트롤러
        └── dto             요청/응답 DTO
```

**의존 방향**: presentation → application → domain / infrastructure → domain (단방향, 역방향 금지)

## 예외 계층 원칙

```
common/exception
  BusinessException          모든 커스텀 예외의 부모
    errorCode: String        error-catalog.md 코드와 1:1 매핑
    httpStatus: int

domain/exception             비즈니스 규칙 위반만
  InvalidCancelAmountException
  InvalidPaymentStatusException
  CancelPeriodExpiredException
  InvalidCancelStateTransitionException

application/exception        리소스 없음, 멱등 중복
  PaymentNotFoundException
  CancelRequestNotFoundException
  IdempotentDuplicationException

infrastructure/exception     외부 연동 실패
  MerchantLimitServiceException
  RiskServiceException
```

presentation에서는 `BusinessException`을 잡아 errorCode 기반으로 에러 응답을 통일한다.
