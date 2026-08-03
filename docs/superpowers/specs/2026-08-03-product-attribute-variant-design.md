# 속성/변형 정규화 — 설계 (product-service)

- 날짜: 2026-08-03
- 브랜치: `feat/product-attribute-variant` (단독)
- 선행: product-catalog(카테고리) + 카탈로그 프론트(SKU 가격·이미지) 모두 main 반영. product 최신 Flyway V7.
- 풀 카탈로그 백필 경로 Y 후속 슬라이스 1 (다음 = 자유텍스트/facet 검색).

## 1. 목표 / 배경

현재 `product_sku.option_summary`는 **자유문자열 VARCHAR(255)**("화이트/M")라 구조가 없다 — 변형 선택 UI·facet 검색·일관된 옵션 표현이 불가능하다.

목표: `option_summary`를 **구조화된 속성/변형 모델로 정규화**한다. 전역 속성 사전(색상·사이즈·소재·안감거칠기…) 위에서, 상품이 쓰는 속성을 **변형-정의**(SKU를 만드는 색상·사이즈)와 **서술**(상품을 설명하는 소재·안감거칠기)로 구분해 선언하고, SKU를 변형 속성값의 완전·유일 조합으로 정의한다.

**불변 제약**: 재고 예약·복원 경로(reserve/release, `payment.cancelled` consumer, 재고 로직)는 변경하지 않는다 — 순수 카탈로그 추가.

## 2. 스코프

**포함**
- 전역 속성 사전(`attribute`/`attribute_value`).
- 상품의 속성 선언(변형/서술 역할, 다속성).
- SKU 변형 조합(완전·유일) + 상품 서술 값(다속성·다값).
- 등록 API 확장 + 상세 조회 구조화 노출.

**범위 밖 (다음 슬라이스 → 각 별도)**
- facet 검색(속성 위) · 자유텍스트 검색
- 자동 변형 매트릭스 생성(색상×사이즈 카테시안)
- 변형 선택 UI(프론트) · 다값 서술의 프론트 표현
- attribute/value 수정·삭제
- `option_summary` 자동 파싱 백필

## 3. 데이터 모델 (Flyway V8, product 최신 V7 다음)

```sql
CREATE TABLE attribute (
    id   BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_name (name)          -- 색상·사이즈·소재… 전역 유일
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attribute_value (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    attribute_id BIGINT NOT NULL,
    value        VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_value (attribute_id, value),   -- 색상→화이트 유일
    KEY idx_attribute_value_attr (attribute_id),
    CONSTRAINT fk_attribute_value_attr FOREIGN KEY (attribute_id) REFERENCES attribute (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 상품이 쓰는 속성 + 역할(변형/서술)
CREATE TABLE product_attribute (
    product_id   BIGINT  NOT NULL,
    attribute_id BIGINT  NOT NULL,
    is_variant   BOOLEAN NOT NULL,                -- true=변형(SKU 정의), false=서술(상품 태그)
    PRIMARY KEY (product_id, attribute_id),
    KEY idx_product_attribute_attr (attribute_id),
    CONSTRAINT fk_product_attribute_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_product_attribute_attr    FOREIGN KEY (attribute_id) REFERENCES attribute (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 변형: SKU의 값 조합 (변형 속성별 1값)
CREATE TABLE sku_attribute_value (
    sku_id             BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    PRIMARY KEY (sku_id, attribute_value_id),
    KEY idx_sku_attr_value_val (attribute_value_id),
    CONSTRAINT fk_sku_attr_value_sku FOREIGN KEY (sku_id)             REFERENCES product_sku (id),
    CONSTRAINT fk_sku_attr_value_val FOREIGN KEY (attribute_value_id) REFERENCES attribute_value (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 서술: 상품 레벨 값 (다속성·다값 허용)
CREATE TABLE product_descriptive_value (
    product_id         BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    PRIMARY KEY (product_id, attribute_value_id),
    KEY idx_product_desc_val_val (attribute_value_id),
    CONSTRAINT fk_product_desc_val_product FOREIGN KEY (product_id)         REFERENCES product (id),
    CONSTRAINT fk_product_desc_val_val     FOREIGN KEY (attribute_value_id) REFERENCES attribute_value (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 전역 `attribute`/`attribute_value`는 상품 간 재사용 → facet 검색 기반.
- `is_variant`는 `product_attribute`에 둠 → 같은 색상이 셔츠엔 변형, 스카프엔 서술일 수 있게 상품별 역할.
- 서술은 `product_descriptive_value`가 (product_id, attribute_value_id) 조인이라 **다속성·다값 자연 허용**.
- SKU 변형 조합 유일성은 앱 레벨 검증(§4) — DB UK만으론 "조합 전체 유일"을 강제하기 어려움(조합이 N행이라).

## 4. 도메인 규칙

- **변형 완전성**: SKU는 상품이 선언한 **모든 변형 속성(is_variant=true)**에 대해 값 하나씩 가져야 한다. 빠지거나 한 속성에 2값이면 400.
- **변형 조합 유일**: 같은 상품 내 두 SKU가 동일 변형 조합을 가질 수 없다(409). 앱에서 조합 정규화(정렬된 value id 집합) 비교로 검증.
- **소속 검증**: `sku_attribute_value`의 attribute_value는 그 상품의 **변형** product_attribute에 속한 attribute의 값이어야 하고, `product_descriptive_value`의 값은 **서술** product_attribute에 속해야 한다(아니면 400).
- **서술**: 완전성·유일성 강제 안 함(상품 태그). 다속성·다값 허용.

## 5. API 계약

- `POST /v1/attributes` `{name}` → `{id}` — 전역 속성 생성(이름 유일).
- `POST /v1/attributes/{id}/values` `{value}` → `{id}` — 속성 값 생성.
- `GET /v1/attributes` → 전역 속성·값 목록(관리·등록 참조용).
- `POST /v1/products` **확장**:
  ```jsonc
  {
    "categoryId": 31, "name": "린넨 셔츠",
    "attributes": [ {"attributeId": 1, "isVariant": true},   // 색상
                    {"attributeId": 2, "isVariant": true},   // 사이즈
                    {"attributeId": 5, "isVariant": false} ],// 소재(서술)
    "descriptiveValueIds": [ 41 ],                            // 소재=울
    "skus": [ { "skuCode": "...", "price": 39000, "initialStock": 100,
                "variantValueIds": [ 11, 21 ] } ]            // 색상=화이트 ∧ 사이즈=M
  }
  ```
  검증: 각 SKU variantValueIds = 선언된 변형 속성 전부 커버(완전) + 조합 유일 + 소속 일치. descriptiveValueIds = 선언된 서술 속성 소속.
- `GET /v1/products/{id}` **확장**: 구조화 노출
  ```jsonc
  { "id":…, "name":…, "category":…,
    "variantOptions": [ {"attribute":"색상","values":["화이트","블랙"]},
                        {"attribute":"사이즈","values":["M","L"]} ],
    "specs":         [ {"attribute":"소재","values":["울"]} ],       // 서술(다값 가능)
    "skus": [ {"skuCode":…,"price":…,"availableQty":…,
               "variant":{"색상":"화이트","사이즈":"M"}} ] }          // 각 SKU 조합
  ```
  기존 `optionSummary`·이미지·가격 필드는 병행 유지(하위호환).

## 6. 하위호환

- `product_sku.option_summary`(자유문자열) 유지 — 표시용. 구조화 속성은 신규 등록부터 얹음. 자동 파싱 백필 안 함(불안정). 기존 상세 응답 필드 제거 없이 추가만.

## 7. 아키텍처 / 레이어 (기존 헥사고날 답습)

- `domain/entity`: Attribute, AttributeValue, ProductAttribute(role), 변형 조합 검증 로직(도메인 규칙 §4).
- `application/service`: AttributeService(전역 사전), CatalogService 확장(등록 시 속성/변형/서술 배선·검증), ProductQueryService 확장(variantOptions/specs 조립).
- `application/interfaces` + `infrastructure/persistence`: 5개 신규 테이블 포트/JPA 어댑터.
- `presentation`: AttributeController + ProductController/SeedRequest 확장 + ProductDetailResponse 확장.

## 8. 불변식 / 가드

- **재고·취소 경로 불변**: StockService·reserve/release·`payment.cancelled` consumer·`product_stock`/`stock_reservation`·`cancel_restore_dlq` 로직 변경 0. git diff(merge-base) 게이트.
- 기존 카테고리 브라우징·상세·이미지·가격 조회 무회귀(응답에 필드 추가만, 제거·변경 없음).
- 마이그레이션 V8만 추가, V1~V7 무변경.

## 9. 테스트 전략 (Testcontainers MySQL)

- 전역 속성/값 생성(이름·값 유일), 목록 조회.
- 변형 완전성(속성 빠짐 → 400), 조합 유일(중복 → 409), 소속 검증(다른 상품 속성값 → 400).
- 서술 다속성·다값 등록 + 상세에 `specs` 정확 노출.
- 상세 `variantOptions`(속성별 값 집합) + 각 SKU `variant` 조합 정확.
- 재고 예약·복원·취소 복원 통합테스트 무회귀 + INV git diff.

## 10. 열린 질문 (계획 단계에서 확정)

- 조합 유일성 검증을 앱에서만 할지, 보조로 정규화 해시 컬럼+UK를 둘지.
- 등록 시 전역 속성/값을 인라인 생성 허용할지(현재: 사전에 별도 생성 후 id 참조).
- `GET /v1/attributes` 페이징 필요 여부(사전 소규모 가정).
- 서술 다값의 응답 그룹핑 형태(attribute별 values 배열 — 위 예시대로).
