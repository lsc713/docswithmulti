# multicommerce

주문, 결제, 결제 취소 승인 흐름을 로컬에서 실행하는 멀티 모듈 Spring Boot + React 예제입니다.

## 준비물

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

## 로컬 기동 순서

### 1. 인프라

```bash
docker compose up -d
```

MySQL, Redis, Kafka, MinIO가 준비될 때까지 기다린 뒤 애플리케이션을 실행합니다.

### 2. 백엔드

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

### 3. 프론트엔드

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
