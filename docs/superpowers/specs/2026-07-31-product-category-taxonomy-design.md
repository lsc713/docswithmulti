# 카테고리 택소노미 + 카테고리별 조회 — 설계 (product-service)

- 날짜: 2026-07-31
- 워크스트림: `product-catalog` (경로 Y 후속 서브프로젝트 1 — 풀 카탈로그 백필의 첫 수직 슬라이스)
- 브랜치: `feat/product-catalog`
- 선행: v3.0 SKU 재고 수명주기(`docs/superpowers/specs/2026-07-30-sku-stock-lifecycle-design.md`, main 머지됨)

## 1. 목표 / 배경

v3.0에서 product-service는 **재고에 필요한 최소 카탈로그**(`product`=id/name, `product_sku`, `product_stock`, `stock_reservation`)만 지었다. 카테고리·조회 API가 전무하다(쓰기는 `POST /v1/products` seed 하나, **읽기 API 0개**).

이 슬라이스의 목표: **"카테고리(대·중·소)로 상품을 브라우징한다"**는 사용자 목표를 관통하는 첫 수직 슬라이스. 테이블만 만들지 않고 카테고리 트리 구성 → 카테고리별 상품 조회 → 상품 상세까지 end-to-end로 관찰·테스트 가능하게 한다.

**불변 제약**: 기존 재고 예약·복원 경로(`reserve`/`release`, `payment.cancelled` consumer, `product_stock`/`stock_reservation` 로직)는 **한 줄도 바꾸지 않는다**. 이 슬라이스는 카테고리 테이블 + 읽기 엔드포인트 + `product`에 컬럼 하나 추가일 뿐이다.

## 2. 스코프

**포함**
- 카테고리 택소노미: 대·중·소 3단계 트리 (adjacency list).
- product ↔ category 연결 (product는 소분류 leaf 하나에 소속).
- 조회 API: 카테고리 트리, 카테고리별 상품 목록(하위 취합·페이징), 상품 상세(SKU + 재고).
- 카테고리 생성 API + 기존 상품 등록(seed) 확장.

**범위 밖 (다음 슬라이스 → 각 별도 spec→gsd 마일스톤)**
- 속성/변형 시스템 (`option_summary` 자유문자열 → 색상·사이즈 attribute/variant 정규화)
- 이미지 (product/sku 이미지 메타)
- 자유텍스트 검색(키워드)
- 카테고리 수정/삭제/이동 (이번엔 생성+조회만)
- M:N 상품-카테고리 (지금은 상품당 leaf 하나)

## 3. 데이터 모델 (마이그레이션 V3)

```sql
CREATE TABLE category (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT NULL,                    -- NULL = 대분류(level 1)
    name       VARCHAR(100) NOT NULL,
    level      TINYINT NOT NULL,               -- 1=대 2=중 3=소 (parent로부터 유도·검증)
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_parent_name (parent_id, name),  -- 형제간 이름 유일
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

`product`에 category 연결 추가 (백필 후 NOT NULL):

```sql
-- 1) 컬럼 추가 (nullable)
ALTER TABLE product ADD COLUMN category_id BIGINT NULL;

-- 2) 기존 행 백필: '미분류' 대>중>소 3노드 생성 후 기존 product를 소 leaf에 연결
--    (실데이터 없으면 사실상 no-op이나 안전하게 항상 수행)

-- 3) 제약 확정
ALTER TABLE product
    MODIFY category_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
    ADD KEY idx_product_category (category_id);
```

- `product.category_id` → **소분류(level 3) leaf**만 참조.
- adjacency list: 깊이 변경 시 스키마 불변, 트리 재귀/브라우징 자연스러움.

## 4. 도메인 규칙

- **level 유도**: parent 없으면 level=1(대), 있으면 `parent.level + 1`. **level > 3 생성 거부**(400) — 4단계 불가.
- **leaf 강제**: product 등록/조회 시 `category_id`가 가리키는 카테고리는 반드시 `level = 3`. 아니면 400.
- **형제 이름 유일**: 같은 parent 아래 같은 이름 카테고리 불가 (UK `uk_category_parent_name`; 대분류끼리는 parent_id=NULL 그룹에서 유일).

## 5. API 계약

| 메서드 | 엔드포인트 | 요청 | 응답 |
|---|---|---|---|
| `POST` | `/v1/categories` | `{ parentId?, name }` | `{ id, level }` — parentId 없으면 대분류 |
| `GET` | `/v1/categories` | — | 대→중→소 중첩 트리 |
| `POST` | `/v1/products` | 기존 + `categoryId`(leaf 필수) | 기존 SeedResponse |
| `GET` | `/v1/categories/{id}/products?page=&size=` | — | 하위 leaf 전부의 상품 목록, 최신순(created_at desc), 페이징 |
| `GET` | `/v1/products/{id}` | — | `{ id, name, category{대/중/소 경로}, skus:[{skuCode, optionSummary, availableQty}] }` |

- 페이징 기본값: `page=0, size=20`. 정렬 고정(최신순) — 정렬 옵션은 범위 밖.
- 등록 시 `categoryId`가 leaf가 아니거나 존재하지 않으면 400.

## 6. 조회 구현

- **하위 취합**: 깊이 3 고정 → **MySQL 8 `WITH RECURSIVE`** 로 주어진 카테고리의 모든 하위 leaf id를 모은 뒤 `product.category_id IN (...)` 필터 + 페이징. 비정규화(경로 컬럼) 없이 단순. 대/중/소 어느 노드로 조회해도 하위 상품이 취합됨.
- **재고**: 상세/목록의 `availableQty`는 `product_stock`을 **read-only join**. reserve/release/취소복원 쓰기 경로는 미변경.
- QueryDSL은 단순 목록/상세에 사용, 재귀 취합은 native query(CTE).

## 7. 아키텍처 / 레이어 (기존 헥사고날 답습)

- `domain/entity/Category` (POJO, level 유도·leaf 판정 규칙 보유)
- `application/service/CategoryService` (생성·트리 조회), `application/service/ProductQueryService` (카테고리별 목록·상세)
- `application/interfaces/CategoryRepository`, `ProductQueryRepository` (포트)
- `infrastructure/persistence` (CategoryJpaEntity/Repository/Impl, 재귀 취합 native query)
- `presentation/controller/CategoryController`, `ProductQueryController` (또는 기존 ProductController 확장)
- `CatalogService.seed`에 `categoryId` 인자 추가 (leaf 검증 포함)

## 8. 불변식 / 가드

- **재고 수명주기 불변 게이트**: `StockService`·`ProcessCancelledStockService`·`PaymentCancelledStockConsumer`·`OrphanReservationRecoveryService`·`stock_reservation`/`product_stock` 관련 코드 변경 0. `git diff`(merge-base) 로 증명.
- **테스트** (Testcontainers MySQL):
  - 카테고리 생성: level 유도, level>3 거부, 형제 이름 중복 거부.
  - leaf 강제: 중분류에 상품 등록 시 400.
  - 카테고리별 조회: 대분류로 조회 시 하위 중·소 상품 취합, 페이징 경계.
  - 상세: SKU + availableQty(재고 join) 정확.
  - 무회귀: 기존 재고 예약/복원·취소복원 통합테스트 전부 통과.

## 9. 열린 질문 (계획 단계에서 확정)

- 재귀 취합 native query를 QueryDSL 프로젝션과 어떻게 조합할지(순수 native vs `JPASQLQuery`).
- `GET /v1/categories` 트리 응답을 전체 반환할지 `parentId` 파라미터로 부분 확장할지(기본: 전체 중첩, 데이터 소규모 가정).
- 백필 마이그레이션에서 '미분류' 노드를 항상 만들지, 기존 product 존재 시에만 만들지.
