# 상품 상세 재고 표시·캐시 이슈 분해

## 1. 상품 상세 재고 상태를 분리 캐시로 제공

**목표:** 상품 상세 본문 캐시와 독립적으로 SKU별 가용 재고·품절 상태를 반환한다.

**의존성:** 없음

## Acceptance Criteria

- [ ] Given 재고 상태 Redis 키가 없는 상품, When 상품 상세를 조회하면, Then MySQL `product_stock`의 SKU별 수량을 응답하고 Redis 재고 상태를 채운다.
- [ ] Given 마지막 1개를 `reserve`한 SKU, When 트랜잭션이 성공하면, Then 다음 상품 상세 조회의 해당 SKU는 `availableQty=0`이고 품절 상태다.
- [ ] Given 예약된 SKU를 `release`하면, When 트랜잭션이 성공하면, Then 다음 상품 상세 조회의 해당 SKU는 복원된 수량과 구매 가능 상태다.
- [ ] Given Redis 재고 상태 조회가 실패하면, When 상품 상세를 조회하면, Then MySQL fallback 결과를 반환하고 `reserve` 정합성은 유지한다.
- [ ] Given `reserve`가 재고 부족으로 실패하면, When 상세를 다시 조회하면, Then 실패한 예약이 재고 상태를 음수로 만들지 않는다.
- [ ] Given cache hit·miss와 fallback, When 각 경로가 실행되면, Then 경로별 Micrometer 카운터가 기록된다.

## 2. 재고 부족 뒤 상품 상세 UI를 최신 상태로 갱신

**목표:** stale 상품 상세에서 구매를 시도해 재고 부족이 발생해도 구매자에게 최신 품절·수량 상태를 보여준다.

**의존성:** 이슈 1

## Acceptance Criteria

- [ ] Given 선택한 SKU가 stale 화면에서는 구매 가능, When 주문 과정에서 재고 부족 응답을 받으면, Then 사용자에게 “방금 품절됨” 안내가 보인다.
- [ ] Given 재고 부족 응답 뒤 상품 상세 재조회가 성공하면, When 최신 SKU 수량이 0이면, Then 해당 옵션은 비활성화되고 품절로 표시된다.
- [ ] Given 재조회 뒤 선택 SKU의 가용 수량이 현재 수량보다 작으면, When 화면 상태를 갱신하면, Then 선택 수량은 가용 수량 이하로 clamp된다.

## 3. 읽기·예약/해제 혼합 ramp 부하 시나리오

**목표:** 분리 재고 캐시의 정합성과 시스템 포화 주체를 혼합 요청에서 측정한다.

**의존성:** 이슈 1

## Acceptance Criteria

- [ ] Given 시드된 상품과 SKU ID, When k6 혼합 시나리오를 시작하면, Then 상세 조회 90%와 `reserve`·`release` 10%가 고유 `paymentKey`로 동시에 실행된다.
- [ ] Given ramp 단계, When 500·750·1,000·1,250 VU를 각 3분 실행하면, Then 단계별 읽기·쓰기 RPS, p95, p99, 오류율이 결과 파일에 남는다.
- [ ] Given 예약이 성공한 SKU, When 같은 시나리오에서 release를 실행하면, Then 재고가 누적 소진되지 않고 다음 반복에서 다시 예약 가능하다.
- [ ] Given 혼합 ramp가 종료되면, When Prometheus 결과를 조회하면, Then k6·Product 각 인스턴스·MySQL·Redis의 CPU/메모리, MySQL `threads_running`, Redis cache hit/miss가 같은 시간축으로 남는다.
- [ ] Given 어느 단계에서 오류율이 1% 이상이거나 p99와 처리량이 악화되면, When 결과 보고서를 작성하면, Then 최초 포화 인스턴스와 근거 지표를 단계별로 명시한다.

## 의존성 순서

`1 → 2`와 `1 → 3`이다. 이슈 2와 3은 서로 독립적으로 진행할 수 있다.

## AC 정밀도 확인

- 모든 AC는 Given-When-Then으로 실제 응답값·화면 상태·측정 지표를 검증한다.
- negative 조건에는 수량·옵션 상태·카운터 등 positive 결과를 함께 둔다.
- 캐시 실패, 재고 부족, 동시 예약, ramp 포화 판정을 각각 분리했다.
