# Mock PG 개발 모드

외부 Toss Payments 호출 없이 주문, 결제 승인, 취소 승인 이후 기능을 개발할 때 사용합니다.

인프라와 다른 백엔드는 평소와 같이 실행하고 payment-service에 `local,mock-pg` 프로필을 지정합니다.

```bash
SPRING_PROFILES_ACTIVE=local,mock-pg ./gradlew :payment-service:bootRun
```

프론트엔드는 Mock 결제 공급자를 지정합니다.

```bash
cd frontend
VITE_API_BASE_URL=http://localhost:8000 VITE_PAYMENT_PROVIDER=mock npm run dev
```

이 모드에서 `결제하기`를 누르면 Toss SDK나 Toss API를 호출하지 않고 기존 `/payment/success` 콜백과 서버 confirm API를 거쳐 결제가 완료됩니다. 관리자 취소 승인은 기존 `MockPgCancelClient`를 사용하므로 외부 PG 취소 호출도 발생하지 않습니다.

## 전체 결제·취소 E2E

payment-service는 Mock 프로필로 실행합니다.

```bash
SPRING_PROFILES_ACTIVE=local,mock-pg ./gradlew :payment-service:bootRun
```

프론트엔드는 Mock 공급자로 실행합니다.

```bash
cd frontend
VITE_API_BASE_URL=http://localhost:8000 VITE_PAYMENT_PROVIDER=mock npm run dev
```

별도 터미널에서 전체 구매→취소 승인 흐름을 실행합니다.

```bash
cd frontend
AUTH_COOKIE_SECURE=false VITE_PAYMENT_PROVIDER=mock E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/mock-pg-flow.spec.js
```

`E2E_ADMIN_EMAIL`은 user-service를 기동할 때 설정한 `APP_ADMIN_BOOTSTRAP_EMAILS`의 이메일과 일치해야 합니다. 이 계정은 Playwright global setup이 회원가입하여 관리자 권한으로 카탈로그와 취소 요청을 처리합니다.

실제 Toss 연동을 확인할 때는 `mock-pg` 프로필과 `VITE_PAYMENT_PROVIDER=mock`을 모두 제거하고 Toss 테스트 키를 설정합니다.
