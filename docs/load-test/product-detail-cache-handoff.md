# 상품 상세 캐시·부하 측정 handoff

기준일: 2026-08-20. 대상은 사설 IP만 사용하는 AWS `product` 프로파일이다.

## 측정 구성

- product-service 1대 (`c7g.xlarge`), MySQL 1대 (`m7g.large`), k6 1대, Prometheus 1대
- product 상세 hot-key 요청, 100 VU, 30초
- Redis read-through 캐시와 single-flight 적용 상태
- Prometheus readiness 및 `up` 확인 후 부하를 시작했다.

## 결과

| 항목 | 값 |
|---|---:|
| 처리량 | 2,686 RPS |
| HTTP p95 | 72.5ms |
| HTTP p99 | 104.7ms |
| 오류율 | 0% |
| product 최대 CPU | 94.1% |
| MySQL 최대 CPU | 59.9% |
| k6 최대 CPU | 61.3% |
| MySQL 동시 실행 쿼리 최대 | 4 |

DB 포화 증거는 없고, 현재 포화 지점은 product JVM/인스턴스다.

## Micrometer + JFR 수집

추가한 Micrometer 타이머:

- `product.detail.cache.read`: Redis 조회와 JSON 역직렬화
- `product.detail.response.assembly`: `ProductDetailResponse` 조립

같은 100 VU 구간에서 관측된 평균:

| 구간 | 평균 |
|---|---:|
| 캐시 읽기·역직렬화 | 0.46ms |
| 응답 DTO 조립 | 2.16ms |

JFR 60초 `profile` 녹화:

- `ExecutionSample`: 4,852건
- `ObjectAllocationSample`: 14,271건
- Jackson 역직렬화/직렬화, `HashMap`, `StringBuilder`, `URI`/`URLEncoder`가 상위 CPU 샘플에 나타남
- G1 evacuation pause가 반복됐고, 일부는 207~288ms였다.

## 해석 시 주의

긴 GC pause가 존재하는 것은 확인됐지만, 이것만으로 HTTP p99 104.7ms의 직접 원인이라고 확정할 수는 없다. p99는 상위 1%를 제외하며, JFR 60초 구간은 30초 부하보다 길다. 다음 측정에서는 JFR 시작/종료를 부하와 맞추고 HTTP p99.9/max 및 `jvm_gc_pause_seconds`를 같은 시간축에서 비교한다.

현재 타이머는 평균만 확인했다. 캐시 miss/stale refresh, 큰 payload, 이미지 수가 많은 상품의 tail 비용을 판정하려면 histogram p95/p99와 cache state 태그가 필요하다. 평균 0.46ms·2.16ms만으로 캐시 역직렬화나 DTO 조립을 우선 최적화할 근거는 없다.

## 수평 확장 판단

즉시 처리량이 필요하면 product 인스턴스를 2대로 늘리는 것은 유효하다. 다만 현재 product 프로파일은 각 인스턴스가 로컬 Redis를 사용한다. 2대로 확장하면 캐시·single-flight lock·무효화가 인스턴스마다 분리되어 다음 문제가 생긴다.

- 서버별 캐시 불일치와 stale 응답
- cache hit율 하락 및 DB miss 중복
- 서버 간 cache stampede 방지 불가
- API Gateway/LB의 다중 target 라우팅 및 인스턴스별 관측 필요

상품 상세의 가격·재고 정확도를 보장하려면 공용 Redis로 cache key, lock, invalidation을 공유한 뒤 수평 확장하는 방식을 권장한다. 가격/재고는 stale 허용 범위를 별도로 정의한다.

## 다음 순서

1. 부하 시작과 동시에 JFR을 시작하고 종료하며 p99.9/max·GC pause를 동기화한다.
2. cache state(fresh/stale/miss)별 histogram을 추가해 tail의 원인을 확인한다.
3. 처리량이 즉시 필요하면 공용 Redis와 LB를 준비한 뒤 product 2대에서 동일 조건으로 재측정한다.
