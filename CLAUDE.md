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
| `product-service` | 상품/SKU + 재고 예약·복원 수명주기 (v3.0, 최소 카탈로그) | 8084 |
| `user-service` | 회원가입/로그인/JWT 발급·갱신·무효화 (v2.0) | 8085 |
| `api-gateway` | 단일 진입점·JWT 검증·신뢰헤더 전달 (v2.0, 무상태) | 8000 |

스택: Java 21 · **Spring Boot 4.0.5 / Spring Security 7** · Spring Data JPA + QueryDSL · MySQL 8.0(모듈별 독립) · Flyway · Kafka 3.x(3-broker) · Gradle · JUnit 5 + Mockito + Testcontainers

---

## 필수 참조 문서 (코드보다 문서가 먼저)

```
@docs/domain-rules.md              비즈니스 규칙 원본
@docs/error-catalog.md             에러 코드 전체 목록
@docs/api-spec.md                  API 요청/응답 스펙
@docs/db-schema.md                 DB 규칙 및 인덱스 전략
@docs/kafka-design.md              Kafka 설계 및 운영
@docs/architecture.md              전체 시스템 설계
@docs/architecture/index.html      시스템 토폴로지 + 취소 플로우 시각화 (mermaid; auth-gateway(v2.0 인증 경계)·cancel-flow·perf-anatomy(1~4막 실측)·k3s-scaleout(토폴로지·flow·검증5종) 연결)
@docs/contributing.md              코드 작성 기준
@docs/agent.md                     작업 행동 규칙
@sysdesign/cancel-design.md        취소 플로우 상세 (멱등성·TX 경계·스케줄러·Kafka)
@docs/conventions/architecture.md  레이어 구조 + 예외 계층 규약
@docs/STATUS.md                    구현 진행 상태
```

취소 관련 코드는 `sysdesign/cancel-design.md`를 읽지 않고 작성 금지.
DDL은 각 모듈 `db/migration/V1__create_*_core.sql ~ V7`을 직접 읽는다.

---

## 확장 기능 (v2.0 인증 경계 · v3.0 재고 — 둘 다 main 반영됨)

- **v2.0 인증 경계**: api-gateway가 JWT를 **단일 지점에서 검증** → 신뢰헤더(X-User-Id/X-User-Role/X-Merchant-Id)를 downstream에 전달, downstream은 재검증 없이 헤더만 신뢰. payment 취소는 역할 인가(ADMIN=전체, MERCHANT=본인 가맹점, USER=본인 결제 자가취소, 그 외 403). **배포 시 NetworkPolicy로 payment ingress를 게이트웨이 파드로만 제한 필수** — 없으면 헤더 스푸핑으로 인가 우회. 시각화: `docs/architecture/auth-gateway.html`.
- **v3.0 SKU 재고 수명주기**: 결제 생성 시 product에 재고 **동기 예약**(오버셀 방지 원자 조건부 UPDATE, product 장애/부족 시 fail-closed로 결제 거부) → 취소 시 `payment.cancelled`(payload에 skuId/quantity)로 product가 SKU 재고 **복원**. reserve/release는 paymentKey 멱등. **취소 코어 불변** — CancelTxWriter.buildPayload에 2필드 추가 외 취소 TX/멱등/스케줄러/outbox 무변경. 설계: `@docs/superpowers/specs/2026-07-30-sku-stock-lifecycle-design.md`.
- **어드민 콘솔 v1.0**: 프론트(admin.html + react-router 라우팅) + `GET /v1/admin/users` 신설(user-service). `POST /v1/products`는 게이트웨이 신뢰헤더 `X-User-Role`만으로 ADMIN 인가 판단 — **배포 시 NetworkPolicy로 product ingress를 게이트웨이 파드로만 제한 필수**(payment와 동일 클래스, `infra/k8s/networkpolicy/product-ingress.yaml`) — 없으면 헤더 스푸핑으로 ADMIN 인가 우회. 취소 코어·스토어프론트 불변식 무영향.

---

## 핵심 불변식 (상세 → sysdesign/cancel-design.md)

**멱등성**
- `request_hash = SHA-256(paymentKey + paymentItemIds 오름차순)`(content-hash, 항상 생성). 클라 `Idempotency-Key` 헤더가 있으면 그 값 우선, 없으면 request_hash로 fallback.
- `dedup_key` = 있으면 `ik:{key}`, 없으면 `ch:{request_hash}`. `cancel_request(payment_id, dedup_key)` UK(`uk_cancel_request_dedup`)로 따닥 차단.
- 같은 `Idempotency-Key`로 이전과 다른 요청(request_hash 불일치) 재사용 시 `IDEMPOTENCY_KEY_CONFLICT` 409 거부.
- FAILED 재시도: 새 INSERT 금지 → 기존 FAILED 건을 PENDING으로 UPDATE.

**TX 경계** (이력 `cancel_request_history`는 항상 TX 밖에서 별도 실행)
- TX1: CancelRequest PENDING INSERT (risk 호출 전)
- TX2: CancelRequest PROCESSING UPDATE (risk 성공 후)
- TX3: PaymentItem + Payment + CancelRequest(COMPLETED) 원자 처리 + `cancel_event_outbox` INSERT(같은 TX, 원자적). Kafka 발행 자체는 TX 밖 — outbox 발행 스케줄러가 담당(OUTBOX 정식, `cancel.publish.mode` 기본값). INLINE/INLINE_ASYNC는 벤치·학습용으로 코드는 남아있으나 기본 비활성.
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
- `payment.cancelled`: payment-service가 **OUTBOX 정식**(TX3 원자 outbox INSERT + 커밋 후 이벤트 wake relay)으로 발행. TX3 커밋 성공 시 outbox 발행 스케줄러(`CancelEventOutboxPublisher`, poll 10s)가 PENDING 행을 배치 발행하고, 커밋 직후 Redisson wake로 즉시 트리거(비권위 — 실패해도 poll이 backstop). 파티션 키 `cancelRequestId`(`CancelTxWriter`). order 컨슈머가 전체 아이템 재계산 + 주문 행 락으로 **순서 무관하게 수렴**하므로 결제 단위 순서 보장 불필요(cancelRequestId가 파티션 분산에 유리).
  - 발행 실패는 `max-retries`까지 재시도 후 DEAD 전이 + 알림(`OperationAlertPort`) — TX 롤백 없음(outbox INSERT는 TX3에 이미 원자 커밋됨).
  - PUBLISHED 행은 `retention-days` 경과 후 별도 purge 스케줄러가 삭제.
  - `cancel.publish.mode`(기본 `OUTBOX`)로 전환 가능. `INLINE`(TX3 안에서 `kafkaTemplate.send()` 직접 호출, 발행 실패 시 TX3 롤백)·`INLINE_ASYNC`(fire-and-forget, dual-write 안전하지 않음)는 벤치/학습 전용 — 프로덕션 기본 아님.
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
- white-box 관측: 실측 compose에서 `OTEL_JAVAAGENT`(트레이스)·`LOADTEST_QUERYCOUNT_ENABLED`(쿼리수) 토글. Tempo는 obs 스택에 포함.

---

## 작업 규칙

- 파일 직접 읽기 전 `qmd`로 먼저 검색 (query / get / multi_get / status). 부족할 때만 파일을 직접 읽는다.
- 요구사항 불명확 시: domain-rules.md·cancel-design.md 확인 → 없으면 가정을 1문장으로 **응답 첫 줄에** 명시 후 진행.
  - 예) `가정: 취소 기간 초과 여부는 Payment.created_at 기준으로 판단한다.`
