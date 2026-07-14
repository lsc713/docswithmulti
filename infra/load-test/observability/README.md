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

## oncall 알림 (Grafana → Slack → oncall 스킬)

부하 실측 중 임계 초과 시 Grafana 통합 알림이 **Slack 채널로 firing 알림을 발송**하고,
그 채널을 `oncall-triage`/`oncall-pr`/`oncall-log` 스킬이 읽어 진단·PR·기록한다.
(스킬은 알림 채널을 **읽기만** 한다 — 발송 배선이 여기다.)

**프로비저닝 파일** (`grafana/provisioning/alerting/`):

| 파일 | 역할 |
|------|------|
| `contactpoints.yml` | Slack contact point(봇 토큰). payload 를 **plain-text** 로 렌더(Slack MCP 가 blocks 를 못 읽는 문제 회피) |
| `policies.yml` | 루트 라우팅 → `slack-oncall`, group by service·alertname |
| `rules.yml` | 알림 룰 5종(Grafana-managed, datasource uid=`prometheus`) |

**알림 룰 → 감별표 매핑** (룰은 정식 `service` 라벨을 부여해 스킬의 `match.services` 필터 통과):

| 룰 | 메트릭(상시 노출) | service | 감별표 |
|----|------------------|---------|--------|
| HighCancelLatency | `http_server_requests` p95 | payment-service | Family A |
| HikariConnectionTimeouts | `hikaricp_connections_timeout_total` | payment-service | A 풀고갈(§B B1 반증 주의) |
| HighErrorRatio | risk 5xx 비율 | risk-management-service | **B2** 락 fail-fast |
| KafkaConsumerLag | `kafka_consumergroup_lag` | order-service | **B3** 폴러/컨슈머 |
| PaymentDbCpuSaturation | node cpu(`host=mysql-payment`) | payment-service | **B1** 지배 병목 |

**시크릿(.env — gitignore, 커밋 안 됨).** repo 루트 `.env` 에:
```
SLACK_BOT_TOKEN=xoxb-...        # chat:write 스코프 + 봇이 채널에 초대돼 있어야 함
ONCALL_SLACK_CHANNEL=C0BAXAEDL5R
```
Grafana 는 이 두 값을 프로비저닝 시점에 `${...}` 로 확장한다(하드코딩 없음). 값이 비면 스택은
정상이고 **전송만** 실패한다.

> 임계값은 실측 무릎(~220 rps, `capacity-planning.md`) 근처 튜닝 대상 — 현재는 데모용 보수값.
> 알림은 **스택 + 앱이 부하로 도는 동안에만** 발화한다(관측 스택은 상시 가동 아님).

## 기동 순서

**1. 각 호스트(9대)에서 node-exporter** — SSM 접속 후:
```bash
docker compose -f node-exporter.compose.yml up -d
```

**2. obs 인스턴스(10.0.1.50)에서 전체 스택** — 루트 `.env` 를 명시해 Slack 시크릿을 주입:
```bash
# .env 는 repo 루트에 있고 compose 는 이 디렉터리에서 뜨므로 --env-file 로 지정
docker compose --env-file /Users/juho/Documents/docswithmulti/.env up -d
```
> `--env-file` 없이 `docker compose up -d` 하면 이 디렉터리의 `.env` 를 찾으므로 Grafana 에
> Slack 값이 안 들어간다(알림 전송만 실패). 스택은 어느 쪽이든 뜬다.

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
