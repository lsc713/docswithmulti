# Product MySQL EC2 Read Replica 실험 — Design Spec

- **작성일:** 2026-08-24
- **상태:** 사용자 승인 완료, 구현 계획 대기
- **범위:** AWS 부하 테스트의 EC2 Docker MySQL에 비동기 read replica를 추가하고 상품 상세 읽기 효과·복제 지연·장애 fallback을 검증한다.

## 1. 배경과 목표

상품 상세 읽기 경로는 캐시 적중 시 불필요한 트랜잭션을 제거한 뒤 100 VU에서 MySQL CPU가
13.33%까지 낮아졌다. 반면 reserve/release 쓰기는 primary의 동기 COMMIT과 재고 행 잠금에
계속 의존한다. 따라서 read replica가 현재 병목을 해결한다고 가정하지 않고 다음을 실측한다.

1. 지정한 상세 조회가 replica로 분리되는지 확인한다.
2. primary-only 기준선 대비 처리량, 지연, primary/replica CPU 변화를 측정한다.
3. 정상 및 의도적으로 주입한 복제 지연이 사용자에게 stale 재고로 노출되는 시간을 측정한다.
4. stale 화면에서도 reserve가 primary 최신 재고로 판정되어 oversell이 없는지 확인한다.
5. replica 연결 장애 시 지정된 읽기만 primary로 fallback하는지 확인한다.

이 실험은 EC2 MySQL 복제의 효과와 한계를 확인하기 위한 것이다. RDS, 자동 승격, 쓰기
failover, 고가용성 구성은 목표가 아니다.

## 2. 범위

### 포함

- `product-scaleout` 부하 테스트 topology에 `mysql-product-replica` EC2 한 대 추가
- MySQL 8 GTID 기반 비동기 source/replica 구성
- Product 서비스의 primary/replica DataSource와 명시적 읽기 라우팅
- 상품 상세 본문 cache miss와 재고 snapshot cache miss만 replica로 라우팅
- replica 연결 실패·timeout 시 primary 단일 재시도
- 정상 lag, 5·30·60초 SQL applier 정지, 재개 후 수렴 시간 측정
- replica 컨테이너 중지 시 fallback 검증
- 기존 9:1 read/write ramp를 이용한 primary-only A/B 기준선

### 제외

- 모든 `@Transactional(readOnly = true)` 메서드의 일괄 replica 라우팅
- reserve/release, 멱등 조회, 캐시 `AFTER_COMMIT` refresh의 replica 사용
- lag 임계치 기반 자동 primary 전환
- ProxySQL 등 별도 SQL proxy
- replica 승격, 자동 failover, 다중 replica, cross-AZ 복제
- 운영 설정 변경 또는 RDS 비교

## 3. 토폴로지와 복제

```text
k6 -> private NLB -> Product a/b/c/d
                         | write, refresh, fallback
                         v
                  mysql-product source (10.0.1.33)
                         |
                         | MySQL 8 GTID async replication
                         v
                  mysql-product-replica (10.0.1.34)
                         ^
                         | detail/stock cache miss SELECT
                         |
                    Product a/b/c/d
```

- source와 replica는 같은 AZ, 같은 `m7g.large`와 gp3 50 GiB를 사용한다. 복제 외의 변수를
  줄이기 위해 source의 기존 사양을 그대로 재사용한다.
- source는 고유 `server-id`, binary log, `ROW` binlog format, GTID와
  `enforce_gtid_consistency`를 사용한다.
- replica는 별도 `server-id`, relay log, `read_only`와 `super_read_only`를 사용한다.
- 애플리케이션 replica 계정은 `SELECT` 권한만 가진다. 잘못 라우팅된 쓰기는 DB에서도 거부한다.
- 새 스택에서는 source와 replica를 먼저 시작하고 GTID auto-position 복제를 연결한 뒤 Product
  Flyway와 seed를 source에서 실행한다. 초기 DDL과 데이터도 같은 복제 경로를 통과시킨다.
- 기존 단일 MySQL profile은 그대로 유지한다. replica 실험은 별도 Terraform profile/flag로만
  활성화해 기존 측정의 비용과 topology를 바꾸지 않는다.

## 4. 애플리케이션 읽기 라우팅

기존 JPA repository를 복제하지 않는다. primary와 replica Hikari pool 앞에 하나의 routing
DataSource를 두고, 명시적 marker가 있는 호출만 replica route를 선택한다. 기본 route는 항상
primary다. 실제 connection은 route가 정해진 뒤 지연 획득한다.

라우팅 대상은 두 곳뿐이다.

1. `ProductDetailLoader.load(productId)`: 상품 상세 본문 cache miss 시 실행되는 읽기 묶음
2. `ProductStockSnapshotCacheService.getOrLoad(productId)`: 재고 snapshot cache miss 또는
   Redis 읽기 실패 시 실행되는 DB 조회

`@ReplicaRead` interceptor는 대상 호출을 read-only transaction으로 실행한다. replica에서
연결 계열 예외가 발생하면 해당 transaction을 끝내고 같은 멱등 읽기를 primary의 새
transaction에서 한 번만 다시 실행한다. SQL 문법, 데이터 매핑, 도메인 예외는 fallback하지
않고 그대로 실패시킨다. replica pool은 짧은 connection timeout을 사용해 fallback 자체가
긴 tail latency를 만들지 않게 한다.

다음 경로는 marker를 붙이지 않아 primary에 남는다.

- `StockService.reserve/release`와 그 안의 모든 검증·멱등·잠금 조회
- `ProductStockSnapshotCacheService.refreshAfterCommit`
- Flyway, Hibernate 시작 검증, scheduler와 Kafka consumer
- 상품 카드, 카테고리, 속성 등 이번 실험 범위 밖의 read-only API

라우팅 결과는 `product.datasource.route{target="primary|replica",outcome="success|fallback"}`
counter로 기록한다. A/B가 실제로 다른 DB를 사용했는지는 양쪽 Performance Schema SQL digest와
이 counter를 함께 대조한다.

## 5. 일관성과 장애 정책

- MySQL primary가 재고의 유일한 권한 원본이다. replica 결과는 화면 표시용 snapshot이다.
- 복제 지연 중 상세 화면이 오래된 재고를 보여도 구매 가능 여부는 primary의 원자 조건부
  UPDATE로 다시 판정한다. replica stale 값으로 reserve 성공을 결정하지 않는다.
- 정상적인 replication lag은 관측 대상이며 자동 fallback 조건이 아니다.
- replica 연결 거부, connection timeout, 통신 단절에만 primary 단일 재시도를 허용한다.
- primary 재시도도 실패하면 기존 오류 처리대로 5xx를 반환한다. 무한 재시도와 별도 circuit
  breaker는 첫 실험에 추가하지 않는다.
- replica는 읽기 전용이며 승격하지 않는다. source 장애는 기존 primary 장애와 동일하게
  처리하고 이번 실험에서 failover하지 않는다.

## 6. 복제 지연 측정

`Seconds_Behind_Source`만으로는 짧은 지연과 idle 상태를 정확히 설명하기 어렵다. 부하 테스트
runner가 disposable product DB에 실험 전용 marker 행을 만들고 다음 루프를 수행한다.

1. source에 증가하는 `run_key + sequence`와 기록 시각을 INSERT/UPDATE한다.
2. replica에서 같은 sequence가 보일 때까지 runner 단조 시계로 경과 시간을 잰다.
3. 정상 구간과 applier 정지 구간의 p50/p95/p99/max, catch-up 시간을 결과 번들에 저장한다.
4. 동시에 `SHOW REPLICA STATUS`의 thread 상태, GTID와 `Seconds_Behind_Source`를 보조 근거로
   수집한다.

marker는 부하 테스트 전용이며 애플리케이션 Flyway schema에 추가하지 않는다. disposable
스택 삭제와 함께 제거된다. sequence 관측은 DB 호스트 간 wall clock 차이에 의존하지 않는다.

지연 주입은 source I/O 수신은 유지한 채 replica SQL 적용만 5초, 30초, 60초씩 정지한다.
각 구간에서 `STOP REPLICA SQL_THREAD`와 `START REPLICA SQL_THREAD`를 사용하고, 재개 후 marker와
상품/재고 조회가 source 최종 상태에 수렴할 때까지 측정을 계속한다.

## 7. 실험 매트릭스

공통 workload는 기존 상품 재고 혼합 ramp의 9:1 read/write 비율과 동일한 seed, Product 4대,
k6 사양, VU 단계와 구간 길이를 사용한다.

| 런 | DB route/상태 | 검증 목적 |
|---|---|---|
| A | primary-only | 처리량·지연·primary CPU 기준선 |
| B | replica 정상 | 읽기 분산, 정상 lag, source/replica 자원 변화 |
| C | replica route + SQL thread 5·30·60초 정지 | stale 노출과 catch-up 시간, reserve 정합성 |
| D | replica 컨테이너 중지 | 연결 장애 primary fallback |

각 런은 다음을 남긴다.

- read/write RPS, p95, p99, 오류율
- Product 각 인스턴스, source MySQL, replica MySQL, Redis와 k6 CPU/메모리
- source/replica SQL digest와 COMMIT 수
- route success/fallback counter
- marker lag p50/p95/p99/max와 재개 후 수렴 시간
- cache hit/miss/write/fallback
- reserve/release 결과, 최종 reservation 상태와 전체 SKU 재고 합계

## 8. 성공 조건

- 지정된 정상 읽기는 replica에서 실행되고 쓰기와 제외 경로는 primary에서 실행된다.
- 모든 reserve 판정과 재고 mutation은 primary에서만 실행된다.
- A~C의 서버 오류와 예기치 않은 4xx는 0건이다.
- 지연 주입 중 stale 표시는 허용하지만 reserve 결과는 primary 최신 재고와 항상 일치한다.
- SQL thread 재개 후 marker와 상품/재고 조회가 최종 source 상태로 수렴한다.
- D에서 replica 연결 장애 대상 읽기의 primary fallback 성공률은 100%다.
- 모든 런 종료 후 RESERVED 잔존 수와 SKU 재고 합계가 workload 기대값과 일치하고 oversell은
  0건이다.
- 성능 향상은 성공 조건이 아니다. 처리량, 지연, primary CPU와 lag 분포를 그대로 보고해
  현재 트래픽에서 replica가 비용 대비 유효한지 판단한다.

## 9. 검증 전략

구현 검증은 가장 작은 실패 가능한 경계에 둔다.

- routing 단위 테스트: marker 없음=primary, `@ReplicaRead`=replica, 연결 예외=primary 1회,
  SQL/매핑 예외=재시도 없음
- 서비스 테스트: 상세 loader와 stock cache miss만 replica marker를 사용하고
  `refreshAfterCommit`과 reserve/release는 사용하지 않음
- MySQL 통합 smoke: GTID replication thread 정상, source write가 replica에 도착,
  replica 애플리케이션 계정 write 거부
- 배포 smoke: Product 4대의 primary/replica 연결과 route counter 확인 후 본 부하 시작
- 런 종료 검증: SQL digest, marker sequence, reservation 상태, SKU 재고 합계를 교차 확인

무작위 실행 순서가 필요한 정확한 효과 크기는 새 스택에서 `A→B→B→A` 중앙값으로 후속
검증한다. 첫 실험은 기능·정합성·병목 방향 확인을 우선한다.

## 10. 변경 예상 범위

- `infra/load-test/instances.tf`: replica 실험용 EC2 role
- `infra/load-test/deploy/`: source/replica MySQL 설정과 시작 순서
- `infra/load-test/deploy/product*.compose.yml`: replica 접속 환경 변수
- `product-service`: DataSource routing, `@ReplicaRead`, 제한된 fallback과 route metric
- `k6/run-product-stock-mix-aws.sh` 및 작은 보조 스크립트: marker/lag 수집과 장애 주입
- `docs/load-test/`: 실행 절차와 측정 결과

새 proxy, 복제 repository, lag 기반 circuit breaker, 운영용 heartbeat scheduler는 만들지 않는다.

## 11. 안전과 비용

- Terraform replica profile을 명시적으로 선택할 때만 추가 EC2가 생성된다.
- 장애 주입 명령은 role과 run key가 일치하는 disposable replica만 대상으로 한다.
- 운영 DB 직접 DDL이나 replication 명령은 사용하지 않는다.
- 모든 런 종료 후 `terraform destroy`와 빈 state를 확인한다.
- replica는 성능 실험 대상이므로 source와 같은 사양을 사용하되, 실험 종료 즉시 제거한다.
