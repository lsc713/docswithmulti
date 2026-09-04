# 이커머스 결제 취소 시스템 (multicommerce)

분산 환경에서 결제 취소가 만드는 **멱등성 · 동시성 · 분산 트랜잭션** 문제를 다루는 시스템입니다.
네트워크 재시도로 인한 중복 환불, 가맹점 한도 동시 차감, 서버 재시작 시 상태 불일치를
각각 다른 수단으로 해결하고, 그 선택을 부하 실측으로 검증했습니다.

`2026.04 ~ 진행 중` · 1인 설계 · 구현
Java 21 · Spring Boot 3 · Spring Data JPA · MySQL 8 · Kafka 3 · Redis · Flyway · Gradle 멀티 모듈 · React

8개 서비스(api-gateway · user · product · order · payment · risk-management · merchant-limit · settlement)가
각자 독립 DB를 씁니다. 취소 한 건이 지나는 경로는 그중 4개입니다 —
**payment → risk-management → merchant-limit** 까지가 동기 HTTP, 이후 **order** 는 Kafka 이벤트로 상태를 맞춥니다.

---

## 무엇을 읽으면 되나

이 저장소는 코드보다 **결정 기록**이 더 많습니다. 관심사에 따라 들어가는 문이 다릅니다.

| 궁금한 것 | 문서 |
|---|---|
| 왜 이 구조인가 · 모듈 경계 · 취소 플로우 | [docs/architecture.md](docs/architecture.md) |
| **무엇을 고르고 무엇을 기각했나** | [sysdesign/design-decisions.md](sysdesign/design-decisions.md) |
| 문제 8건과 해결 요약 | [sysdesign/resume-project.md](sysdesign/resume-project.md) |
| 부하를 어떻게 쟀나 · 원칙과 여정 | [docs/load-test/measurement-journey.md](docs/load-test/measurement-journey.md) |
| 그 처리량이 충분한가 (수요 ↔ 공급) | [docs/load-test/capacity-planning.md](docs/load-test/capacity-planning.md) |
| 스케일아웃은 천장을 밀었나 | [docs/load-test/k3s-scaleout-results.md](docs/load-test/k3s-scaleout-results.md) |
| 예상 질문과 답변 | [sysdesign/interview-qa.md](sysdesign/interview-qa.md) |
| 부하 스크립트 실물 | [k6/](k6/) |

선택의 배경이 된 정리는 블로그에 따로 있습니다 —
[InnoDB Durability · Concurrency · 동시성 제어 방식 비교 · 멱등성](https://lsc713.github.io/quarSync/posts/notes/)

---

## 핵심 결정 셋

**1. 멱등성 — Redis가 아니라 DB 유니크 키**
Idempotency-Key + DB UK를 API · Kafka Consumer · 보상 트랜잭션 · 보상 재시도 4개 레이어에 적용했습니다.
Redis를 쓰지 않은 이유는 단순합니다 — 캐시가 죽으면 멱등성이 깨지고, 그건 환불 2회, 즉 금융 사고입니다.

**2. 한도 차감 — 비관적 락, 낙관적 락이 아니라**
`SELECT ... FOR UPDATE` 로 조회와 차감을 한 트랜잭션에 묶었습니다.
낙관적 락은 한도 초과 시 재시도해도 결과가 같아 재시도 횟수만 늘고, Redis 분산락은 Redis 장애가 곧 취소 전체 중단입니다.

**3. 트랜잭션 경계 — 외부 호출마다 끊기**
HTTP 경계를 넘으면 원자성이 깨지므로 취소 한 건을 TX 3단계로 나누고 사이에 외부 호출을 뒀습니다.
끊긴 자리는 `CancelRequest` 상태 머신(PENDING · PROCESSING · COMPLETED · FAILED)이 이어받아,
서버가 어디서 죽어도 복구 스케줄러가 이어서 처리합니다. 실패 시에는 보상 트랜잭션이 한도를 되돌립니다.

---

## 측정

| 항목 | 값 | 근거 |
|---|---|---|
| 취소 경로 천장 | **~220 rps** | 이력 커밋 6회 → 4회로 줄인 뒤 재측정 |
| 운용 기준선(knee) | ~190 rps | 220은 p95 ~2s 라 SLO 로는 못 씀 |
| 병목 | payment_db 커밋 fsync | 앱 티어가 아님 — replica ×3 이 천장을 18%만 밀었음 |
| 핫 가맹점 거부율 | **99.9% → 0%** | 경합을 거부가 아니라 지연으로 흡수 |

### 측정에서 한 번 틀렸던 것

`×1 replica 가 210 rps 에서 이미 포화(221ms)` 라고 판정한 적이 있습니다. **거짓이었습니다.**
간섭 요인을 격리하고 다시 재니 같은 조건에서 57ms 였고, 원래의 가설(앱 티어는 천장이 아니다)이
오히려 지지됐습니다. 오염된 런 하나가 옳은 결론을 거짓으로 반증할 뻔한 사례라
[k3s-scaleout-results.md](docs/load-test/k3s-scaleout-results.md) 에 남겼습니다.

---

## 로컬 기동

### 준비물

- Java 21
- Node.js 22+
- Docker / Docker Compose
- Toss Payments 테스트 클라이언트 키와 시크릿 키

루트에 커밋하지 않는 `.env` 파일을 만들고 본인의 Toss 개발자센터 테스트 키를 넣습니다.

```dotenv
TOSS_CLIENT_KEY=test_ck_...
TOSS_SECRET_KEY=test_sk_...
TOSS_BASE_URL=https://api.tosspayments.com

# 로컬 HTTP 쿠키 및 최초 관리자 계정 설정
AUTH_COOKIE_SECURE=false
APP_ADMIN_BOOTSTRAP_EMAILS=admin@example.com
```

`APP_ADMIN_BOOTSTRAP_EMAILS`에 등록한 이메일은 **처음 회원가입할 때만** `ADMIN`이 됩니다. 이미 가입된 계정의 역할은 자동 변경되지 않습니다.

### 실행 순서

#### 1. 인프라

```bash
docker compose up -d
```

MySQL, Redis, Kafka, MinIO가 준비될 때까지 기다린 뒤 애플리케이션을 실행합니다.

#### 2. 백엔드

각 명령은 별도 터미널에서 실행합니다. 의존 순서는 `merchant-limit → risk → payment → gateway`이고, 나머지는 인프라 준비 후 병렬 실행해도 됩니다.

```bash
./gradlew :merchant-limit-service:bootRun   # 8082
./gradlew :risk-management-service:bootRun # 8083
```

최초 DB라면 결제에서 사용하는 `merchantId=1`의 취소 한도를 한 번 생성합니다.

```bash
curl -X POST http://localhost:8082/v1/merchants \
  -H 'Content-Type: application/json' \
  -d '{"merchantKey":"merchant_001","name":"테스트 가맹점","cancelPeriodDays":30,"dailyLimit":10000000}'
```

이어서 나머지 서비스를 실행합니다.

```bash
set -a; source .env; set +a; ./gradlew :payment-service:bootRun # 8080
./gradlew :order-service:bootRun                                 # 8081
./gradlew :product-service:bootRun                               # 8084
set -a; source .env; set +a; ./gradlew :user-service:bootRun    # 8085
./gradlew :api-gateway:bootRun                                   # 8000
```

정산 소비자까지 확인할 때만 다음 서비스를 추가합니다.

```bash
./gradlew :settlement-service:bootRun # 8086
```

#### 3. 프론트엔드

```bash
cd frontend
npm install
VITE_API_BASE_URL=http://localhost:8000 npm run dev
```

- 쇼핑몰: <http://localhost:5173>
- 관리자: <http://localhost:5173/admin/login>
- Kafka UI: <http://localhost:8989>
- MinIO 콘솔: <http://localhost:9001>

관리자 이메일로 쇼핑몰에서 로그인해도 취소 요청 화면(`/admin/cancel-requests`)으로 이동합니다.

## 결제 과정

1. 사용자가 로그인하고 상품 옵션을 선택해 주문서를 작성합니다.
2. 프론트가 `POST /v1/orders`로 주문을 생성합니다.
3. 프론트가 `POST /v1/payment-attempts`로 결제 시도를 준비합니다. 서버가 금액과 주문 상태를 검증하고 Toss 클라이언트 키를 반환합니다.
4. 프론트가 Toss Payments SDK 결제창을 열고 사용자가 테스트 결제를 진행합니다.
5. Toss가 `/payment/success`로 돌려보내면 프론트가 `POST /v1/payment-attempts/{paymentRequestId}/confirm`을 호출합니다.
6. payment-service가 Toss 승인 API를 호출하고 성공한 결제를 `COMPLETED`로 저장합니다. 승인 결과가 불명확하면 결제 시도 조회 API를 폴링합니다.

Toss 테스트 키를 사용하면 실제 청구는 발생하지 않습니다. 카드 정보는 애플리케이션 서버가 아니라 Toss 결제창에만 입력합니다.

## 결제 취소 요청과 처리

1. 구매자가 `주문내역`에서 `취소 요청`을 누르고 사유를 입력합니다.
2. `POST /v1/payments/{paymentKey}/cancel-requests`가 승인 대기 건을 `REQUESTED`로 저장합니다. 이 시점에는 결제가 취소되지 않습니다.
3. `ADMIN` 또는 해당 `MERCHANT`가 관리자 취소 요청 화면에 로그인합니다.
4. `승인`은 `POST /v1/cancel-requests/{id}/approve`를 호출합니다. 전체 결제 상품을 대상으로 취소 코어와 재고 복구 이벤트가 실행됩니다.
5. `반려`는 사유와 함께 `POST /v1/cancel-requests/{id}/reject`를 호출하며 PG 취소는 실행하지 않습니다. 구매자는 반려된 요청을 다시 제출할 수 있습니다.

### 현재 PG 프로필 주의사항

- `mock-pg`: 결제 승인과 취소를 모두 Mock 클라이언트가 처리하므로 Toss API를 호출하지 않습니다.
- `mock-pg` 없음: 승인과 취소 모두 실제 Toss HTTP 클라이언트를 사용하므로 Toss 테스트 키 설정이 필요합니다.
- `prod,mock-pg`: 운영 환경의 Mock PG 사용을 막기 위해 유효하지 않으며 payment-service 기동이 거부됩니다.

## 검증

```bash
./gradlew test
cd frontend && npm run test:unit && npm run build
```
