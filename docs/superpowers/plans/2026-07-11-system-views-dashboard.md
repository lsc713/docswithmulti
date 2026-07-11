# 3-뷰 시스템 대시보드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inbound(k6 remote-write)·App(서버측)·Infra(node-exporter) 세 뷰를 행으로 구성한 신규 Grafana 대시보드를 추가해, 다음 실측에서 시스템을 한 화면으로 본다.

**Architecture:** 이미 스크레이프/수신 중인 신호만 재사용 — 코드·exporter·compose 변경 없음. 신규 대시보드 JSON 1개 + 런 절차 노트 1줄.

**Tech Stack:** Grafana provisioning(JSON) · Prometheus PromQL · k6 experimental-prometheus-rw

## Global Constraints

- 기존 `cancel-loadtest-overview.json`·`saturation-diagnosis.json` **수정 금지**(신규 파일만).
- Prometheus 데이터소스 uid는 `"prometheus"`. 대시보드는 provisioning provider가 `dashboards/` 폴더에서 자동 로드(provider.yml 확인).
- 새 exporter·앱 코드·compose 변경 없음. k6 remote-write는 런타임 플래그(`PROM=...`)이지 코드 아님.
- 패널 스타일/포맷은 기존 대시보드와 동일(timeseries, gridPos 2열, 타겟 데이터소스 `prometheus`).
- k6 지표명은 v0.54 remote-write 예상치 — 첫 런 검증 대상(패널 JSON만 조정).

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `infra/load-test/observability/grafana/dashboards/system-views.json` | 3-뷰 대시보드(13패널) | 신규 |
| `docs/load-test/saturation-diagnosis.md` | Inbound 행용 `PROM=` k6 실행 노트 | 수정(1줄 섹션) |

---

## Task 1: 3-뷰 대시보드 JSON

**Files:**
- Create: `infra/load-test/observability/grafana/dashboards/system-views.json`

**Interfaces:**
- Consumes(App/Infra, 상시 스크레이프): `http_server_requests_seconds_count`/`_bucket`(라벨 `service`/`status`), `node_cpu_seconds_total`/`node_memory_*`/`node_network_*`/`node_disk_*`(라벨 `host`/`device`, mode=idle). Consumes(Inbound, k6 remote-write): `k6_http_reqs_total`, `k6_vus`, `k6_http_req_failed_total`.

- [ ] **Step 1: 대시보드 JSON 생성**

`infra/load-test/observability/grafana/dashboards/system-views.json`:

```json
{
  "uid": "system-views",
  "title": "3-뷰 — Inbound·App·Infra",
  "tags": ["load-test", "system", "3-view"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "5s",
  "time": { "from": "now-15m", "to": "now" },
  "templating": { "list": [] },
  "annotations": { "list": [] },
  "panels": [
    {
      "id": 1,
      "type": "row",
      "title": "Inbound View — 클라이언트 부하 (k6 remote-write)",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 }
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "Inbound RPS (k6)",
      "description": "k6를 PROM=http://10.0.1.50:9090/api/v1/write 로 실행해야 채워짐. 지표명은 첫 런에서 확인.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 1 },
      "fieldConfig": { "defaults": { "unit": "reqps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum(rate(k6_http_reqs_total[1m]))",
          "legendFormat": "k6 rps"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "Active VUs (k6)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 1 },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "k6_vus",
          "legendFormat": "VUs"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "클라측 요청 실패율 (k6)",
      "description": "k6_http_req_failed_total 이름은 버전에 따라 다를 수 있음 — 첫 런 확인.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 1 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum(rate(k6_http_req_failed_total[1m])) / clamp_min(sum(rate(k6_http_reqs_total[1m])), 1)",
          "legendFormat": "fail ratio"
        }
      ]
    },
    {
      "id": 5,
      "type": "row",
      "title": "App View — 비즈니스 처리 (서버측)",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 }
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "TPS (service)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 10 },
      "fieldConfig": { "defaults": { "unit": "reqps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (service) (rate(http_server_requests_seconds_count[1m]))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 7,
      "type": "timeseries",
      "title": "성공률 (service)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 10 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "1 - (sum by (service)(rate(http_server_requests_seconds_count{status=~\"5..\"}[1m])) / clamp_min(sum by (service)(rate(http_server_requests_seconds_count[1m])), 1))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 8,
      "type": "timeseries",
      "title": "Latency p95 (service)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 18 },
      "fieldConfig": { "defaults": { "unit": "s" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.95, sum by (le, service) (rate(http_server_requests_seconds_bucket[1m])))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 9,
      "type": "timeseries",
      "title": "Latency p99 (service)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 18 },
      "fieldConfig": { "defaults": { "unit": "s" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.99, sum by (le, service) (rate(http_server_requests_seconds_bucket[1m])))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 10,
      "type": "row",
      "title": "Infra View — 자원 소모 (node-exporter)",
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 26 }
    },
    {
      "id": 11,
      "type": "timeseries",
      "title": "CPU 사용률 (host)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 27 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "1 - avg by (host) (rate(node_cpu_seconds_total{mode=\"idle\"}[1m]))",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 12,
      "type": "timeseries",
      "title": "Memory 사용률 (host)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 27 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 13,
      "type": "timeseries",
      "title": "Network 수신 (host)",
      "description": "device 필터는 AL2023 Graviton 기본 ens5. 인터페이스명이 다르면 첫 런에서 조정.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 35 },
      "fieldConfig": { "defaults": { "unit": "bps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (host) (rate(node_network_receive_bytes_total{device=~\"ens5|eth0\"}[1m])) * 8",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 14,
      "type": "timeseries",
      "title": "Network 송신 (host)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 35 },
      "fieldConfig": { "defaults": { "unit": "bps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (host) (rate(node_network_transmit_bytes_total{device=~\"ens5|eth0\"}[1m])) * 8",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 15,
      "type": "timeseries",
      "title": "Disk 읽기 (host)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 43 },
      "fieldConfig": { "defaults": { "unit": "Bps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (host) (rate(node_disk_read_bytes_total[1m]))",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 16,
      "type": "timeseries",
      "title": "Disk 쓰기 (host)",
      "description": "TX3 커밋(fsync) 부하가 여기 나타남 — 이력배치 #1 확증의 핵심 신호.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 43 },
      "fieldConfig": { "defaults": { "unit": "Bps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (host) (rate(node_disk_written_bytes_total[1m]))",
          "legendFormat": "{{host}}"
        }
      ]
    }
  ]
}
```

- [ ] **Step 2: JSON 유효성 검증**

Run:
```bash
python3 -m json.tool infra/load-test/observability/grafana/dashboards/system-views.json > /dev/null && echo "JSON OK"
```
Expected: `JSON OK`.

- [ ] **Step 3: 속성/패널/데이터소스 검증**

Run:
```bash
python3 -c "import json;d=json.load(open('infra/load-test/observability/grafana/dashboards/system-views.json'));ts=[p for p in d['panels'] if p['type']=='timeseries'];rows=[p for p in d['panels'] if p['type']=='row'];print('uid',d['uid']);print('timeseries',len(ts),'rows',len(rows));assert d['uid']=='system-views';assert len(ts)==13;assert len(rows)==3;assert all(t['datasource']['uid']=='prometheus' for p in ts for t in p['targets'])"
```
Expected: `uid system-views` / `timeseries 13 rows 3`, assert 통과.

- [ ] **Step 4: 기존 대시보드 불변 확인**

Run: `git status --porcelain infra/load-test/observability/grafana/dashboards/cancel-loadtest-overview.json infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json`
Expected: 출력 없음(둘 다 미변경).

- [ ] **Step 5: 커밋**

```bash
git add infra/load-test/observability/grafana/dashboards/system-views.json
git commit -m "feat(obs): 3-뷰 시스템 대시보드(Inbound·App·Infra) 신규 추가"
```

---

## Task 2: Inbound 행 실행 노트

**Files:**
- Modify: `docs/load-test/saturation-diagnosis.md`

**Interfaces:**
- Consumes: Task 1의 대시보드(uid `system-views`)를 어떻게 채우는지 운영 노트로 연결.

- [ ] **Step 1: 실행 노트 추가**

`docs/load-test/saturation-diagnosis.md`의 `## 교차 확인 (Tempo)` 섹션 **바로 앞**에 아래 섹션을 삽입:

```markdown
## 3-뷰 대시보드 (Inbound·App·Infra)

`system-views` 대시보드는 부하(Inbound)↔처리(App)↔자원(Infra)을 한 화면에서 본다.
- **App·Infra 행**은 상시 스크레이프라 배포 즉시 그려진다(CPU/Memory/Network/Disk, TPS/지연/성공률).
- **Inbound 행(k6)** 을 채우려면 k6를 remote-write로 실행한다:
  ```bash
  PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh
  ```
- 첫 런 스모크: Prometheus `/api/v1/label/__name__/values` 로 `k6_*` 실제 지표명과 node_network `device` 명을 확인하고, 다르면 `system-views.json` 패널 expr을 조정한다.
```

- [ ] **Step 2: 삽입 검증**

Run:
```bash
grep -c "3-뷰 대시보드\|PROM=http://10.0.1.50:9090/api/v1/write\|system-views" docs/load-test/saturation-diagnosis.md
```
Expected: 매치 카운트 ≥ 3.

- [ ] **Step 3: 커밋**

```bash
git add docs/load-test/saturation-diagnosis.md
git commit -m "docs(load-test): 3-뷰 대시보드 Inbound 행 k6 remote-write 실행 노트"
```

---

## Self-Review

- **Spec coverage:** Inbound 3패널(RPS/VUs/실패율, T1) ✓ / App 4패널(TPS/성공률/p95/p99, T1) ✓ / Infra 6패널(CPU/Mem/Net rx·tx/Disk r·w, T1) ✓ / 신규 파일·overview·saturation 불변(T1 Step4) ✓ / PROM 실행 노트(T2) ✓ / k6·device 지표명 첫 런 검증(패널 description + T2 노트) ✓.
- **Placeholder scan:** JSON·명령·문서 전문 포함, TBD 없음. k6 지표명 불확실성은 검증 노트로 명시(placeholder 아님).
- **Type consistency:** 패널 수 13 timeseries + 3 row(spec의 3+4+6=13 일치). 지표명 `http_server_requests_seconds_count`/`_bucket`·`node_cpu/memory/network/disk_*`·`k6_http_reqs_total`/`k6_vus`/`k6_http_req_failed_total`·라벨 `service`/`host`/`device`/`status` 전 패널 일관. uid `system-views`, 데이터소스 `prometheus` 일관.
