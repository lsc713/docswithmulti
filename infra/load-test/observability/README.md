# 관측 스택 (Prometheus + Grafana + exporters)

부하 실측 지표를 계층별로 수집한다. 근거: [`../../../docs/load-test/measurement-journey.md` §1](../../../docs/load-test/measurement-journey.md).

## 빠른 확인 (노트북 브라우저 — 퍼블릭 IP 불필요)

관측 스택은 `deploy/ssm-deploy.sh` 가 **자동 배포**(node-exporter 전 호스트 + obs 스택). 확인은 SSM 포트포워딩으로:

```bash
./infra/load-test/deploy/port-forward.sh grafana     # → http://localhost:3000  대시보드
./infra/load-test/deploy/port-forward.sh kafka       # → http://localhost:8989  consumer lag
./infra/load-test/deploy/port-forward.sh prometheus  # → http://localhost:9090  scrape Targets
./infra/load-test/deploy/port-forward.sh payment     # → localhost:8080/actuator/health
```

- Grafana 대시보드 **Cancel Load Test — Overview** 자동 프로비저닝(에러율/지연/HikariCP/row_lock/GC/lag).
- 부하 걸리는 동안 이 대시보드만 보면 됨 — SSM 로그를 CLI로 캘 필요 없음.
- 전제: 로컬에 `session-manager-plugin` 설치.

## 로그 검색 (CloudWatch Logs Insights)

`LOG_CLOUDWATCH=1` 로 배포하면 앱 로그가 `/loadtest/apps` 로그그룹으로 감. 콘솔 ▸ CloudWatch ▸ Logs Insights 에서 GUI 쿼리:

```sql
-- 에러코드별 카운트 (RISK_SERVICE_UNAVAILABLE 같은 걸 한눈에)
fields @message
| filter @message like /Exception|ERROR/
| parse @message /code=(?<code>[A-Z_]+)/
| stats count(*) as n by code | sort n desc
```
```sql
-- 데드락/락 이벤트 타임라인
fields @timestamp, @logStream, @message
| filter @message like /Deadlock|LockAcquisition|could not/
| sort @timestamp desc
```

## 무엇을 수집하나

| 소스 | 방식 | 지표 |
|------|------|------|
| 앱 4종 | Micrometer `/actuator/prometheus` (Prometheus pull) | HTTP p95/p99, TPS, 에러율, **HikariCP pending/active**, JVM/GC, Tomcat, 서킷브레이커(risk) |
| 각 호스트 | node-exporter (:9100) | CPU/mem/disk/net |
| MySQL 4종 | mysqld-exporter | **innodb_row_lock_waits**, lock time, deadlock, threads, buffer pool |
| Redis | redis-exporter | **hit ratio**, 메모리, 연결 |
| Kafka | kafka-exporter | **consumer lag** |
| k6 | Prometheus remote-write | 클라이언트측 p95/p99, 에러 |

## 기동 순서

**1. 각 호스트(9대)에서 node-exporter** — SSM 접속 후:
```bash
docker compose -f node-exporter.compose.yml up -d
```

**2. obs 인스턴스(10.0.1.50)에서 전체 스택**:
```bash
docker compose up -d
```

- Grafana: `http://10.0.1.50:3000` (admin/admin, 익명 Viewer 허용)
  - 대시보드: **Load Test / Cancel Load Test — Overview** 자동 프로비저닝
- Prometheus: `http://10.0.1.50:9090` → Status ▸ Targets 로 scrape 상태 확인

**3. k6 결과를 Grafana로** — k6 호스트(10.0.1.10)에서:
```bash
K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
  k6 run -o experimental-prometheus-rw load-test.js
```

## 전제 (배포 시 맞춰야 함 — task #2)

고정 사설 IP + 포트 규약. 앱이 다른 호스트를 바라보도록 **env 오버라이드** 필요(로컬 yml은 localhost 기준):

| 대상 | 주소 |
|------|------|
| mysql-payment / risk | `10.0.1.30:3306` / `10.0.1.31:3306` |
| mysql-merchant / order | `10.0.1.32:3306` / `10.0.1.32:3307` (한 호스트 두 컨테이너) |
| Redis / Kafka | `10.0.1.40:6379` / `10.0.1.40:9092` |

- mysqld-exporter는 test 편의상 `root/root` 사용. Kafka는 **advertised.listeners를 사설 IP로** 설정해야 exporter가 붙는다.

## 더 깊은 대시보드 (수동 import)

기본 대시보드는 핵심 지표만. 심화 분석은 Grafana ▸ Import 로 커뮤니티 대시보드 ID 사용:

| ID | 대상 |
|----|------|
| 4701 | JVM (Micrometer) |
| 7362 | MySQL Overview |
| 1860 | Node Exporter Full |
| 763 | Redis |
| 7589 | Kafka Exporter |
