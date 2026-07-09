# CLAUDE.md

패션 이커머스 결제 취소 시스템. 세션 시작 시 이 파일을 먼저 읽고 아래 참조 문서로 이동한다.
상세는 CLAUDE.md에 복제하지 않고 `docs/`에 둔다 — 이 파일은 **지도 + 불변식 + 가드레일**이다.

---

## 모듈 구성

| 모듈 | 역할 | 포트 |
|------|------|------|
| `payment-service` | 결제 취소 핵심 로직 | 8080 |
| `order-service` | 주문/주문아이템 상태 동기화 | 8081 |
| `merchant-limit-service` | 가맹점별 일일 취소한도 원본 관리 | 8082 |
| `risk-management-service` | 취소 가능 여부 검증 + 소진 한도 관리 | 8083 |
| `product-service` | 상품/SKU/재고 관리 (미구현) | 8084 |

스택: Java 21 · Spring Boot 3.x · Spring Data JPA + QueryDSL · MySQL 8.0(모듈별 독립) · Flyway · Kafka 3.x(3-broker) · Gradle · JUnit 5 + Mockito + Testcontainers

---

## 필수 참조 문서 (코드보다 문서가 먼저)

```
@docs/domain-rules.md              비즈니스 규칙 원본
@docs/error-catalog.md             에러 코드 전체 목록
@docs/api-spec.md                  API 요청/응답 스펙
@docs/db-schema.md                 DB 규칙 및 인덱스 전략
@docs/kafka-design.md              Kafka 설계 및 운영
@docs/architecture.md              전체 시스템 설계
@docs/contributing.md              코드 작성 기준
@docs/agent.md                     작업 행동 규칙
@sysdesign/cancel-design.md        취소 플로우 상세 (멱등성·TX 경계·스케줄러·Kafka)
@docs/conventions/architecture.md  레이어 구조 + 예외 계층 규약
@docs/STATUS.md                    구현 진행 상태
```

취소 관련 코드는 `sysdesign/cancel-design.md`를 읽지 않고 작성 금지.
DDL은 각 모듈 `db/migration/V1__create_*_core.sql ~ V7`을 직접 읽는다.

---

## 핵심 불변식 (상세 → sysdesign/cancel-design.md)

**멱등성**
- `request_hash = SHA-256(paymentKey + paymentItemIds 오름차순)`, `cancel_request(payment_id, request_hash)` UK로 따닥 차단. 서버가 생성 (Idempotency-Key 헤더 없음).
- FAILED 재시도: 새 INSERT 금지 → 기존 FAILED 건을 PENDING으로 UPDATE.

**TX 경계** (이력 `cancel_request_history`는 항상 TX 밖에서 별도 실행)
- TX1: CancelRequest PENDING INSERT (risk 호출 전)
- TX2: CancelRequest PROCESSING UPDATE (risk 성공 후)
- TX3: PaymentItem + Payment + CancelRequest(COMPLETED) 원자 처리 + `kafkaTemplate.send()` 인라인 발행. 발행 실패 시 TX3 롤백 → processing-recovery 재처리.
- TX3에서는 `findAllByPaymentIdForUpdate()`로 재조회 후 Payment 상태 재계산 (조회 시점 데이터 사용 금지).

**daily_limit 조회 순서** (2순위 건너뛰고 3순위 호출 금지)
1. Redis `daily_limit:{merchantId}:{kstDate}`
2. `merchant_cancel_usage.daily_limit` (DB 스냅샷 — Redis 장애 시 merchant-limit 호출 없이 처리)
3. merchant-limit HTTP (최초 요청 / 스냅샷 없을 때만)

**스케줄러 3개** (payment-service, Redis 분산락)
- pending-recovery(60s): PENDING 5분 초과 → risk check → 차감됐으면 보상 → FAILED
- processing-recovery(60s): PROCESSING 5분 초과 → PG사 조회 → TX3 재실행/보상 (Kafka 재발행 포함)
- compensation-retry(30s): compensation_retry → risk 보상 API 재시도

**Kafka 발행**
- `payment.cancelled`: payment-service가 **TX3 인라인** 발행. 파티션 키 `paymentKey`.
- `merchant.limit.updated { merchantId }`: merchant-limit-service는 **Outbox 패턴** 발행. 파티션 키 `merchantId`.

---

## 절대 하지 말 것

```
- 운영 DB 직접 DDL 금지 (Flyway로만) / Flyway 적용 파일 수정 금지 (새 버전 추가)
- 모듈 간 DB 직접 접근 금지 (HTTP 또는 Kafka 경유)
- domain 레이어에 Spring/JPA 어노테이션 금지
- 시크릿/비밀번호 하드코딩 금지
- 테스트 없이 구현 완료 처리 금지
- domain-rules.md 확인 없이 비즈니스 로직 작성 금지

[취소 플로우 전용]
- cancel-design.md 안 읽고 취소 코드 작성 금지
- request_hash 없이 CancelRequest INSERT 금지
- 이력(cancel_request_history)을 TX 1/2/3 안에 포함 금지
- TX3에서 조회 시점 데이터로 Payment 재계산 금지 (findAllByPaymentIdForUpdate() 재조회)
- daily_limit Redis Miss → 바로 merchant-limit HTTP 금지 (DB 스냅샷 먼저)
- FAILED 재시도 시 새 INSERT 금지 (기존 FAILED → PENDING UPDATE)
```

---

## 실행 명령어

```bash
./gradlew build                       # 전체 빌드
./gradlew :payment-service:test       # 모듈 테스트
docker compose up -d                  # 로컬 인프라 (앱은 gradle로 실행)
./gradlew :payment-service:flywayInfo # 마이그레이션 상태
```

- Kafka UI: http://localhost:8989
- 부하 실측: `infra/load-test/` + `docs/load-test/`(topology.html, measurement-journey.md). 종료 시 `terraform destroy`.

---

## 작업 규칙

- 파일 직접 읽기 전 `qmd`로 먼저 검색 (query / get / multi_get / status). 부족할 때만 파일을 직접 읽는다.
- 요구사항 불명확 시: domain-rules.md·cancel-design.md 확인 → 없으면 가정을 1문장으로 **응답 첫 줄에** 명시 후 진행.
  - 예) `가정: 취소 기간 초과 여부는 Payment.created_at 기준으로 판단한다.`
