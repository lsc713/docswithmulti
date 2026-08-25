# Product 상세 조회 SQL 분석 보고서

- 측정일: 2026-08-19
- 대상: `GET /v1/products/{id}`
- DB: MySQL 8.0.46
- 데이터: 상품 100,000건 / SKU 900,000건 / 재고 900,000건

## 결론

상품 상세 조회는 SKU 수에 비례하는 N+1이 없었다. 다만 카테고리 경로를 노드별로 조회해 3단계 카테고리에서 SQL 3회가 발생했다. 이 구간을 재귀 CTE 한 번으로 바꿔 요청당 SQL을 **8회에서 6회로 25% 감소**시켰다.

100,000개 상품 기준 `EXPLAIN ANALYZE`에서 상품, SKU, 재고, 이미지, 옵션, 상세 속성 조회는 모두 PK 또는 상품 FK 인덱스를 사용했다. 큰 테이블의 전체 스캔은 없었다.

## 측정 데이터

| 테이블 | 행 수 |
|---|---:|
| `product` | 100,000 |
| `product_sku` | 900,000 |
| `product_stock` | 900,000 |
| `sku_attribute_value` | 1,800,000 |
| `product_image` | 300,000 |
| `product_descriptive_value` | 200,000 |

운영 데이터를 건드리지 않도록 임시 DB에 스키마를 복제하고 기존 seed 스크립트로 데이터를 만들었다. 분석 후 임시 DB는 제거한다.

## 요청당 SQL 수

| 조회 단계 | 변경 전 | 변경 후 |
|---|---:|---:|
| 상품 기본 정보 | 1 | 1 |
| 카테고리 경로 | 3 | 1 |
| 옵션 조합 | 1 | 1 |
| SKU 및 재고 | 1 | 1 |
| 이미지 | 1 | 1 |
| 상세 속성 | 1 | 1 |
| 합계 | **8** | **6** |

기존 구현은 leaf에서 root까지 `findById`를 반복했다. 변경 후 MySQL 8 재귀 CTE가 경로를 `level` 순으로 한 번에 반환한다. SKU 4개인 상품의 실제 HTTP 요청을 대상으로 `db.queries.per_request`가 정확히 6인지 회귀 테스트로 고정했다.

## EXPLAIN ANALYZE

| 구간 | 접근 방식 | 관찰 결과 |
|---|---|---|
| 상품 | `product.PRIMARY` | 단건 PK 조회 |
| 카테고리 경로 | recursive CTE + `category.PRIMARY` | 3행 materialize, SQL 1회 |
| 옵션 조합 | `product_attribute.PRIMARY`, `idx_product_sku_product`, 연관 PK | 18행, warm 약 0.21 ms |
| SKU/재고 | `idx_product_sku_product`, `product_stock.PRIMARY` | 9행, warm 약 0.05 ms |
| 이미지 | `idx_product_image_product` | 3행, warm 약 0.03 ms |
| 상세 속성 | 연관 PK 인덱스 | 2행, warm 약 0.03 ms |

카테고리 쿼리의 반복 측정은 warm 상태에서 약 0.006~0.011 ms였다. 변경 전에는 단건 PK SQL을 카테고리 깊이만큼 실행했으므로 DB 실행 시간뿐 아니라 JDBC 왕복도 3번이었다.

절대 시간은 로컬 DB 캐시와 `EXPLAIN ANALYZE` 자체 오버헤드 영향을 받으므로 전후 처리량 개선치로 해석하지 않는다. 코드가 바뀌지 않은 나머지 쿼리의 시간 차이도 캐시 워밍 영향이며 접근 계획은 동일했다.

## 관측 설정

`product-service`가 기존 `common-observability`를 사용하도록 연결했다. 부하테스트 배포에서는 다음 플래그로 요청별 SQL 수를 수집한다.

```bash
LOADTEST_QUERYCOUNT_ENABLED=true
```

기본값은 `false`다. 기준 처리량 측정에는 계측 오버헤드를 제외하기 위해 끄고, SQL 수 검증 구간에서만 켜는 것이 적절하다. 메트릭 이름은 `db.queries.per_request`이며 URI 태그로 상품 상세 요청을 구분한다.

## 검증

- 상품 상세 4 SKU 요청: SQL 6회 회귀 테스트
- `product-service` 테스트: 97개, 실패 0, 오류 0
- 코드 스타일 검사: `git diff --check` 이상 없음

AWS 재측정에서도 요청당 SQL 6회를 확인했다. 쿼리 계측을 끈 기준 처리량은 1,143.75 RPS, ramp 처리량은 1,190.85 RPS였고 이전보다 각각 3.63%, 6.61% 증가했다. 상세 결과와 자원 병목은 [AWS 재측정 보고서](product-detail-query-aws-results-2026-08-19.md)에 기록했다.
