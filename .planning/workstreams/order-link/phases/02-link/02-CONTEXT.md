# Phase 2 Context — 결제–주문 검증 링크

**Source:** `docs/superpowers/specs/2026-07-31-order-link-design.md` (권위 설계)
**Requirements:** PLINK-01, PLINK-02, PLINK-03, TRUST-01, CANCEL-01
**Depends on:** Phase 1 (order-service `POST /v1/orders/items:verify` 엔드포인트 — 이미 구현·검증됨, 이 브랜치에 존재).
**Goal:** 결제 생성이 상류 주문을 검증(fail-closed)하고 `payment.order_id`로 강하게 링크하며, 결제 신원을 X-User-Id 신뢰헤더에서 취득한다. 취소 코어는 불변.

## Phase 1이 제공하는 계약 (소비 대상)
`POST /v1/orders/items:verify` (order-service :8081, 내부) — Header `X-User-Id`, Body `{ orderItemIds: [long...] }` → `200 { orderId }` (존재+단일 order+소유) / `404 ORDER_ITEM_NOT_FOUND` / `409 ORDER_ITEMS_MULTIPLE_ORDERS` / `403 ORDER_OWNERSHIP_MISMATCH`. 정확 계약은 `phases/01-api/01-SUMMARY.md`/구현 참조.

## Locked Decisions (설계 확정 — 변경 금지)

1. **OrderVerifyPort + OrderVerifyHttpClient (신규)**: `ProductStockPort`/`ProductStockHttpClient` 패턴 미러 — 포트는 `application/interfaces/`, 클라이언트는 `infrastructure/http/`. order-service `items:verify` 호출, 받은 **X-User-Id를 헤더로 포워딩**, 검증된 `orderId` 반환.
2. **fail-closed (PLINK-01)**: order-service 장애/타임아웃/비200 → 결제 **거부**(product reserve와 동일). 검증 4xx는 결제 생성 실패로 매핑(사유 전달).
3. **CreatePaymentService 흐름 최전방 검증 (PLINK-03)**: `order 검증 → paymentKey → 재고 예약(product) → persist(order_id 포함)`. 검증 실패 시 **재고 예약·persist 미발생**(부작용 전 차단, 보상 불필요).
4. **payment.order_id (PLINK-02)**: `BIGINT NOT NULL` + `INDEX idx_payment_order_id`. **Flyway `V18`** 신규 파일(적용된 V1~V17 수정 금지 — 새 버전만). ⚠️ 기존 payment 행 존재 시 NOT NULL 직행 실패 → plan 단계에서 데이터 유무 확인 후 (a) 직행 또는 (b) nullable→backfill→NOT NULL 2단계 확정.
5. **PaymentController 신뢰헤더 (TRUST-01)**: `user_id ← @RequestHeader("X-User-Id")`, `CreatePaymentRequest.userId` 제거. 이 X-User-Id를 order 검증 호출에 포워딩. **merchant_id는 body 유지**(스코프 밖).
6. **CANCEL-01 게이트 (수정: "0 diff" 아님)**: Phase 2는 **결제 생성 경로**(`CreatePaymentService`, `PaymentCreateTxWriter`, `PaymentController`, `CreatePaymentRequest`/`CreatePaymentCommand`, 스키마 V18)를 수정한다. 하지만 **취소 코어는 불변**이어야 한다 — 아래 파일 변경 0:
   - `CancelPaymentService`, `CancelTxWriter`, `CancelAuthorizationService`, `CancelHistoryRecorder`, `CancelPaymentCommand`, `CompensationRetryService`, `PendingRecoveryService`, `ProcessingRecoveryService`, outbox 발행(`CancelEventOutbox*`), 멱등(`cancel_request`/dedup) 관련.
   - 게이트: `git diff --name-only $(git merge-base HEAD main)...HEAD -- payment-service/` 결과에 **위 취소 파일이 없어야** 하고, 기존 취소 통합테스트 전부 통과. (Cancel* 파일이 diff에 나타나면 STOP.)

## Canonical References (미러링 대상)

- **fail-closed HTTP 포트/클라이언트**: `payment-service/.../application/interfaces/ProductStockPort.java`, `infrastructure/http/ProductStockHttpClient.java` (타임아웃·비200→예외 패턴).
- **결제 생성 현 흐름**: `application/service/CreatePaymentService.java`(paymentKey→reserve→persist), `PaymentCreateTxWriter.java`(persist TX), `presentation/controller/PaymentController.java`, `presentation/dto/CreatePaymentRequest.java`, `application/service/CreatePaymentCommand.java`.
- **스키마**: `payment-service/src/main/resources/db/migration/V1__create_payment_core.sql`(payment 테이블), 최신 V17 → 신규 **V18**.
- **레이어/예외 규약**: `docs/conventions/architecture.md`, error-catalog.md(검증 실패 매핑 코드 추가 시).

## Claude's Discretion (구현 재량)

- V18 데이터 처리 방식(직행 vs 2단계) — 실제 데이터 유무 확인 후.
- OrderVerify HTTP 클라이언트의 timeout/에러 매핑 세부(ProductStockHttpClient와 일관).
- verify 응답 DTO 네이밍, order_id 저장 위치(Payment 엔티티/persist writer).

## Scope Fence

- **In**: OrderVerifyPort/HttpClient(신규), CreatePaymentService 검증 삽입, payment.order_id(V18), PaymentController X-User-Id 전환.
- **Out**: order-service 변경(Phase 1 완료), merchant_id 신뢰헤더화, 주문→결제 오케스트레이션, **취소 코어 일체**(CANCEL-01).
