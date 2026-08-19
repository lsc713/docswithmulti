# Product 상세 `EXPLAIN ANALYZE` 및 인덱스 검토

## 기술 요약

로컬 `mysql-product`에서 Product 상세 조회를 구성하는 6개 SQL을 실제 `EXPLAIN ANALYZE`로 확인했다. SKU 재고 조회는 `idx_product_sku_product`와 `product_stock`의 PK를 사용했고, 이미지 조회는 `idx_product_image_product`를 사용했다. 변형·서술 속성 조회도 `product_attribute`의 PK, `attribute_value`의 유니크 키, `sku_attribute_value`의 인덱스를 사용했다.

현재 증거만으로 기존 인덱스가 빠졌다고 판단할 수 없다. AWS 부하 테스트의 첫 병목은 MySQL CPU 약 98%였으므로, 인덱스는 실행계획에서 실제 대량 행 읽기가 확인되는 경우에만 추가한다.

## 측정 범위와 한계

- DB: 로컬 Docker `mysql-product`, MySQL 8
- 대상: `product_id = 10`
- 로컬 데이터: 상품 14개, SKU 22개, 이미지 7개, SKU 속성 연결 16개
- AWS 부하 테스트 데이터: 상품 100,000개, SKU 900,000개
- 따라서 아래 실행 시간은 AWS 성능 수치가 아니며, 인덱스 사용 여부와 조인 형태를 검증하는 용도다.

## 실행계획 결과

| SQL | 실제 접근 경로 | 관찰 |
|---|---|---|
| 상품 본체 | `product` PK | 단일 행 조회 |
| 카테고리 경로 CTE | `category` PK로 parent 역방향 탐색 | 3단계, 실제 0.239ms |
| SKU 재고 | `idx_product_sku_product` → `product_stock` PK | 상품 SKU 4개, 풀스캔 없음 |
| 이미지 | `idx_product_image_product` | 상품 이미지 2개, 정렬 인덱스 포함 |
| 변형 속성 | `product_attribute` PK → `attribute_value.uk_attribute_value` → `sku_attribute_value.idx_sku_attr_value_val` | `ORDER BY s.id, a.id` 정렬 발생 |
| 서술 속성 | `product_attribute` PK → `product_descriptive_value` PK → `attribute_value`/`attribute` PK | 대상 행 0개, 기존 키 사용 |

### 변형 쿼리의 주의점

로컬 계획에서는 `product_sku`가 22행뿐이라 `Table scan on s`가 선택됐다. 이는 작은 테이블에서 인덱스 탐색보다 스캔이 싸다고 판단한 결과다. AWS의 900,000 SKU 환경에서는 `WHERE s.product_id = :productId`가 `idx_product_sku_product`를 사용하는지 대규모 데이터에서 다시 확인해야 한다.

## 인덱스 후보

### 후보 A: `product_attribute(product_id, is_variant, attribute_id)`

```sql
CREATE INDEX idx_product_attribute_product_variant
    ON product_attribute (product_id, is_variant, attribute_id);
```

변형·서술 쿼리가 `product_id`와 `is_variant`를 함께 필터링하므로 후보가 될 수 있다. 다만 현재 PK `(product_id, attribute_id)`가 이미 `product_id` 범위를 매우 작게 줄인다. 상품당 속성 수가 적다면 효과가 거의 없고, 인덱스 쓰기·메모리 비용만 늘 수 있다.

**판정: 조건부 후보.** AWS 크기 데이터에서 `rows examined`가 상품당 속성 수보다 크게 나오거나 `is_variant` 필터 제거량이 크면 테스트한다.

### 후보 B: `product_sku(product_id, id)`

```sql
CREATE INDEX idx_product_sku_product_id
    ON product_sku (product_id, id);
```

현재 `idx_product_sku_product(product_id)`가 있고 `ORDER BY s.id`가 있다. InnoDB 보조 인덱스의 레코드 위치와 PK 접근이 추가되므로 후보 B는 중복 가능성이 높다.

**판정: 추가하지 않음.** 먼저 기존 인덱스가 대규모 데이터에서도 정렬을 제거하는지 확인한다.

### 후보 C: `sku_attribute_value(sku_id, attribute_value_id)`

```sql
CREATE INDEX idx_sku_attribute_value_sku
    ON sku_attribute_value (sku_id, attribute_value_id);
```

현재 PK가 이미 `(sku_id, attribute_value_id)`다.

**판정: 추가하지 않음.** 완전한 중복 인덱스다.

### 후보 D: 이미지 인덱스

현재 `product_image(product_id, sort_order)`가 있고 쿼리 정렬은 `sort_order, id`다. 같은 `sort_order`가 존재할 수 있지만 현재 인덱스 레코드에 PK가 뒤따르므로 대부분의 경우 추가 인덱스가 필요하지 않다.

**판정: 추가하지 않음.** 이미지 행이 상품당 매우 많고 `Using filesort`가 실제로 확인될 때만 `(product_id, sort_order, id)`를 비교한다.

## 권장 검증 순서

1. AWS와 동일하게 100,000 상품·900,000 SKU 데이터로 로컬 또는 임시 MySQL을 구성한다.
2. 아래 6개 쿼리에 대해 `EXPLAIN ANALYZE`를 저장한다.
3. 후보 A를 invisible index로 만든 뒤 동일 쿼리의 `actual time`, `rows`, `Sort` 여부를 비교한다.
4. 개선이 확인된 경우에만 Flyway migration으로 반영한다.
5. 인덱스 전후 동일 부하에서 RPS, p95/p99, MySQL CPU, buffer pool read를 비교한다.

```sql
ALTER TABLE product_attribute
  ADD INDEX idx_product_attribute_product_variant
  (product_id, is_variant, attribute_id) INVISIBLE;

-- 검증 후 효과가 있을 때만 V__ migration에서 VISIBLE 인덱스로 확정
ALTER TABLE product_attribute
  ALTER INDEX idx_product_attribute_product_variant VISIBLE;
```

## 결론

현재 실행계획에서는 즉시 추가해야 할 필수 인덱스가 발견되지 않았다. 가장 합리적인 실험 대상은 `product_attribute(product_id, is_variant, attribute_id)` 하나지만, 이것도 대규모 데이터에서 행 제거량과 CPU가 실제로 줄어드는지 확인한 뒤 채택한다. MySQL CPU 포화가 지속되면 인덱스보다 먼저 MySQL 인스턴스 상향 또는 상품 상세 read-through 캐시를 비교해야 한다. Hikari 풀만 늘리는 것은 DB 포화를 악화시킬 수 있으므로 단독 처방으로 사용하지 않는다.
