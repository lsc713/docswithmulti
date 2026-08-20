# 상품 상세 캐시·인프라 부하 테스트 Handoff

## 1. 현재 상태

- 캐시 구현은 별도 서비스가 아니라 `product-service` 내부에 반영됨.
- 상품 상세 조회는 Redis 우선 조회를 사용함.
- `fresh`: 즉시 반환
- `stale`: 기존 값 즉시 반환 + 비동기 refresh
- `miss/expired`: 상품 키별 single-flight로 DB 조회 중복을 억제
- 예약·재고 차감의 권위 데이터는 항상 MySQL

구현 브랜치의 캐시 관련 클래스:

```text
product-service/src/main/java/com/example/product/infrastructure/cache/
  ProductDetailCacheService.java
  ProductDetailCachePolicy.java
  ProductDetailCacheState.java
  ProductDetailCacheConfig.java
  ProductDetailCacheInvalidation.java
```

## 2. 검증 결과

### 애플리케이션 테스트

캐시 정책·서비스·상품 브라우징 통합 테스트 통과.

```text
BUILD SUCCESSFUL
```

### AWS private AZ baseline

`product` 프로필로 NAT instance, k6, product, MySQL을 같은 AZ에 구성하고 직접 product-service를 호출했다.

| 분포 | 평균 RPS | p95 | 성공률 |
|---|---:|---:|---:|
| hot | 3,562 | 3.89ms | 100% |
| uniform | 3,569 | 3.86ms | 100% |

이전 DB 중심 기준선 약 1,144 RPS 대비 평균 처리량은 약 3.1배다. 이는 평균 RPS 비교이며 peak RPS 비교가 아니다.

### AWS ramp

단계: 10 VU → 50 VU → 100 VU, 각 3분.

| 최대 VU | 전체 평균 RPS | p95 | p99 | 성공률 |
|---:|---:|---:|---:|---:|
| 100 | 2,857 | 40.2ms | 62.6ms | 100% |

100 VU에서 오류는 없었고 p95/p99는 100~500ms 목표 안에 있다. 다만 결과 파일은 ramp 전체 집계라 각 단계별 knee 지점은 아직 분리되지 않았다.

### VU별 hot baseline (30초)

| VU | 평균 RPS | p95 | p99 | 성공률 |
|---:|---:|---:|---:|---:|
| 10 | 3,639 | 3.79ms | 6.87ms | 100% |
| 15 | 3,788 | 3.53ms | 4.35ms | 100% |
| 25 | 3,798 | 3.51ms | 4.30ms | 100% |
| 50 | 3,809 | 3.50ms | 4.30ms | 100% |
| 100 | 3,818 | 3.48ms | 4.26ms | 100% |
| 150 | 3,816 | 3.49ms | 4.31ms | 100% |
| 200 | 3,828 | 3.49ms | 4.29ms | 100% |

VU 50 이후 평균 처리량은 약 3.8k RPS에서 평탄해졌지만, VU 200까지 p95/p99와 성공률은 안정적이었다. 따라서 현재 단일 인스턴스 구성의 처리량 knee는 약 50 VU 이후로 보이며, 명시적인 장애 포화점은 확인되지 않았다.

## 3. 해석

- 현재 측정 범위에서는 단일 product 인스턴스가 부하를 안정적으로 처리한다.
- VU 증가 시 RPS는 초기에 증가하지만, 포화에 가까워지면 응답시간 증가 때문에 RPS가 정체하거나 감소할 수 있다.
- baseline 약 3.9ms 대비 ramp 전체 p95 40.2ms로 증가했으므로 100 VU 부근에서 경합이 시작됐을 가능성은 있다.
- 현재 결과만으로 다중 인스턴스가 필요하다고 판단할 근거는 부족하다.

## 4. 다음 측정

정확한 포화점 확인을 위해 다음 VU를 개별 실행한다.

```text
10 → 15 → 25 → 50 → 100 → 150 → 200
```

각 단계에서 기록할 항목:

- 평균·peak RPS
- p50/p95/p99
- 에러율
- product CPU·메모리
- Redis hit/miss/stale 비율
- MySQL CPU·connections·slow query

판단 기준:

- product CPU 포화: 수직 확장 또는 수평 확장
- Redis miss/stampede 증가: TTL·refresh-ahead·single-flight 조정
- MySQL CPU/connection 포화: miss 경로·쿼리·풀·읽기 복제 검토
- 자원 여유인데 RPS 정체: k6 시나리오 또는 애플리케이션 스레드 설정 점검

## 5. 운영 주의사항

- 이번 AWS 테스트는 API Gateway를 거치지 않고 product-service를 직접 호출했다.
- Prometheus를 배포하지 않아 ramp 실행 중 remote-write 경고가 있었고, 자원별 시계열은 수집하지 못했다.
- 테스트 종료 후 Terraform 리소스 23개를 모두 destroy했다.
- 결과 파일:

```text
k6/results/20260819T160931Z-baseline-hot-18105.summary.json
k6/results/20260819T161323Z-baseline-uniform-18683.summary.json
k6/results/20260819T162603Z-ramp-hot-21292.summary.json
```
