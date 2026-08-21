# Product stock mixed ramp — MySQL 포화점 측정

- 측정일: 2026-08-20 (AWS `ap-northeast-2`)
- 경로: 동일 AZ 사설망의 k6 → private NLB → Product 4대 → 공용 Redis/MySQL
- 워크로드: Product 상세 조회(read)와 재고 reserve/release(write)를 약 9:1 VU 비율로 병행
- 구간: 각 3분. 표의 MySQL CPU는 해당 구간 **종료 시점** Prometheus 표본이다.
- 성공 기준: 서버 오류율 및 예기치 않은 4xx 오류율 0%

## 결론

MySQL CPU 포화는 읽기 **25~50 VU 사이**에서 시작한다.

- 읽기 10 VU 유지에서는 MySQL CPU 63.1%, RPS 3,651로 여유가 있다.
- 읽기 10→25 VU 종료 시 CPU는 88.9%다.
- 읽기 25→50 VU 구간부터 CPU가 99.5%에 도달하고, 이후 VU를 올려도 처리량은 약 7.8k RPS에서 증가하지 않는다.
- 모든 런에서 오류율은 0%였으므로, 오류가 아니라 CPU 포화와 대기 증가가 처리량 상한을 만든다.

정확한 최초 포화 VU가 필요하면 다음 런을 `25 → 30 → 35 → 40 → 45 → 50`으로 실행한다.

## 최종 세분화 런: 읽기 10→100 VU

런 키: `20260820T155859Z-product-stock-mix-35971`

| 구간 | 읽기 VU | 쓰기 VU | 총 RPS | MySQL CPU | workload p95 |
|---|---:|---:|---:|---:|---:|
| 유지 | 10 | 1 | 3,651 | 63.1% | 7.6ms |
| 램프 | 10→25 | 1→3 | 6,928 | 88.9% | 8.6ms |
| 램프 | 25→50 | 3→6 | 7,854 | 99.5% | 13.6ms |
| 램프 | 50→75 | 6→8 | 7,886 | 99.6% | 20.3ms |
| 램프 | 75→100 | 8→11 | 7,848 | 99.7% | 26.4ms |

전체: 평균 6,585 RPS, p95 18.5ms, p99 27.2ms, 실패율 0%.

## 교차 확인 런: 읽기 100→200 VU

런 키: `20260820T151009Z-product-stock-mix-28785`

| 구간 | 읽기 VU | MySQL CPU | 총 RPS | workload p95 |
|---|---:|---:|---:|---:|
| 유지 | 100 | 99.4% | 9,106 | 32ms |
| 램프 | 100→125 | 99.8% | 7,891 | 35ms |
| 램프 | 125→150 | 99.8% | 7,598 | 46ms |
| 램프 | 150→175 | 99.8% | 7,537 | 54ms |
| 램프 | 175→200 | 99.8% | 7,409 | 61ms |

전체: 평균 8,011 RPS, p95 54ms, p99 76ms, 실패율 0%.

## 상위 범위 교차 확인: 읽기 250→500 VU

런 키: `20260820T141644Z-product-stock-mix-20373`

| 구간 | 읽기 VU | MySQL CPU | 총 RPS | workload p95 |
|---|---:|---:|---:|---:|
| 유지 | 250 | 99.2% | 8,762 | 111ms |
| 램프 | 250→300 | 99.6% | 8,617 | 125ms |
| 램프 | 300→350 | 99.5% | 8,500 | 151ms |
| 램프 | 350→400 | 99.5% | 8,060 | 152ms |
| 램프 | 400→450 | 99.8% | 7,415 | 149ms |
| 램프 | 450→500 | 99.8% | 7,214 | 164ms |

전체: 평균 8,170 RPS, p95 159ms, p99 237ms, 실패율 0%.

## 해석과 다음 조치

1. 인스턴스 증설만으로는 이 상한을 넘길 수 없다. 병목은 Product 노드가 아니라 공용 MySQL CPU다.
2. 캐시 적중률을 높여도 reserve/release 쓰기는 MySQL을 경유한다. 읽기와 쓰기를 분리한 비율 변화 측정으로 DB 쓰기 비용을 별도 확인한다.
3. `threads_running` 관측은 이번 번들에서 비어 있었으므로, exporter 라벨/쿼리를 보정한 뒤 연결 대기·락 대기와 함께 재측정한다.
4. DB 튜닝 후보는 실제 느린 쿼리와 `EXPLAIN ANALYZE`를 기반으로 인덱스, 트랜잭션 범위, 행 잠금 경합 순으로 검토한다. CPU가 이미 포화된 상태에서 인스턴스 상향은 임시 상한 완화일 뿐 원인 제거는 아니다.

## 후속 실험: write-only `refreshAfterCommit` A/B

- 측정일: 2026-08-21 (AWS `ap-northeast-2`)
- 토폴로지: 동일한 Product 4대, 공용 Redis/MySQL, private NLB
- 워크로드: `reserve` 성공 후 같은 예약을 `release`하는 write-only 시나리오
- 구간: `1 → 3 → 6 → 8 → 11 VU`, 각 3분
- 비교 변수: `product.cache.refresh-after-commit-enabled`만 `false`/`true`로 변경
- 표의 RPS와 MySQL CPU는 해당 구간 Prometheus 표본의 평균

### 결론

`refreshAfterCommit`은 쓰기 비용의 일부지만 주 병목은 아니다. 비활성화하면 전체 처리량이
**400.85 → 453.00 RPS(+13.0%)**로 증가하고 p95는 **12.71 → 11.29ms(-11.2%)**로
감소했다. 마지막 `8→11 VU` 구간에서도 RPS는 **737.0 → 847.8(+15.0%)**, MySQL CPU는
**76.9% → 73.6%(-3.3%p)**였다.

MySQL SQL digest에서 `refreshAfterCommit`이 추가하는 재고 스냅샷 조회는 ON 런 기준
360,274회, 누적 65.0초였다. 같은 런의 `ProductSku.findById`는 360,737회, 누적 41.6초였다.
따라서 반복 `findById`보다 refresh 조회의 누적 DB 비용이 더 컸다. 다만 `COMMIT`은
360,405회, 누적 1,684.0초로 두 조회보다 압도적으로 컸다. 최우선 분석 대상은 여전히
트랜잭션 커밋 경로이며, refresh 제거만으로 쓰기 병목 전체가 해소되지는 않는다.

### 결과

| 구간 | OFF RPS | OFF MySQL CPU | OFF p95 | ON RPS | ON MySQL CPU | ON p95 |
|---|---:|---:|---:|---:|---:|---:|
| 1 VU | 85.8 | 22.3% | 12.93ms | 85.9 | 22.4% | 10.82ms |
| 1→3 VU | 149.6 | 35.4% | 11.50ms | 133.3 | 33.1% | 10.81ms |
| 3→6 VU | 375.5 | 57.3% | 10.83ms | 340.3 | 59.3% | 10.87ms |
| 6→8 VU | 636.4 | 69.0% | 10.91ms | 559.4 | 72.5% | 11.76ms |
| 8→11 VU | 847.8 | 73.6% | 11.11ms | 737.0 | 76.9% | 12.46ms |

| 항목 | OFF | ON |
|---|---:|---:|
| 총 요청 | 407,712 | 360,768 |
| 평균 RPS | 453.00 | 400.85 |
| p95 | 11.29ms | 12.71ms |
| p99 | 12.50ms | 13.86ms |
| 실패율 | 0% | 0% |
| stock cache write | 전 구간 0 | 발생 확인 |

SQL digest는 각 요청이 의도한 신규 예약 경로를 탔는지도 검증했다. ON 런의 iteration은
180,384회였고 예약 INSERT 180,371회, 조건부 차감 180,368회, 복원 180,359회로 거의
일치했다. 수집 경계의 진행 중 요청 차이만 존재한다.

### 무효 런과 부하 테스트 수정

첫 ON 대조군 `20260821T053103Z-product-stock-mix-38897`은 결과에서 제외했다. 기존
`paymentKey`가 `VU-iteration`만 포함해 직전 런의 키와 충돌했고, 282,838 iteration 중
조건부 재고 차감이 약 84,917회만 실행됐다. 나머지는 멱등 재호출 경로였으므로 OFF와 같은
쓰기 작업이 아니다.

`paymentKey`에 `RUN_KEY`를 포함하도록 `adea9d0`에서 수정하고 회귀 검사를 추가했다.
최종 ON 런은 이 수정본으로 측정했다. 실험용 refresh 토글 구현은 `77093a4`다.

### 해석 한계

두 유효 런은 같은 사양과 데이터셋을 사용했지만 실행 순서를 무작위화하지 않았고 MySQL을
런마다 재기동하지 않았다. 따라서 절대 차이에는 버퍼 풀과 CPU 워밍 영향이 포함될 수 있다.
방향을 더 엄격하게 확정해야 한다면 새 스택에서 워밍 후 `OFF→ON→ON→OFF` 순서로 반복하고
중앙값을 비교한다.

### 결과 파일과 자원 정리

- [refresh OFF summary](../../k6/results/20260821T051132Z-product-stock-mix-35370.summary.json)
- [refresh ON summary](../../k6/results/20260821T054808Z-product-stock-mix-41115.summary.json)

측정 종료 후 Terraform 리소스 36개를 삭제했고 빈 Terraform state를 확인했다. 로컬 결과
번들은 유지했다.

## 후속 실험: COMMIT 지연 원인 분리

- 측정일: 2026-08-21 (AWS `ap-northeast-2`)
- 워크로드: write-only `1 → 3 → 6 → 8 → 11 VU`, 각 3분
- 공통 조건: Product 4대, MySQL 1대, binlog ON, `sync_binlog=1`, refresh ON
- 비교 조건: 기본 내구성/균등 SKU, redo flush 완화/균등 SKU, 기본 내구성/단일 hot SKU
- SQL digest, InnoDB status, Performance Schema file wait, 호스트 `iostat -x`를 런마다 초기화해 수집

### 결론

균등 분포의 주된 COMMIT 지연 원인은 스토리지 flush다. `innodb_flush_log_at_trx_commit`만
`1 → 2`로 바꾸자 COMMIT 평균은 **4.984 → 3.028ms(-39.2%)**, p95는
**6.918 → 4.571ms(-33.9%)**로 줄었고 처리 transaction은 **426,370 → 516,089(+21.0%)**로
증가했다. redo fsync 증가량은 **623,903 → 4,316(-99.3%)**, 평균 CPU iowait은
**23.33 → 8.62%**로 감소했다.

다만 `flush=2`에서도 전체 workload p95는 11.14 → 11.01ms로 비슷했고 p99는
12.70 → 13.25ms로 소폭 늘었다. redo flush가 줄면서 마지막 11 VU의 MySQL CPU가
81.9 → 95.2%로 올라가 CPU/쿼리 처리와 `sync_binlog=1`의 binlog 동기화가 다음 한계가 됐다.
따라서 설정 변경은 원인 확인에는 성공했지만 단독 해결책은 아니다.

단일 hot SKU에서는 행 잠금이 별도의 큰 병목이었다. 처리 transaction은 균등 대비
**426,370 → 186,546(-56.2%)**, 전체 workload p95는 **11.14 → 40.96ms**로 악화됐다.
재고 행 UPDATE는 평균 약 13.5~13.8ms, p95 34.67ms였고 행 잠금 대기는
**145,364건/2,501.6초**, 최대 75ms였다. 8 VU 표본에서 동시에 관측된
`data_lock_waits`는 21건이었다.

즉 우선순위는 다음과 같다.

1. 일반적인 균등 트래픽: redo/binlog 동기 flush와 스토리지 I/O가 우선 병목이다.
2. 인기 SKU 집중 트래픽: 같은 `product_stock` 행의 잠금 직렬화가 더 큰 병목이 된다.
3. 동시 커밋/group commit은 일부 fsync를 합치지만 기본 설정에서 스토리지 대기를 숨길 만큼
   충분하지 않았다. flush 완화 뒤에는 CPU가 다음 상한으로 이동했다.

### 전체 결과

| 조건 | iteration | 평균 iteration/s | workload p95 | workload p99 | 실패율 |
|---|---:|---:|---:|---:|---:|
| flush=1, uniform | 214,214 | 238.01 | 11.14ms | 12.70ms | 0% |
| flush=2, uniform | 258,979 | 287.75 | 11.01ms | 13.25ms | 0% |
| flush=1, hot SKU | 93,273 | 103.63 | 40.96ms | 43.47ms | 0% |

| 조건 | COMMIT 수 | 평균 | p95 | p99 | redo fsync 증가 | 행 잠금 증가 |
|---|---:|---:|---:|---:|---:|---:|
| flush=1, uniform | 426,370 | 4.984ms | 6.918ms | 7.586ms | 623,903 | 1,987건 / 4.24초 |
| flush=2, uniform | 516,089 | 3.028ms | 4.571ms | 6.026ms | 4,316 | 5,121건 / 6.66초 |
| flush=1, hot SKU | 186,546 | 3.663ms | 4.786ms | 5.012ms | 478,629 | 145,364건 / 2,501.59초 |

hot SKU의 COMMIT 자체가 균등보다 짧은 것은 잠금에서 먼저 직렬화돼 동시에 COMMIT에
진입하는 transaction 수가 줄었기 때문이다. endpoint 지연은 COMMIT이 아니라 재고 행
UPDATE의 잠금 대기에서 크게 증가했다.

| 조건 | 평균 write/s | 평균 write await | 평균 disk util | 최대 disk util | 평균 CPU iowait |
|---|---:|---:|---:|---:|---:|
| flush=1, uniform | 1,429.88 | 0.905ms | 67.47% | 87.20% | 23.33% |
| flush=2, uniform | 852.92 | 0.924ms | 61.35% | 90.80% | 8.62% |
| flush=1, hot SKU | 1,043.05 | 0.913ms | 71.42% | 86.40% | 25.71% |

### 단계별 HTTP 처리량과 지연

표의 RPS, p95, MySQL CPU는 각 3분 구간의 종료 표본이다.

| VU | flush=1 uniform RPS / p95 / CPU | flush=2 uniform RPS / p95 / CPU | flush=1 hot RPS / p95 / CPU |
|---:|---:|---:|---:|
| 1 | 117 / 12.17ms / 31.0% | 164 / 7.21ms / 21.2% | 129 / 9.37ms / 34.3% |
| 3 | 245 / 11.11ms / 50.1% | 324 / 7.26ms / 36.9% | 236 / 9.57ms / 50.6% |
| 6 | 562 / 10.77ms / 70.1% | 726 / 7.92ms / 57.5% | 242 / 20.81ms / 50.9% |
| 8 | 751 / 10.82ms / 76.3% | 917 / 8.63ms / 80.1% | 241 / 29.41ms / 51.0% |
| 11 | 1,000 / 11.13ms / 81.9% | 1,038 / 10.97ms / 95.2% | 240 / 40.77ms / 50.8% |

hot SKU는 6 VU부터 약 240 RPS에서 처리량이 더 늘지 않고 지연만 증가했다. 반대로 균등
분포는 11 VU까지 처리량이 증가했으며, flush=2에서는 낮은 VU의 지연 개선이 특히 컸다.

### 운영 해석

`innodb_flush_log_at_trx_commit=2`는 마지막 1초 이내 transaction을 OS 또는 호스트 장애에서
잃을 수 있으므로 이 실험에서는 진단 변수로만 사용했다. 운영 변경안으로 바로 적용하지 않는다.
또한 binlog와 `sync_binlog=1`은 유지했으므로 이번 결과는 redo flush만 완화한 비교다.

운영 대안은 먼저 EBS gp3 IOPS/throughput 상향 또는 DB 인스턴스/스토리지 상향을 같은
내구성(`flush=1`, `sync_binlog=1`)에서 재검증하고, 동시에 hot SKU에 대해서는 transaction
범위 단축과 재고 갱신 직렬화 방식을 별도로 검토하는 것이다. primary-replica는 읽기 확장에는
도움이 되지만 이 쓰기 COMMIT/단일 행 잠금 병목을 직접 해소하지 않는다.

### 결과 파일

- [flush=1 uniform summary](../../k6/results/20260821T062849Z-product-stock-mix-46139.summary.json)
- [flush=2 uniform summary](../../k6/results/20260821T064607Z-product-stock-mix-49146.summary.json)
- [flush=1 hot SKU summary](../../k6/results/20260821T070432Z-product-stock-mix-51797.summary.json)

핫키 분포 옵션은 `c7fe937`에서 추가했다. 측정 종료 시
`innodb_flush_log_at_trx_commit=1` 복구를 확인했다. 진단 수집 후 Terraform 리소스 36개를
삭제했고 빈 Terraform state를 확인했다.

## 후속 실험: gp3 IOPS/throughput 상향

- 측정일: 2026-08-21 (AWS `ap-northeast-2`)
- 공통 조건: write-only uniform `1 → 3 → 6 → 8 → 11 VU`, 각 3분, `flush=1`,
  `sync_binlog=1`, 같은 애플리케이션 이미지와 50 GiB gp3 볼륨
- 기준: gp3 3,000 IOPS / 125 MiB/s
- 변경: gp3 6,000 IOPS / 250 MiB/s

### 결론

gp3 상향 효과는 없었다. 처리량은 **238.01 → 240.50 iteration/s(+1.0%)**에 그쳤고
workload p95는 **11.14 → 11.90ms(+6.8%)**로 개선되지 않았다. COMMIT 평균은
**4.984 → 4.912ms(-1.4%)**였지만 p95/p99는 각각 **6.918/7.586ms로 동일**했다.

상향 런의 실제 평균 디스크 쓰기는 1,436.61 IOPS와 약 12 MiB/s로, 3,000 IOPS와
125 MiB/s인 기존 gp3 한도보다 낮았다. 평균 write await도 0.899ms로 기준선 0.905ms와
같은 수준이다. 따라서 이번 워크로드는 gp3의 프로비저닝 IOPS/throughput 한도 때문에
막힌 것이 아니라 짧고 빈번한 동기 flush와 그에 따른 커밋 대기 비용이 주원인이다.

| 항목 | 3,000 / 125 | 6,000 / 250 |
|---|---:|---:|
| iteration | 214,214 | 216,452 |
| 평균 iteration/s | 238.01 | 240.50 |
| workload p95 / p99 | 11.14 / 12.70ms | 11.90 / 13.23ms |
| COMMIT 수 | 426,370 | 431,424 |
| COMMIT 평균 / p95 / p99 | 4.984 / 6.918 / 7.586ms | 4.912 / 6.918 / 7.586ms |
| redo fsync 증가 | 623,903 | 647,436 |
| 행 잠금 증가 | 1,987건 / 4.24초 | 7,532건 / 18.95초 |
| 평균 write/s | 1,429.88 | 1,436.61 |
| 평균 write await | 0.905ms | 0.899ms |
| 평균 / 최대 disk util | 67.47 / 87.20% | 69.07 / 90.80% |
| 평균 / 최대 CPU iowait | 23.33 / 32.80% | 25.04 / 36.83% |
| 실패율 | 0% | 0% |

| VU | 3,000 / 125 RPS / p95 / CPU | 6,000 / 250 RPS / p95 / CPU |
|---:|---:|---:|
| 1 | 117 / 12.17ms / 31.0% | 120 / 11.93ms / 30.9% |
| 3 | 245 / 11.11ms / 50.1% | 255 / 11.10ms / 49.4% |
| 6 | 562 / 10.77ms / 70.1% | 567 / 11.26ms / 69.8% |
| 8 | 751 / 10.82ms / 76.3% | 758 / 11.56ms / 74.9% |
| 11 | 1,000 / 11.13ms / 81.9% | 1,008 / 11.88ms / 79.9% |

- [gp3 3,000/125 summary](../../k6/results/20260821T062849Z-product-stock-mix-46139.summary.json)
- [gp3 6,000/250 summary](../../k6/results/20260821T074632Z-product-stock-mix-55224.summary.json)

MySQL에만 gp3 값을 지정하는 Terraform 변수와 plan 검사는 `0099cfc`에서 추가했다.
한 번의 A/B 결과이므로 1% 처리량 차이는 실행 변동 범위로 해석한다.
측정 종료 후 Terraform 리소스 36개를 삭제했고 빈 Terraform state를 확인했다.

## 후속 실험: binlog group commit delay

- 측정일: 2026-08-21 (AWS `ap-northeast-2`)
- 공통 조건: write-only uniform `1 → 3 → 6 → 8 → 11 VU`, 각 3분,
  `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`
- 스토리지: gp3 50 GiB, 3,000 IOPS, 125 MiB/s
- 비교 순서: `delay=0µs/count=0` → `200µs/8` → `500µs/16`
- 같은 애플리케이션 이미지, 1,000개 데이터셋, Product 4대와 MySQL 1대를 연속 사용

### 결론

이 워크로드에서는 binlog group commit delay가 처리량과 지연을 모두 악화시켰다.
`200µs/8`은 기준선 대비 처리량이 **9.4% 감소**하고 workload p95가 **16.5% 증가**했다.
`500µs/16`은 처리량이 **12.3% 감소**하고 p95가 **17.0% 증가**해 더 나빴다.

delay가 커질수록 평균 write/s와 redo fsync는 줄었지만, COMMIT 수도 거의 같은 비율로
감소했다. COMMIT당 redo fsync는 약 **1.442 → 1.406 → 1.333**, 물리 write는 약
**3.09 → 2.95 → 2.83회**로 소폭 병합됐으나 추가 대기 비용을 상쇄하지 못했다.
write await는 세 조건 모두 약 0.9ms로 같아 스토리지 단건 지연도 개선되지 않았다.

| 항목 | 0µs / 0 | 200µs / 8 | 500µs / 16 |
|---|---:|---:|---:|
| iteration | 216,476 | 196,211 | 189,882 |
| 평균 iteration/s | 240.53 | 218.01 (-9.4%) | 210.98 (-12.3%) |
| workload p95 | 11.06ms | 12.88ms (+16.5%) | 12.94ms (+17.0%) |
| workload p99 | 12.64ms | 14.20ms | 14.63ms |
| COMMIT 수 | 430,571 | 391,697 | 378,667 |
| COMMIT 평균 | 4.878ms | 5.661ms | 5.950ms |
| COMMIT p95 / p99 | 6.607 / 7.244ms | 8.318 / 9.120ms | 8.318 / 9.550ms |
| redo fsync 증가 | 620,837 | 550,803 | 504,912 |
| 행 잠금 증가 | 1,071건 / 2.10초 | 301건 / 0.86초 | 82건 / 0.13초 |
| 평균 write/s | 1,477.09 | 1,285.91 | 1,189.12 |
| 평균 write await | 0.911ms | 0.903ms | 0.917ms |
| 평균 / 최대 disk util | 69.52 / 88.40% | 61.69 / 74.80% | 60.35 / 75.20% |
| 평균 / 최대 CPU iowait | 23.28 / 35.05% | 25.16 / 32.65% | 23.76 / 30.70% |
| 실패율 | 0% | 0% | 0% |

### 단계별 HTTP 처리량과 지연

| VU | 0µs/0 RPS / p95 / CPU | 200µs/8 RPS / p95 / CPU | 500µs/16 RPS / p95 / CPU |
|---:|---:|---:|---:|
| 1 | 117 / 12.45ms / 30.7% | 121 / 9.38ms / 31.5% | 114 / 9.74ms / 31.0% |
| 3 | 255 / 10.75ms / 51.4% | 232 / 9.39ms / 48.4% | 222 / 9.82ms / 45.1% |
| 6 | 568 / 10.42ms / 73.8% | 513 / 10.26ms / 67.1% | 506 / 10.33ms / 62.8% |
| 8 | 754 / 10.78ms / 77.6% | 689 / 12.04ms / 70.9% | 661 / 11.38ms / 69.9% |
| 11 | 1,019 / 11.05ms / 83.0% | 894 / 12.87ms / 79.3% | 851 / 12.91ms / 76.6% |

낮은 VU의 p95는 후행 런에서 워밍 영향으로 짧았지만, 8~11 VU 처리량과 지연은 delay가
커질수록 일관되게 악화됐다. 실행 순서를 무작위화하지 않은 한계가 있어도 운영 후보에서
제외하기에는 방향과 크기가 충분히 명확하다. 기본 `0/0`을 유지한다.

- [0µs/0 summary](../../k6/results/20260821T082833Z-product-stock-mix-60256.summary.json)
- [200µs/8 summary](../../k6/results/20260821T084524Z-product-stock-mix-61491.summary.json)
- [500µs/16 summary](../../k6/results/20260821T090206Z-product-stock-mix-62730.summary.json)

세 런 종료 후 `0/0`, `flush=1`, `sync_binlog=1` 복구를 확인했다.

## 후속 실험: `reserve` SKU 일괄 조회

- 측정일: 2026-08-21 (AWS `ap-northeast-2`)
- 워크로드: 요청마다 서로 다른 SKU 10개를 reserve한 뒤 같은 10개를 release하는 write-only
  `1 → 3 → 6 → 8 → 11 VU`, 각 3분
- 공통 조건: Product 4대, MySQL 1대, 1,000 product/9,000 SKU, gp3 50 GiB
  3,000 IOPS/125 MiB/s, `flush=1`, `sync_binlog=1`, group commit `0/0`
- 비교 이미지: 변경 전 `bf1232b`와 일괄 조회 `8baa7bf`
- 실행 순서: 변경 전 → 변경 후, 같은 인프라를 연속 사용하고 각 런 직전에 예약 테이블,
  재고 수량, Performance Schema SQL digest를 초기화

`bf1232b` 이후 기준선 커밋 `0099cfc`까지 `product-service` diff는 없어서, 이미지가 존재하는
`bf1232b`를 변경 전 대조군으로 사용했다. 기존 write-only 부하는 예약당 item이 1개라
N+1 차이가 생기지 않으므로 `STOCK_ITEMS_PER_RESERVATION=10` 옵션을 추가하되 기본값 1은
유지했다.

### 결론

`reserve`의 반복 `findById`를 `findAllById` 한 번과 `Map<Long, ProductSku>` 조회로 바꾸자
SKU 조회가 iteration당 **20.0 → 11.0회(-45.0%)**로 줄었다. 변경 후 11회는 reserve의
`IN (...)` 1회와 아직 변경하지 않은 release의 단건 조회 10회다. SKU 조회 누적 시간도
iteration당 **2.564 → 1.516ms(-40.9%)**로 감소했다.

전체 처리량은 **80.38 → 86.54 iteration/s(+7.66%)**, HTTP 처리량은
**160.75 → 173.07 RPS(+7.66%)**로 증가했다. workload p95는
**39.78 → 36.85ms(-7.37%)**, p99는 **44.66 → 42.31ms(-5.26%)**로 감소했다.
따라서 다중 item 예약에서는 N+1 제거가 실제 처리량과 지연을 개선했다.

다만 최종 구간의 MySQL CPU는 변경 전/후 모두 약 99%였고 COMMIT 평균도
**4.712 → 4.735ms**로 변하지 않았다. 이 변경은 SKU 조회 CPU를 줄였지만 기존에 확인한
동기 COMMIT/refresh 비용까지 제거하지는 않으므로, 쓰기 상한 자체의 주 병목은 여전히 남아 있다.

### 전체 결과

| 항목 | 변경 전 | 일괄 조회 | 변화 |
|---|---:|---:|---:|
| iteration | 72,343 | 77,887 | +7.66% |
| 평균 iteration/s | 80.38 | 86.54 | +7.66% |
| 평균 HTTP RPS | 160.75 | 173.07 | +7.66% |
| workload p95 | 39.78ms | 36.85ms | -7.37% |
| workload p99 | 44.66ms | 42.31ms | -5.26% |
| workload/server/unexpected 4xx 실패율 | 0% | 0% | 동일 |
| SKU SELECT 수 | 1,446,574 | 856,604 | -40.78% |
| SKU SELECT/iteration | 19.996 | 10.998 | -45.00% |
| SKU SELECT 누적 시간 | 185.478초 | 118.070초 | -36.34% |
| SKU SELECT 시간/iteration | 2.564ms | 1.516ms | -40.87% |
| COMMIT 수 / 평균 | 144,662 / 4.712ms | 155,728 / 4.735ms | 평균 +0.49% |

변경 후 SQL digest는 release의 `WHERE id = ?`가 778,722회, reserve의
`WHERE id IN (...)`가 77,882회였다. 77,887 iterations와 수집 경계 오차 5회 이내로
일치한다. 변경 전 `WHERE id = ?`는 1,446,574회로 72,343 iterations의 20배와 일치했다.

| VU | 변경 전 RPS / p95 / MySQL CPU | 일괄 조회 RPS / p95 / MySQL CPU |
|---:|---:|---:|
| 1 | 42.17 / 34.55ms / 30.45% | 42.57 / 35.86ms / 28.90% |
| 3 | 70.15 / 25.55ms / 46.95% | 74.28 / 25.92ms / 46.35% |
| 6 | 160.59 / 23.92ms / 79.70% | 168.86 / 23.70ms / 79.04% |
| 8 | 230.58 / 28.63ms / 95.59% | 249.70 / 27.40ms / 95.16% |
| 11 | 255.24 / 35.08ms / 98.88% | 280.93 / 32.55ms / 98.63% |

변경 후 종료 집계는 예약 778,870건이 모두 `RELEASED`였고, 9,000개 SKU 재고의
최솟값/최댓값이 모두 100이라 전체 쓰기와 원복이 완료됐음을 확인했다.

### 결과 파일과 한계

- [변경 전 summary](../../k6/results/20260821T121314Z-product-stock-mix-12987.summary.json)
- [일괄 조회 summary](../../k6/results/20260821T123017Z-product-stock-mix-15774.summary.json)

```bash
env MYSQL_THRESHOLD_VERY_LOW_RAMP=true \
  STOCK_MIX_WORKLOAD=write \
  STOCK_MIX_DISTRIBUTION=uniform \
  STOCK_ITEMS_PER_RESERVATION=10 \
  REPO_REF=8baa7bf759949ff4c3d708467bb74d56a67e8d07 \
  PRODUCT_URL=http://<private-nlb>:8084 \
  ./k6/run-product-stock-mix-aws.sh
```

한 번의 순차 A/B이므로 워밍과 시간대 변동이 포함될 수 있다. 다만 SQL 형태와 iteration당
조회 감소는 구조적으로 검증됐고, 6~11 VU 구간에서 처리량/지연 방향도 일관됐다. 더 엄격한
효과 크기가 필요하면 새 스택마다 실행 순서를 교차한 `A→B→B→A` 중앙값을 사용한다.

## 재현

```bash
env MYSQL_THRESHOLD_VERY_LOW_RAMP=true \
  REPO_REF=6914746d621cb222079c0e08431e1afe16cd620d \
  PRODUCT_URL=http://<private-nlb>:8084 \
  PROM_URL=http://<prometheus>:9090/api/v1/write \
  ./k6/run-product-stock-mix-aws.sh
```

관련 구현 커밋: `17a6f01`(250→500), `fe00f9b`(100→200), `6914746`(10→100).
