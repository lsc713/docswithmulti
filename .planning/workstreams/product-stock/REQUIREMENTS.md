# REQUIREMENTS — Milestone v3.0: SKU 재고 수명주기 (product-service)

> Workstream: `product-stock`. 경로 Y 서브프로젝트 1. 권위 입력:
> `docs/superpowers/specs/2026-07-30-sku-stock-lifecycle-design.md`.
> 취소 코어 4개 서비스 + v2.0 인증 경계는 as-built·불변. 이 마일스톤은 신규 product-service
> 재고 수명주기(reserve→release)만 추가.

## v3.0 Requirements

### STOCK — product-service 재고 기반 (신규 모듈)

- [x] **STOCK-01**: product-service가 product·SKU·재고·예약을 저장하는 독립 스키마(Flyway V1)로 기동한다.
- [x] **STOCK-02**: 관리자가 `POST /v1/products`로 product + SKU + 초기 재고를 등록(seed)할 수 있다.
- [x] **STOCK-03**: 재고 예약(reserve)은 `available_qty >= qty`일 때만 성공하고, 부족하면 409로 거부된다(오버셀 방지, 원자 조건부 UPDATE).
- [x] **STOCK-04**: reserve/release는 paymentKey 기준 멱등이다(중복 요청이 재차감/재복원하지 않는다).

### RSV — 결제 생성 시 재고 예약 (payment 통합)

- [x] **RSV-01**: 결제 생성 시 payment가 product로 SKU 재고를 동기 예약하고, 예약 실패(재고 부족·장애)면 결제 생성이 거부된다.
- [x] **RSV-02**: 결제 요청과 payment_item에 sku_id와 quantity가 실린다(Flyway V16).
- [x] **RSV-03**: 예약 성공 후 결제 생성 TX가 실패하면 예약이 보상(release)된다(재시도 포함).

### RST — 취소 시 재고 복원 (이벤트 소비)

- [x] **RST-01**: payment.cancelled 이벤트 payload에 취소된 아이템의 skuId·quantity가 실린다(하위호환 필드 추가, 취소 코어 로직 불변).
- [x] **RST-02**: product-service가 payment.cancelled를 신규 consumer로 구독해 취소된 SKU 재고를 복원한다(부분취소 인지·cancelRequestId 멱등).
- [x] **RST-03**: orphan 예약(예약 성공 후 결제 미생성)이 복구 스케줄러로 정리된다(payment 조회 기반, processing-recovery 패턴).

## Future Requirements (deferred — 경로 Y 후속 서브프로젝트)

- 카탈로그 코어: category(대·중·소 계층) + product 확장 + product_image.
- 속성 시스템: product_attribute_type/value + product_sku_attribute(변형 정규화).
- 상품 조회/검색/브라우징 API.

## Out of Scope (explicit)

- **취소 코어 로직 변경**: payload 필드 추가(RST-01) 외 payment-service 취소 플로우(멱등성·TX1/2/3·스케줄러·outbox) 불변.
- **명시적 confirm 단계(saga 3-phase)**: 예약=차감 모델 채택, YAGNI.
- **풀 카탈로그**: category/attribute/image/version은 후속 서브프로젝트.
- **product ↔ 타 서비스 연동**: 재고는 payment 경로(HTTP reserve/release + Kafka 취소 이벤트)만.

## Traceability

| REQ-ID | Phase | Status |
|--------|-------|--------|
| STOCK-01 | Phase 1 | done |
| STOCK-02 | Phase 1 | done |
| STOCK-03 | Phase 1 | done |
| STOCK-04 | Phase 1 | done |
| RSV-01 | Phase 2 | done |
| RSV-02 | Phase 2 | done |
| RSV-03 | Phase 2 | done |
| RST-01 | Phase 3 | done |
| RST-02 | Phase 3 | done |
| RST-03 | Phase 3 | done |

_(Phase 열은 로드맵이 채운다.)_
