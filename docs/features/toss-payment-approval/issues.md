# 토스 테스트 결제 승인 — 이슈 분해

> 상태: 확정 및 GitHub 등록 완료
>
> 기준 문서: [prd.md](./prd.md)
>
> GitHub: [#115](https://github.com/lsc713/docswithmulti/issues/115) →
> [#116](https://github.com/lsc713/docswithmulti/issues/116) →
> [#117](https://github.com/lsc713/docswithmulti/issues/117) →
> [#118](https://github.com/lsc713/docswithmulti/issues/118) →
> [#119](https://github.com/lsc713/docswithmulti/issues/119)

## 의존 순서

```text
#1 승인 전 주문 상태
  → #2 서버 가격 권위
    → #3 결제 시도·토스 승인 API
      → #4 취소·결과 불명 복구
        → #5 프런트 전환·레거시 제거·배포
```

각 이슈는 앞 이슈가 배포 가능한 상태라는 전제에서 시작한다. #3과 #4 동안에는 기존 체크아웃
경로를 유지하고 신규 API를 먼저 완성한다. #5에서 프런트가 신규 API로 전환되는 것과 동시에
PG 호출 없이 `COMPLETED`를 만들던 기존 경로를 제거한다.

---

## Issue 1 — [결제 완료 전 주문을 배송 대상에서 제외한다](https://github.com/lsc713/docswithmulti/issues/115)

**예상 크기:** 0.5~1일  
**의존성:** 없음

### 목적

주문을 생성하자마자 `DELIVERY_WAITING`으로 두는 현재 동작을 고친다. 주문은 `PENDING`으로
생성하고, 실제 `payment.completed` 이벤트를 받은 경우에만 배송 대기로 전이한다. 현재의 즉시
완료 결제 경로도 동일 이벤트를 발행하므로 이 이슈만 배포해도 기존 체크아웃은 계속 완결된다.

### 변경 범위

- order-service의 신규 주문 초기 상태를 `PENDING`으로 변경한다.
- `payment.completed` payload에 하위 호환 필드 `orderId`를 추가한다.
- order-service가 해당 이벤트를 멱등 소비해 `PENDING → DELIVERY_WAITING`으로 전이한다.
- settlement-service가 사용 중인 기존 완료 이벤트 필드와 계산은 유지한다.

### Acceptance Criteria

- [ ] Given 로그인 구매자가 유효한 항목으로 주문을 생성했을 때, When order-service가 주문을 저장하면, Then 저장된 주문 상태는 `PENDING`이고 `DELIVERY_WAITING`이 아니다.
- [ ] Given `PENDING` 주문과 그 주문 ID를 포함한 `payment.completed` 이벤트가 있을 때, When order-service가 이벤트를 소비하면, Then 해당 주문 상태는 `DELIVERY_WAITING`으로 한 번 전이된다.
- [ ] Given 같은 `payment.completed` 이벤트가 이미 처리됐을 때, When 동일 이벤트가 다시 전달되면, Then 주문 상태는 `DELIVERY_WAITING`으로 유지되고 추가 상태 변경은 발생하지 않는다.
- [ ] Given 기존 필드와 새 `orderId`가 포함된 완료 이벤트가 있을 때, When settlement-service가 이벤트를 소비하면, Then 기존 `paymentKey` 기준 SALE 원장이 한 건 기록되고 총액 계산은 변경되지 않는다.
- [ ] Given 결제 완료 이벤트를 받지 않은 `PENDING` 주문이 있을 때, When 주문 상태를 조회하면, Then 주문은 `DELIVERY_WAITING`·`DELIVERING`·`DELIVERED` 중 어느 상태도 아니다.

---

## Issue 2 — [product-service의 SKU 가격으로 결제 금액을 고정한다](https://github.com/lsc713/docswithmulti/issues/116)

**예상 크기:** 1일  
**의존성:** Issue 1

### 목적

브라우저의 `itemAmount`가 결제·정산 금액이 되는 신뢰 경계 문제를 제거한다. product-service가
재고 예약 시점의 SKU 단가를 저장하고 반환하며, payment-service는 이 단가와 수량으로만 결제
금액과 결제 항목 금액을 만든다. 기존 즉시 완료 체크아웃에서도 바로 적용되어 독립적인 보안
개선으로 배포할 수 있다.

### 변경 범위

- 재고 예약 요청에 `productId`를 포함하고 SKU 소유 상품과 일치하는지 검증한다.
- 예약 행에 예약 시점 단가를 스냅샷으로 저장한다.
- 예약 응답에 항목별 `skuId`, `productId`, `unitPrice`, `quantity`를 반환한다.
- 같은 `paymentRequestId` 예약 재시도는 최초 스냅샷을 반환한다.
- payment-service는 요청 `itemAmount` 대신 `unitPrice × quantity`의 합을 사용한다.

### Acceptance Criteria

- [ ] Given SKU 서버 단가가 10,000원이고 수량이 2개이며 요청 `itemAmount`가 1원일 때, When payment-service가 재고를 예약하고 결제를 생성하면, Then payment와 완료 이벤트의 총액은 20,000원이다.
- [ ] Given SKU가 상품 A에 속하고 요청의 `productId`가 상품 B일 때, When 예약을 요청하면, Then product-service는 4xx로 거절하고 재고 수량·예약 행·payment 행은 모두 변경되지 않는다.
- [ ] Given 동일 `paymentRequestId`와 동일 항목의 첫 예약이 단가 10,000원으로 성공했을 때, When SKU 현재 단가가 바뀐 뒤 같은 예약을 재시도하면, Then 응답 단가는 최초 스냅샷 10,000원이고 재고는 추가 차감되지 않는다.
- [ ] Given 여러 SKU 중 하나의 재고가 부족할 때, When 한 요청으로 전체 항목을 예약하면, Then 모든 SKU의 재고와 예약 행은 요청 전 값으로 유지되고 payment 행은 생성되지 않는다.
- [ ] Given 브라우저가 음수·소수·문자열 금액을 보내거나 금액 필드를 생략했을 때, When 유효한 SKU와 수량으로 결제를 생성하면, Then 승인 기준 금액은 서버 단가와 정수 수량만으로 계산된다.

---

## Issue 3 — [결제 시도를 저장하고 토스 테스트 결제를 서버에서 승인한다](https://github.com/lsc713/docswithmulti/issues/117)

**예상 크기:** 1일  
**의존성:** Issue 2

### 목적

토스 결제창이 사용할 결제 시도를 `PENDING`으로 준비하고, 인증 성공 결과를 payment-service가
검증·승인해 `COMPLETED`로 만드는 신규 백엔드 경로를 제공한다. 기존 체크아웃은 아직 신규 API를
호출하지 않으므로 이 이슈는 기존 사용자 흐름을 깨지 않고 API 소비자가 독립 검증할 수 있다.

### 변경 범위

- `payment_request_id`, nullable·200자 `payment_key`, `FAILED`, 주문별 활성 시도 유일성을 위한
  Flyway 마이그레이션과 기존 데이터 backfill을 추가한다.
- `POST /v1/payment-attempts` 준비 API를 추가한다.
- `POST /v1/payment-attempts/{paymentRequestId}/confirm` 승인 API를 추가한다.
- `GET /v1/payment-attempts/{paymentRequestId}` 소유자 상태 조회 API와 product-service의 고아
  예약 확인용 내부 상태 조회를 추가한다.
- 토스 승인·조회 애플리케이션 포트와 실제 Toss HTTP 어댑터를 추가한다.
- 승인 전에 사용자 소유권, `NORMAL`, 저장된 서버 금액, 토스 `orderId`와 `amount`를 검증한다.
- `pgPaymentKey` 연결과 `PENDING → COMPLETED` 전이를 직렬화하고 완료 아웃박스를 같은 트랜잭션에
  기록한다.

### Acceptance Criteria

- [ ] Given 소유권과 재고가 유효한 주문이 있을 때, When 구매자가 결제 준비 API를 호출하면, Then 응답은 UUID v4 `paymentRequestId`, 서버 계산 정수 KRW 금액, 주문명, 원본 사용자 ID를 포함하지 않는 `customerKey`와 공개 클라이언트 키를 포함하고 DB 상태는 `PENDING`이며 `payment_key`는 NULL이다.
- [ ] Given 한 주문에 활성 `PENDING` 시도가 있을 때, When 같은 주문으로 준비 요청 두 개가 동시에 실행되면, Then DB에는 그 주문의 `PENDING` 결제가 한 건만 존재하고 패자 요청의 재고 예약은 해제 또는 재시도 큐에 기록된다.
- [ ] Given 저장 금액·사용자·`paymentRequestId`가 일치하는 토스 인증 결과가 있을 때, When 승인 API가 토스 승인 200 응답을 받으면, Then 실제 `pgPaymentKey`가 저장되고 상태는 `COMPLETED`이며 완료 아웃박스는 한 건 존재한다.
- [ ] Given 브라우저 금액이 저장 금액과 다르거나 토스 `orderId`가 `paymentRequestId`와 다를 때, When 승인 API를 호출하면, Then 토스 승인 HTTP 호출 횟수는 0이고 payment 상태는 `PENDING`이며 `PAYMENT_CONFIRM_MISMATCH` 경고 로그에는 `paymentRequestId`만 기록되고 PG secret은 기록되지 않는다.
- [ ] Given 이미 완료된 `paymentRequestId`와 같은 승인 입력이 있을 때, When 승인 API를 다시 호출하면, Then 기존 완료 응답을 반환하고 토스 승인 호출·완료 아웃박스·재고 차감 수는 증가하지 않는다.
- [ ] Given 서로 다른 결제 시도가 같은 `pgPaymentKey`를 연결하려 할 때, When 두 번째 연결을 시도하면, Then 요청은 충돌 응답으로 거절되고 두 결제 레코드는 하나의 `pgPaymentKey`를 공유하지 않는다.
- [ ] Given 기존 완료 payment 행이 있는 데이터베이스에 마이그레이션을 적용할 때, When 애플리케이션이 시작되면, Then 기존 `payment_key`와 취소·정산 조회 결과는 유지되고 각 기존 행에는 고유 `payment_request_id`가 존재한다.

---

## Issue 4 — [사용자 취소와 승인 결과 불명 결제를 최종 상태로 수렴시킨다](https://github.com/lsc713/docswithmulti/issues/118)

**예상 크기:** 1일  
**의존성:** Issue 3

### 목적

결제창 취소는 즉시 재고를 돌려주고, 승인 응답을 받지 못한 결제는 실패로 단정하지 않고 토스
조회 결과로 수렴시킨다. 별도 서비스나 큐를 만들지 않고 payment-service의 기존 스케줄러,
Redisson 락과 stock-release 재시도 경로를 재사용한다.

### 변경 범위

- 승인 전 결제창 취소·명시적 실패를 받는
  `POST /v1/payment-attempts/{paymentRequestId}/fail` API를 추가한다.
- 확정 실패와 만료 시 `FAILED` 조건부 전이 후 재고를 해제한다.
- `pgPaymentKey`가 연결된 승인 결과 불명 건을 조회하는 배치와 설정 가능한 임계시간·배치 크기를
  추가한다.
- 토스 조회의 `DONE`, `ABORTED`·`EXPIRED`, 조회 장애를 각각 완료, 실패, 보류로 매핑한다.
- 동시 실패 콜백·승인·복구 간 경쟁은 상태 전이와 `pgPaymentKey` 존재 조건으로 직렬화한다.

### Acceptance Criteria

- [ ] Given `pgPaymentKey`가 없는 `PENDING` 결제가 있을 때, When 소유 구매자가 결제창 취소를 보고하면, Then 상태는 `FAILED`가 되고 각 예약 SKU는 정확히 한 번 복원된다.
- [ ] Given `pgPaymentKey`가 이미 연결된 `PENDING` 결제가 있을 때, When 실패 콜백이 승인 처리와 경쟁하면, Then 실패 콜백은 결제를 `FAILED`로 바꾸지 않고 토스 조회 복구 대상으로 남긴다.
- [ ] Given 토스 승인 요청이 타임아웃되고 토스 조회 상태가 `DONE`일 때, When 복구 배치가 해당 건을 처리하면, Then 결제는 `COMPLETED`가 되고 완료 아웃박스는 한 건만 존재하며 재고는 해제되지 않는다.
- [ ] Given 토스 승인 요청이 타임아웃되고 토스 조회 상태가 `ABORTED` 또는 `EXPIRED`일 때, When 복구 배치가 해당 건을 처리하면, Then 결제는 `FAILED`가 되고 예약 재고는 정확히 한 번 복원된다.
- [ ] Given 토스 조회도 타임아웃 또는 5xx로 실패할 때, When 복구 배치가 해당 건을 처리하면, Then 결제는 `PENDING`이고 예약은 `RESERVED`로 유지되며 다음 조회 대상에서 제외되지 않는다.
- [ ] Given `pgPaymentKey` 없이 승인 유효시간을 지난 `PENDING` 준비 건이 있을 때, When 만료 배치가 실행되면, Then 결제는 `FAILED`가 되고 예약 재고는 복원된다.
- [ ] Given 재고 해제 HTTP 호출이 실패할 때, When 실패·만료 처리를 완료하면, Then 결제는 `FAILED`이고 기존 stock-release 재시도 저장소에 `paymentRequestId` 기준 항목이 한 건 기록된다.
- [ ] Given 결제 시도가 `FAILED`이고 연결된 주문이 있을 때, When 구매자가 같은 주문으로 결제 준비를 다시 요청하면, Then 주문은 `PENDING`을 유지하고 이전 값과 다른 새 `paymentRequestId`가 발급된다.

---

## Issue 5 — [프런트 체크아웃을 토스 V2로 전환하고 즉시완료 경로를 제거한다](https://github.com/lsc713/docswithmulti/issues/119)

**예상 크기:** 1일  
**의존성:** Issue 4

### 목적

구매자가 기존 체크아웃에서 토스 테스트 결제창을 사용하고 성공·실패 리다이렉트 후 일관된
결과를 보게 한다. 전환과 동시에 PG 호출 없이 결제를 `COMPLETED`로 만들던 레거시 API를
제거한다. gateway, Secret, 서비스 URL과 NetworkPolicy도 실제 호출 그래프에 맞춰 함께 배포해
로컬에서만 작동하는 경로를 남기지 않는다.

### 변경 범위

- 기존 Checkout 요약·버튼에 토스 V2 SDK와 결제 준비 API를 연결한다.
- 성공 URL에서 승인 API를 호출하고 완료 후 기존 `OrderSuccess`를 표시한다.
- 실패 URL에서 실패 API를 호출하고 체크아웃 스타일의 오류·다시 결제를 표시한다.
- 완료된 경우에만 장바구니를 비운다.
- 기존 `POST /v1/payments` 즉시 완료 생성 경로를 제거한다.
- gateway에 인증·CSRF 적용된 `/v1/payment-attempts/**` 라우트를 추가한다.
- 토스 테스트 client/secret 키, payment→order/product와 product→payment URL, 호출자별
  NetworkPolicy를 배포 설정에 추가한다. 내부 엔드포인트는 gateway 라우트에서 제외한다.

### Acceptance Criteria

- [ ] Given 로그인 구매자와 재고가 있는 상품이 있을 때, When 체크아웃에서 결제 버튼을 누르면, Then 토스 V2 테스트 결제창에 서버 계산 금액과 주문명이 표시되고 결제 요청 중 `.pay-btn`의 `disabled` 속성은 true다.
- [ ] Given 구매자가 토스 테스트 결제 인증을 성공했을 때, When 성공 URL로 돌아오면, Then 프런트는 승인 API를 한 번 호출하고 `COMPLETED` 응답 후 기존 완료 화면에 실제 `pgPaymentKey`와 서버 금액을 표시한다.
- [ ] Given 구매자가 결제창에서 취소했을 때, When 실패 URL로 돌아오면, Then 프런트는 해당 시도를 실패 처리하고 오류 코드에 대응하는 안내와 `다시 결제` 버튼을 표시하며 장바구니 항목은 유지한다.
- [ ] Given 승인 결과가 아직 `PENDING`일 때, When 성공 URL 처리가 응답을 받으면, Then 완료 화면이나 `COMPLETED` 문구를 표시하지 않고 처리 중 안내와 상태 재조회 동작을 표시한다.
- [ ] Given 결제가 `COMPLETED`일 때, When 체크아웃 후처리가 실행되면, Then 장바구니 구매는 장바구니가 비워지고 바로구매는 기존 장바구니를 변경하지 않는다.
- [ ] Given 인증된 사용자가 레거시 `POST /v1/payments`를 직접 호출할 때, When 요청이 gateway에 도달하면, Then 응답은 404 또는 405이고 payment·payment_item·완료 아웃박스 행 수는 증가하지 않는다.
- [ ] Given 배포된 gateway와 서비스 NetworkPolicy가 있을 때, When gateway를 통해 결제 준비·승인을 호출하고 payment-service가 order/product 내부 API를 호출하면, Then 각 요청은 허용되고 gateway를 통한 내부 API 경로 요청은 404다.
- [ ] Given 저장소의 배포 설정을 사용할 때, When 프런트 번들과 payment-service 설정을 검사하면, Then 토스 secret 키 문자열은 프런트 산출물·Git 추적 파일·HTTP 응답에 존재하지 않고 payment-service Secret 참조로만 공급된다.

## 이번 이슈 묶음에서 만들지 않는 별도 이슈

- checkout-service 신설
- 복수 PG 추상화와 라우팅
- 범용 Saga 프레임워크
- 공유 이벤트 DTO 라이브러리
- order/product의 취소 복구 코드를 선제적으로 공통 모듈화

이 항목들은 현재 토스 일회성 결제를 완성하는 데 필요하지 않다. 반복 변경이나 두 번째 PG 같은
실제 요구가 생길 때 별도 근거를 갖고 검토한다.
