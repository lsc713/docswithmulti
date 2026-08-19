# 토스 테스트 결제 승인 PRD

> 상태: 아키텍처와 Out of Scope 확정
>
> 확정 요구사항: [spec-fixed.md](./spec-fixed.md)

## 개요

현재 체크아웃은 order-service에서 주문을 만든 뒤 payment-service가 내부 결제 키를 생성하고
결제를 즉시 `COMPLETED`로 저장한다. 이번 기능은 일반 구매자가 토스페이먼츠 V2 테스트
결제창에서 인증하고, payment-service의 서버 승인이 성공한 경우에만 결제와 기존 후속 흐름을
완료하도록 바꾼다.

판매자·관리자 화면, 승인 후 취소 정책과 정산 정책은 유지한다.

## 사용자 스토리

- 구매자로서 기존 체크아웃에서 토스 테스트 결제창을 열고 결제수단을 인증하고 싶다.
- 구매자로서 서버 승인이 성공한 경우에만 결제 완료 화면과 결제 내역을 보고 싶다.
- 구매자로서 결제창을 취소하거나 승인이 거절되면 재고가 해제되고 다시 결제하고 싶다.
- 운영자로서 승인 응답이 불명확한 결제가 조회를 통해 최종 상태로 수렴하고 중복 승인되지
  않기를 원한다.

## 기술 결정

### payment-service가 결제 시도와 토스 승인을 소유한다

**Context** — 현재 payment-service는 주문 소유권 검증, product-service 재고 예약,
`payment`·`payment_item` 저장과 `payment.completed` 발행을 이미 조정한다. 하지만 자체 생성한
`paymentKey`로 결제를 즉시 `COMPLETED` 처리하고, 브라우저가 보낸 `itemAmount`를 합산하므로
실제 PG 승인과 서버 가격 검증이 없다. 기존 `payment.payment_key`는 NOT NULL 64자이지만 토스의
`paymentKey`는 인증 이후에 생기며 최대 200자다. 주문은 생성 즉시 `DELIVERY_WAITING`이 되어
승인 실패 주문도 배송 가능한 상태가 되는 문제도 있다.

**Decision** — payment-service를 결제 상태의 단일 소유자로 유지하고 다음 구조를 적용한다.

1. **결제 준비**
   - `POST /v1/payment-attempts`가 기존 주문 소유권과 주문 항목을 검증한다.
   - payment-service가 UUID v4 형식의 고유한 `paymentRequestId`를 발급한다. 이 값은 토스의
     `orderId`와 승인 POST의 `Idempotency-Key`로 그대로 재사용한다.
   - product-service가 `paymentRequestId`로 재고를 원자 예약하고 각 SKU의 서버 저장 단가를
     함께 반환한다. payment-service는 요청의 `itemAmount`를 승인 금액에 사용하지 않고
     `서버 단가 × 수량`으로 정수 KRW 합계를 계산한다.
   - 이 합계와 항목, 주문 링크를 `PENDING` 결제로 저장한다. 주문별 활성 결제 유일성 경쟁이나
     저장 실패 시 방금 예약한 재고를 기존 멱등 release·재시도 경로로 보상한다.
   - 응답은 `paymentRequestId`, 서버 계산 금액, 주문명, 결제 시도에서 파생한 비식별
     `customerKey`와 공개 가능한 토스 테스트 클라이언트 키를 반환한다. 사용자 ID 원문과 비밀
     키는 반환하지 않는다.

2. **데이터 모델과 동시성**
   - `payment`에 고유 `payment_request_id`를 추가한다.
   - 기존 `payment_key`는 토스 `pgPaymentKey`를 저장하도록 최대 200자로 늘리고 승인 전에는
     NULL을 허용한다. 기존 완료 행은 현재 값을 유지하고 `payment_request_id`를 backfill한다.
   - `PaymentStatus`에 `FAILED`를 추가한다. 승인 전·결과 불명은 `PENDING`, 승인 성공은
     `COMPLETED`, 확정 실패는 `FAILED`다.
   - MySQL의 조건부 생성 열과 유일 인덱스로 주문별 활성 `PENDING` 한 건을 보장한다. 애플리케이션
     선조회만으로 동시 요청을 막지 않는다.
   - `pgPaymentKey` 연결과 상태 전이는 조건부 UPDATE 또는 행 잠금으로 직렬화한다. 사용자 실패
     콜백은 `pgPaymentKey`가 아직 연결되지 않은 `PENDING`만 실패시킬 수 있다.

3. **결제창과 승인**
   - 프런트엔드는 토스 V2 SDK를 로드하고 준비 응답의 서버 금액으로 결제창을 연다.
   - 성공·실패 URL에는 `paymentRequestId`를 포함한다. SPA 진입 시 URL 파라미터를 읽어 기존
     체크아웃 또는 완료 화면으로 연결하며 새 전역 상태관리 도구는 도입하지 않는다.
   - `POST /v1/payment-attempts/{paymentRequestId}/confirm`은 로그인 사용자, 저장된 주문,
     `NORMAL`, 저장 금액, 리다이렉트의 `orderId`와 `amount`를 검증한다.
   - 검증 후 `pgPaymentKey`를 먼저 결제 시도에 연결하고, 토스
     `POST /v1/payments/confirm`을 DB 트랜잭션 밖에서 호출한다. POST 멱등 키는 UUID v4인
     `paymentRequestId`를 사용한다.
   - 성공 시 한 트랜잭션에서 `COMPLETED` 전이와 기존 `payment.completed` 아웃박스를 기록한다.
     같은 승인 요청은 이미 저장된 결과를 반환하며 승인과 완료 이벤트를 반복하지 않는다.

4. **실패, 재고 보상과 결과 불명 복구**
   - 결제창 취소와 승인 전 확정 실패는 조건부로 `FAILED` 처리하고 기존 멱등 release 경로로
     예약을 해제한다. release 장애는 기존 stock-release 재시도 저장소를 재사용한다.
   - 승인 호출 타임아웃, 연결 단절 또는 승인 결과를 단정할 수 없는 오류는 `PENDING`과 예약을
     유지한다.
   - payment-service 안의 기존 스케줄러·Redisson 락 패턴을 재사용해 오래된 결과 불명 건만
     토스 `GET /v1/payments/{paymentKey}`로 조회한다. 승인 확인 시 완료 트랜잭션을 재사용하고,
     만료·실패 확인 시 실패 및 재고 해제로 수렴시킨다.
   - `pgPaymentKey`가 없는 채 승인 유효시간을 지난 준비 건은 `FAILED`로 만료시키고 재고를
     해제한다. 별도 복구 서비스나 새 큐는 만들지 않는다.

5. **주문과 기존 후속 흐름**
   - 주문 생성 상태를 `PENDING`으로 바꾸어 결제 전에는 배송 대상이 되지 않게 한다.
   - 기존 `payment.completed` 이벤트에 `orderId`를 하위 호환 필드로 추가하고 order-service가
     멱등 소비해 주문을 `DELIVERY_WAITING`으로 전이한다.
   - settlement-service가 사용하는 기존 필드와 의미는 유지한다. 취소는 완료 결제의 실제
     `pgPaymentKey`를 계속 사용하므로 승인 후 취소·정산 정책은 바꾸지 않는다.
   - 실패한 주문은 `PENDING`에 남아 새 `paymentRequestId`로 재결제할 수 있다.

6. **외부 경계와 배포**
   - 애플리케이션 계층에는 토스 승인·결제 조회 계약을 표현하는 포트를 두고, 인프라 계층의
     Toss HTTP 어댑터가 Basic 인증, 응답 매핑과 설정 가능한 타임아웃을 담당한다.
   - 토스 시크릿 키는 payment-service Secret으로, 클라이언트 키는 공개 설정으로 분리한다.
   - gateway는 인증·CSRF가 적용된 payment-attempt API만 외부에 노출한다.
   - 기존 실제 호출 그래프에 맞춰 payment→order/product 및 product→payment 내부 통신의 URL과
     NetworkPolicy를 함께 배포한다. 내부 API는 gateway 공개 경로와 분리하고 호출 서비스만
     접근하도록 제한한다.

**Alternatives** — 다음 두 안은 선택하지 않았다.

- **order-service가 승인 오케스트레이션 소유**: 주문 상태를 한곳에서 다룰 수 있으나 토스 키,
  결제 멱등성과 실패 복구가 order-service로 이동한다. 취소·정산은 여전히 payment-service를
  사용하므로 결제 상태의 소유자가 둘이 되고 서비스 간 동기화 실패가 늘어난다.
- **checkout-service 신설**: 장기적으로 여러 PG·쿠폰·프로모션을 한 Saga로 조정하기에는 좋지만,
  현재 범위에는 새 서비스, DB, 배포와 장애 지점만 추가한다. 기존 payment-service의 주문 검증,
  재고 예약과 아웃박스 흐름도 다시 감싸야 해 중복 오케스트레이션이 된다.

**Consequences** — 결제 승인, 조회, 취소와 정산의 기준 키가 payment-service에 유지되고 기존
재고 보상·아웃박스 패턴을 재사용할 수 있다. 브라우저 금액을 신뢰하지 않으며 주문은 실제 승인
후에만 배송 상태가 된다. 반면 `payment_key`의 nullable 전환과 길이 확대, 레거시 backfill,
product 예약 응답과 `payment.completed`의 하위 호환 확장이 필요하다. 승인 HTTP 호출과 DB 완료
사이의 원자성은 만들 수 없으므로 결과 불명 조회 복구가 필수이며, `PENDING` 동안 재고가 더 오래
묶일 수 있다. 첫 버전은 이 비용을 감수하고 복수 PG나 별도 Saga 서비스는 도입하지 않는다.

## Out of Scope

다음 범위를 구현하지 않는다.

- 가상계좌 입금 웹훅과 비동기 승인 대기 결제수단
- 자동결제와 빌링키
- 복수 PG 라우팅과 장애 시 다른 PG로 전환
- 쿠폰, 포인트와 분할 결제
- 판매자·관리자 화면 변경
- 기존 승인 후 취소 정책과 정산 계산 변경
- `payment.completed`의 기존 필드 삭제·이름 변경·의미 변경. order-service 전이를 위한
  선택적 `orderId` 추가만 허용한다.
- 별도 캐시, 메시지 큐 또는 독립 복구 서비스 도입

## 용어 정의

| 용어 | 정의 |
|---|---|
| 주문 | 구매 항목과 구매자를 보관하는 order-service의 레코드 |
| 결제 시도 | 한 주문에 대해 토스 인증과 승인을 한 번 시도하는 단위 |
| 결제 인증 | 구매자가 토스 결제창에서 결제수단 인증을 마치고 `paymentKey`를 받는 단계 |
| 결제 승인 | payment-service가 토스 서버 API를 호출해 실제 결제를 확정하는 단계 |
| 결과 불명 | 승인 요청의 응답을 받지 못해 토스에서 승인됐는지 단정할 수 없는 상태 |
| 재고 예약 | 결제가 확정되거나 실패할 때까지 해당 수량을 다른 구매가 사용할 수 없게 잡아두는 것 |
| `paymentRequestId` | 우리 시스템의 결제 시도 식별자이자 토스 요청의 `orderId` |
| `pgPaymentKey` | 토스가 인증 성공 후 발급하는 외부 결제 식별자 |
| `paymentKey` | 기존 외부 API 호환 명칭. 토스 연동 후에는 `pgPaymentKey`를 의미 |
