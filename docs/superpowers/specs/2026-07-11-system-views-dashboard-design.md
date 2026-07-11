# 3-뷰 시스템 대시보드 (Inbound·App·Infra) 설계

- 날짜: 2026-07-11
- 상태: 설계 확정
- 관련: 2026-07-11 AWS 실측(포화 진단), `infra/load-test/observability/`, 메모리 `loadtest-aws-run`

## 배경 / 문제

실측 관측을 세 뷰로 나눠 보면 지금 Grafana 커버리지가 고르지 않다:

| 뷰 | 지표 | 데이터 스크레이프 | 패널 |
|---|---|---|---|
| **Inbound** (클라 부하 RPS) | k6 client | remote-write 수신은 켜져 있으나 미사용 | ❌ 없음 |
| **App** (TPS·지연·성공률) | `http_server_requests_*` | ✅ | ✅ overview/saturation |
| **Infra** CPU | `node_cpu_*` | ✅ | ✅ saturation |
| **Infra** Memory/Network/Disk | `node_memory/network/disk_*` | ✅ (node-exporter 전 호스트) | ❌ 없음 |

즉 **데이터는 다 있는데 Inbound와 Infra의 Memory/Network/Disk는 패널이 없어** Grafana에서 바로 안 보인다. Prometheus는 이미 `--web.enable-remote-write-receiver`가 켜져 있어(관측 compose 확인) k6 remote-write 수신이 준비돼 있다.

## 목표

세 뷰(Inbound/App/Infra)를 행으로 구성한 **신규 대시보드**를 만들어, 다음 실측(특히 이력배치 #1 OTel 확증 런)에서 시스템을 한 화면으로 본다. 코드 변경 없음 — 대시보드 JSON + 런 절차 노트뿐.

## 비목표 (YAGNI)

- 기존 `cancel-loadtest-overview.json`·`saturation-diagnosis.json` **수정 금지**(목적 분리, 신규 파일).
- 새 exporter 추가 없음(node-exporter가 mem/net/disk 이미 노출).
- 앱 코드·compose 변경 없음. k6를 remote-write로 실행하는 것은 **런타임 플래그**(`PROM=...`)이지 코드 아님.

## 설계

### 산출물

`infra/load-test/observability/grafana/dashboards/system-views.json` (provisioning provider 자동 로드). uid `system-views`, 제목 "3-뷰 — Inbound·App·Infra". 스타일은 기존 대시보드와 동일(Prometheus 데이터소스 uid `prometheus`, timeseries 패널, gridPos 2열).

### 행 1 — Inbound View (k6 remote-write)

k6를 `-o experimental-prometheus-rw`로 실행할 때만 채워진다(6h 보존).

| 패널 | expr | 단위 |
|---|---|---|
| Inbound RPS (k6) | `sum(rate(k6_http_reqs_total[1m]))` | reqps |
| Active VUs (k6) | `k6_vus` | short |
| 클라측 요청 실패율 (k6) | `sum(rate(k6_http_req_failed_total[1m])) / clamp_min(sum(rate(k6_http_reqs_total[1m])),1)` | percentunit |

**k6 지표 이름 주의:** 위는 k6 v0.54 remote-write 기준 예상 이름이다. k6 trend/counter의 정확한 export 이름은 버전·`K6_PROMETHEUS_RW_TREND_STATS` 설정에 따라 다를 수 있다. **첫 런에서 Prometheus `/api/v1/label/__name__/values`로 `k6_*` 실제 이름을 확인하고 필요 시 이 3개 expr을 조정**한다(패널 JSON만 수정, 저비용). RPS(`k6_http_reqs_total`)·VUs(`k6_vus`)는 이름이 안정적이고, 실패율 메트릭명만 특히 확인 대상.

### 행 2 — App View (서버측, 상시 스크레이프)

| 패널 | expr | 단위 |
|---|---|---|
| TPS (service) | `sum by (service) (rate(http_server_requests_seconds_count[1m]))` | reqps |
| Latency p95 (service) | `histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket[1m])))` | s |
| Latency p99 (service) | `histogram_quantile(0.99, sum by (le, service) (rate(http_server_requests_seconds_bucket[1m])))` | s |
| 성공률 (service) | `1 - (sum by (service)(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) / clamp_min(sum by (service)(rate(http_server_requests_seconds_count[1m])),1))` | percentunit |

### 행 3 — Infra View (node-exporter, 상시)

`host` 라벨은 Prometheus node job이 타겟별로 부여(prometheus.yml 확인). 전 호스트가 시리즈로 표시.

| 패널 | expr | 단위 |
|---|---|---|
| CPU 사용률 (host) | `1 - avg by (host) (rate(node_cpu_seconds_total{mode="idle"}[1m]))` | percentunit |
| Memory 사용률 (host) | `1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes` | percentunit |
| Network 수신 (host) | `sum by (host) (rate(node_network_receive_bytes_total{device=~"ens5\|eth0"}[1m])) * 8` | bps |
| Network 송신 (host) | `sum by (host) (rate(node_network_transmit_bytes_total{device=~"ens5\|eth0"}[1m])) * 8` | bps |
| Disk 읽기 (host) | `sum by (host) (rate(node_disk_read_bytes_total[1m]))` | Bps |
| Disk 쓰기 (host) | `sum by (host) (rate(node_disk_written_bytes_total[1m]))` | Bps |

- Network는 bytes/s에 `*8`로 bits/s(단위 `bps` → Grafana가 Mbps로 자동 스케일). NIC 필터 `device=~"ens5|eth0"`(AL2023 Graviton 기본 ens5; docker/veth 가상 인터페이스 제외). 호스트 인터페이스명이 다르면 첫 런에서 조정.
- Disk는 bytes/s(단위 `Bps` → MB/s 자동 스케일). 디바이스 합산(호스트 총 처리량).

### 런 절차 노트

`docs/load-test/saturation-diagnosis.md`(또는 measurement-journey 런북)에 한 줄 추가: **3-뷰의 Inbound 행을 채우려면 k6를 `PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh`로 실행**한다(remote-write). App/Infra 행은 상시 스크레이프라 PROM 없이도 나온다.

## 검증 전략

- **JSON 유효성:** `python3 -m json.tool system-views.json` 파싱 성공.
- **속성/패널:** uid `system-views`, 모든 타겟 데이터소스 `prometheus`, 패널 수 확인(3+4+6=13).
- **PromQL 문법:** 각 expr 유효(라벨/함수). node 지표명·`host`/`device` 라벨은 실측 스택 기준.
- **overview/saturation 불변:** git status로 두 파일 미변경 확인.
- **실제 곡선:** 다음 AWS 런에서 — App/Infra는 배포 즉시, Inbound는 `PROM=` k6 런 중. 첫 런 스모크에 "k6_* 지표명 확인 + Network device명 확인" 추가.

## 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `infra/load-test/observability/grafana/dashboards/system-views.json` | 신규 3-뷰 대시보드(13패널) |
| `docs/load-test/saturation-diagnosis.md` | Inbound 행용 `PROM=` k6 실행 노트 1줄 |

## 미해결 / 후속

- k6 remote-write 지표 정확명은 첫 런 검증 후 확정(패널 미세조정).
- 이 대시보드는 #1(이력배치) OTel 확증 런의 관측 기반이 됨 — 확증 런에서 Inbound(부하)↔App(TPS/지연)↔Infra(CPU/커밋 유발 Disk I/O) 상관을 한 화면으로 본다.
