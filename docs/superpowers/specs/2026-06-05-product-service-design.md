# product-service 설계 스펙

## 개요

패션 이커머스 결제 취소 시스템에 상품 카탈로그 + 재고 관리 모듈을 추가한다.
상품 버저닝을 지원하고, 결제 취소 시 Kafka 이벤트로 재고를 복원한다.
재고 차감 API는 내부용으로 준비하되, order-service 연동은 별도 작업으로 분리한다.

---

## 1. 모듈 책임 & 포트

| 항목 | 내용 |
|------|------|
| 모듈명 | `product-service` |
| 포트 | `8084` |
| 책임 | 상품 카탈로그 (CRUD + 버저닝), SKU 관리 (color/size), 재고 관리 (차감/복원), 카테고리 관리 |
| 인가 | JWT 검증. MERCHANT는 자기 가맹점 상품만 CUD. 조회는 누구나. ADMIN은 전체 |
| 이벤트 소비 | `payment.cancelled` 구독 → 재고 복원 |
| 내부 API | 재고 차감, 상품 정보 조회 (서비스 간 통신) |

---

## 2. 데이터 모델

### 엔티티 관계

```
category (계층형, parent_id 자기참조)
  └── product (가맹점 소속, 카테고리 참조)
       └── product_version (이름/가격/할인/속성 JSON — payment-service의 productAutoId)
            └── product_sku (color + size 조합)
                 └── product_stock (SKU별 재고 수량)

processed_stock_event (cancelRequestId UK — 재고 복원 멱등성)
```

### Category

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| name | String | 카테고리명 |
| parentId | Long | 상위 카테고리, nullable (루트일 때 null) |
| depth | int | 0=대, 1=중, 2=소 |
| createdAt | Instant | |

### Product

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | 다른 서비스의 `productId` |
| merchantId | Long | 소속 가맹점 |
| categoryId | Long | 카테고리 참조 |
| status | Enum | ACTIVE, INACTIVE |
| createdAt | Instant | |
| updatedAt | Instant | |

### ProductVersion

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | 다른 서비스의 `productAutoId` |
| productId | Long (FK) | |
| name | String | 상품명 |
| price | BigDecimal | 정가 |
| discountPrice | BigDecimal | 할인가, nullable |
| attributes | String (JSON) | `{"제조국":"한국","소재":"면100%"}` |
| version | int | 버전 번호 (1, 2, 3...) |
| isCurrent | boolean | 현재 활성 버전 여부 |
| createdAt | Instant | |

### ProductSku

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| productVersionId | Long (FK) | |
| color | String | "빨강", "검정" 등 |
| size | String | "S", "M", "L", "XL" 등 |
| skuCode | String | UNIQUE, 자동 생성 |
| createdAt | Instant | |

### ProductStock

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| skuId | Long (FK) | UNIQUE |
| quantity | int | 현재 재고 수량 |
| updatedAt | Instant | |

### ProcessedStockEvent

| 필드 | 타입 | 설명 |
|------|------|------|
| id | Long (PK) | |
| cancelRequestId | Long | UNIQUE — 멱등성 보장 |
| createdAt | Instant | |

---

## 3. API 엔드포인트

### 카테고리 (ADMIN 전용)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/v1/categories` | 카테고리 생성 |
| GET | `/v1/categories` | 전체 카테고리 트리 조회 |
| PATCH | `/v1/categories/{id}` | 카테고리명 수정 |
| DELETE | `/v1/categories/{id}` | 카테고리 삭제 (하위 없을 때만) |

### 상품 (MERCHANT: 자기 가맹점만 CUD, 조회는 누구나)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/v1/products` | 상품 생성 (첫 버전 + SKU + 재고 함께) |
| GET | `/v1/products/{id}` | 상품 상세 조회 (현재 버전 + SKU + 재고) |
| GET | `/v1/products` | 상품 목록 조회 (카테고리/가맹점 필터) |
| PATCH | `/v1/products/{id}/status` | 상품 상태 변경 (ACTIVE/INACTIVE) |

### 상품 버전 (MERCHANT)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/v1/products/{id}/versions` | 새 버전 생성 (이름/가격/속성 변경 시) |
| GET | `/v1/products/{id}/versions` | 버전 이력 조회 |

### SKU + 재고 (MERCHANT)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/v1/products/{id}/skus` | SKU 추가 (color + size + 초기 재고) |
| PATCH | `/v1/skus/{skuId}/stock` | 재고 수량 직접 수정 |

### 내부 API (인증 불필요 — 서비스 간 통신)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/internal/stocks/deduct` | 재고 차감 (order-service 연동용) |
| GET | `/internal/products/{id}` | 상품 정보 조회 (payment-service 스냅샷용) |

### Kafka Consumer

| 토픽 | 동작 |
|------|------|
| `payment.cancelled` | cancelRequestId 멱등성 체크 → SKU별 재고 복원 |

---

## 4. 재고 차감/복원 설계

### 재고 차감 (`POST /internal/stocks/deduct`)

```
요청: { skuId, quantity }
  → ProductStock SELECT ... FOR UPDATE (비관적 락)
  → quantity 부족 시 PROD_004 (422)
  → stock.deduct(quantity)
  → 저장
응답: { skuId, remainingQuantity }
```

### 재고 복원 (Kafka Consumer: `payment.cancelled`)

```
페이로드 (향후 확장 예정):
{
  "cancelRequestId": 1,
  "cancelledItems": [
    { "skuId": 5, "quantity": 2 }
  ],
  ...
}

Consumer 처리:
  1. processed_stock_event에서 cancelRequestId 중복 체크
  2. 중복이면 skip (멱등)
  3. 각 cancelledItem:
     - ProductStock 조회
     - stock.restore(quantity)
     - 저장
  4. processed_stock_event INSERT
  5. ACK
```

---

## 5. DB 스키마

Flyway 파일: `V1__create_product_core.sql`

```sql
CREATE TABLE category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    parent_id  BIGINT       NULL,
    depth      INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_category_parent_id (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id)
);

CREATE TABLE product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_product_merchant_id (merchant_id),
    INDEX idx_product_category_id (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id)
);

CREATE TABLE product_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id     BIGINT         NOT NULL,
    name           VARCHAR(100)   NOT NULL,
    price          DECIMAL(19,2)  NOT NULL,
    discount_price DECIMAL(19,2)  NULL,
    attributes     JSON           NULL,
    version        INT            NOT NULL DEFAULT 1,
    is_current     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_product_version_product_id (product_id),
    CONSTRAINT fk_product_version_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE TABLE product_sku (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_version_id BIGINT       NOT NULL,
    color              VARCHAR(30)  NOT NULL,
    size               VARCHAR(10)  NOT NULL,
    sku_code           VARCHAR(50)  NOT NULL UNIQUE,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_sku_product_version_id (product_version_id),
    CONSTRAINT fk_sku_product_version FOREIGN KEY (product_version_id) REFERENCES product_version(id)
);

CREATE TABLE product_stock (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id     BIGINT  NOT NULL UNIQUE,
    quantity   INT     NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stock_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id)
);

CREATE TABLE processed_stock_event (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    cancel_request_id BIGINT NOT NULL UNIQUE,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
```

---

## 6. 에러 코드

| 코드 | HTTP | 설명 |
|------|------|------|
| PROD_001 | 404 | 상품을 찾을 수 없음 |
| PROD_002 | 404 | SKU를 찾을 수 없음 |
| PROD_003 | 404 | 카테고리를 찾을 수 없음 |
| PROD_004 | 422 | 재고 부족 (차감 시) |
| PROD_005 | 409 | 중복 SKU 코드 |
| PROD_006 | 422 | 하위 카테고리 존재 시 삭제 불가 |
| PROD_007 | 403 | 해당 상품에 대한 권한 없음 |

---

## 7. 인가 규칙

JWT claims에서 role, merchantId를 추출하여 검증.

| 역할 | 상품 CUD | 상품 조회 | 카테고리 관리 |
|------|---------|----------|-------------|
| USER | 불가 | 가능 | 불가 |
| MERCHANT | 자기 가맹점만 | 가능 | 불가 |
| ADMIN | 전체 | 가능 | 전체 |

내부 API (`/internal/**`)는 JWT 검증 없이 허용.

---

## 8. 테스트 전략

| 레이어 | 대상 | 방식 |
|--------|------|------|
| domain | Product 상태 전환, ProductStock 차감/복원 | 단위 테스트 |
| domain | ProductVersion 버전 관리, Category 계층 | 단위 테스트 |
| application | 상품 생성/버전 추가/SKU 추가 UseCase | 단위 테스트 (Mockito) |
| application | 재고 차감/복원 UseCase | 단위 테스트 (Mockito) |
| infrastructure | JPA 저장소, Kafka Consumer | 통합 테스트 (Testcontainers) |
| presentation | Controller 요청/응답, JWT 인가 | MockMvc |

---

## 9. 패키지 구조

```
product-service
└── src/main/java/com/example/product
    ├── common/exception/
    │   ├── BusinessException.java
    │   ├── ErrorCode.java
    │   ├── domain/
    │   └── application/
    ├── domain/
    │   ├── entity/       Category, Product, ProductStatus, ProductVersion,
    │   │                 ProductSku, ProductStock, ProcessedStockEvent
    │   └── exception/    (없음 — common/exception/domain/ 사용)
    ├── application/
    │   ├── usecase/      CategoryUseCase, ProductUseCase, ProductVersionUseCase,
    │   │                 SkuUseCase, StockDeductUseCase, StockRestoreUseCase
    │   ├── service/      각 UseCase 구현체
    │   └── interfaces/   Repository 인터페이스
    ├── infrastructure/
    │   ├── persistence/  JPA 엔티티 + Repository 구현
    │   ├── messaging/    PaymentCancelledConsumer
    │   ├── security/     JwtAuthenticationFilter, SecurityConfig
    │   └── config/       PersistenceConfig, KafkaConsumerConfig
    └── presentation/
        ├── controller/   CategoryController, ProductController, SkuController,
        │                 InternalProductController, InternalStockController
        ├── dto/          요청/응답 DTO
        └── GlobalExceptionHandler.java
```
