# DB schema guide

## Source of truth

DDL은 각 모듈의 `src/main/resources/db/migration/` 하위 Flyway 파일이 원본이다.
이 문서는 DDL을 복제하지 않는다.
테이블 구조 확인은 해당 파일을 직접 읽는다.

## Flyway conventions

### 파일 명명 규칙

```
V{버전}__{설명}.sql
예: V1__create_payment_tables.sql
    V2__add_outbox_table.sql
```

### 규칙

- 한 번 적용된 파일은 절대 수정하지 않는다.
- 컬럼 추가/변경은 새 버전 파일로 작성한다.
- 파일 하나에 하나의 논리적 변경만 담는다.

## Module DB ownership

| 모듈 | migration 경로 |
|------|--------------|
| payment-service | src/main/resources/db/migration/ (V1~V7) |
| order-service | src/main/resources/db/migration/ (V1) |
| merchant-limit-service | src/main/resources/db/migration/ (V1) |
| risk-management-service | src/main/resources/db/migration/ (V1) |
| product-service | src/main/resources/db/migration/ (V1) |

## Naming conventions

| 대상 | 규칙 | 예시 |
|------|------|------|
| 테이블 | snake_case | cancel_request |
| 컬럼 | snake_case | created_at |
| PK | id (BIGINT AUTO_INCREMENT) | id |
| FK | {참조테이블}_id | payment_id |
| UK | uk_{테이블}_{컬럼} | uk_cancel_request_dedup |
| INDEX | idx_{테이블}_{컬럼} | idx_outbox_status |
| 날짜/시간 | DATETIME(3) UTC | created_at |

## Datetime convention

- 모든 시각은 UTC DATETIME(3)으로 저장한다.
- KST 기준 날짜가 필요한 경우 kst_date DATE 컬럼을 별도로 둔다.
- 서버에서 KST 날짜를 계산해 쿼리에 사용한다.

## Index strategy

### 기본 원칙

- PK 외 UK는 비즈니스 멱등키에만 사용한다.
- 조회 조건에 쓰이는 컬럼에 INDEX를 건다.
- 복합 인덱스는 카디널리티 높은 컬럼을 앞에 둔다.

### 주요 인덱스 결정 근거

| 인덱스 | 이유 |
|--------|------|
| cancel_request(payment_id, dedup_key) UK | 멱등성 보장. dedup_key는 idempotency_key(클라 optional) 있으면 `ik:{key}`, 없으면 `ch:{request_hash}` 접두 generated 컬럼 (uk_cancel_request_dedup) |
| cancel_event_outbox(status) | Outbox 스케줄러 PENDING 조회 |
| compensation_retry(status, next_retry_at) | 재시도 스케줄러 조회 |
| merchant_cancel_usage(merchant_id, kst_date) UK | 한도 행 유일성 + FOR UPDATE |
| cancel_request(status, created_at) | 복구 스케줄러 5분 초과 건 조회 |
| cancel_usage_history(cancel_request_id) UK | risk-service 이중 차감 방어 |

## Soft delete 정책

취소 관련 테이블은 soft delete를 사용하지 않는다.
상태 컬럼(status)으로 논리적 상태를 표현한다.
데이터는 감사 목적으로 보존한다.

## 주의사항

- FK 제약은 성능 이슈로 애플리케이션 레벨에서 관리한다.
  DB에 FOREIGN KEY를 선언하지 않는다.
- 금액 컬럼은 DECIMAL(19,2)를 사용한다.
  FLOAT, DOUBLE 사용 금지.