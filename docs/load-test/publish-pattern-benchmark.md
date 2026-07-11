# Kafka 발행 패턴 실측 런북 (Publish-Pattern Benchmark)

> 생성일: 2026-07-12
> 목적: `payment.cancelled` 이벤트 발행 방식 3가지(INLINE / INLINE_ASYNC / OUTBOX)를 동일 AWS 리그에서 실측 비교한다. 처리량·커넥션 점유·e2e 발행 지연·Kafka 장애 거동·발행 버스트를 한 판에 드러낸다.
> 관련: [`measurement-journey.md`](./measurement-journey.md) (인프라 기동·VU 스윕·기록 템플릿), [`saturation-diagnosis.md`](./saturation-diagnosis.md) (USE 판정·Hikari 진단)
> 대시보드: `publish-pattern-comparison` (Grafana, uid `publish-pattern-comparison`)

---

## 0. 배경 및 실험 설계

현행 `payment.cancelled`는 TX3 안에서 `kafkaTemplate.send().get(5s)` **동기 인라인** 발행이다(dual-write, 실패 시 TX3 롤백 → processing-recovery). `send().get()`은 브로커 왕복 시간(RTT)을 **TX3 커넥션 점유 시간에 더한다** — 앞선 실측(커밋 6→4, rps 147→220)에서 TX3 내 DB 작업 수가 점유를 지배함을 증명했다. 이 실험은 그 연장선: Kafka RTT 제거 시 이득이 얼마인지, 그리고 발행 분리가 지연·장애·버스트에서 어떤 트레이드오프를 가져오는지 정량화한다.

### 모드 요약

| 모드 | 동작 | 안전성 | 측정 역할 |
|------|------|--------|----------|
| `INLINE` (기본, 현행) | TX3 안 `send().get(5s)`. 실패 시 롤백 | dual-write 안전 | **기준선** |
| `INLINE_ASYNC` | TX3 안 `send()` fire-and-forget, `.get()` 없음 | **안전하지 않음** — 측정 전용 | 처리량 **상한** 레퍼런스 |
| `OUTBOX` | TX3가 `cancel_event_outbox` INSERT + 폴러 발행 | dual-write 완전 해소 | 운영 후보 |

> `INLINE_ASYNC` 활성 시 기동 로그에 WARN("측정 전용, 프로덕션 사용 금지") 출력 — 프로덕션에 절대 사용하지 않는다.

---

## 1. 공정 비교 토글

`measurement-journey.md` §10 white-box 관측과 동일한 원칙: **한 번에 한 변수만** 바꾼다. 발행 모드가 이 실험의 유일한 독립변수다.

```bash
# 각 모드 재배포 시 아래 env 블록을 **통째로 고정**한다 (노이즈 제거)
SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true \   # Tomcat JMX 메트릭 ON (포화 진단과 동일)
OTEL_JAVAAGENT= \                            # 트레이싱 OFF (오버헤드 제거)
LOADTEST_QUERYCOUNT_ENABLED=false \          # 쿼리카운트 OFF (datasource-proxy 제거)
CANCEL_PUBLISH_MODE=<모드> \                  # INLINE | INLINE_ASYNC | OUTBOX
CANCEL_OUTBOX_POLL_MS=10000 \               # OUTBOX 기본 폴 주기 10s
CANCEL_OUTBOX_BATCH_SIZE=1000               # OUTBOX 배치 크기 기본값
```

**고정 조건**: DB `m7g.large` · 시드 100k · VU 스윕 50→400 (P1), 목표 rps 구간 유지 (P2).

---

## 2. 런타임 지표명 사전 확인 체크리스트

대시보드 패널이 **실제 지표명**을 참조하는지 첫 런 시작 전에 반드시 확인한다. Micrometer 지표명은 환경(JVM, 브로커 연결 여부)에 따라 등록 시점이 다르다.

```bash
# payment-service SSM 포워드 후 (혹은 내부망 curl)
# 1) e2e 지연 (order-service)
curl -s http://<order-svc-ip>:8081/actuator/prometheus | grep cancel_event_e2e_latency
# → 출력 예: cancel_event_e2e_latency_seconds_bucket{...}
# 없으면: 컨슈머가 아직 메시지 0건 → smoke 1건 취소 후 재확인

# 2) Consumer lag (order-service)
curl -s http://<order-svc-ip>:8081/actuator/prometheus | grep kafka_consumer_fetch_manager_records_lag
# → 있으면 그 이름을 대시보드 Row 3 expr 에서 그대로 사용
# 없으면: KafkaClientMetrics 바인딩 확인 (MicrometerConsumerListener 등록 여부)

# 3) Produce rate (payment-service)
curl -s http://<payment-svc-ip>:8080/actuator/prometheus | grep kafka_producer_record_send_total
# → 있으면 Row 4 expr 신뢰 가능

# 4) e2e 지연 버킷 확인 (percentiles-histogram 활성 여부)
curl -s http://<order-svc-ip>:8081/actuator/prometheus | grep cancel_event_e2e_latency_seconds_bucket | head -5
# _bucket 이 있어야 histogram_quantile 계산 가능
```

> 지표명이 다르면 `publish-pattern-comparison.json` 패널 expr을 실제 이름으로 수정하고 재임포트한다. 대시보드 패널 expr 형식: `histogram_quantile(0.95, sum(rate(cancel_event_e2e_latency_seconds_bucket[1m])) by (le))`.

---

## 3. 인프라 기동 및 배포

`measurement-journey.md` §7 배포 절차를 그대로 따른다. 발행 패턴 실측에서 달라지는 점만 아래에 정리한다.

### 3-1. 이미지 준비 (코드가 바뀐 경우만)

```bash
gh workflow run loadtest-images.yml
# payment-service 이미지에 CANCEL_PUBLISH_MODE 지원 코드(INLINE/INLINE_ASYNC/OUTBOX)가 포함돼야 한다
# order-service 이미지에 cancel_event_e2e_latency 계측이 포함돼야 한다
```

### 3-2. 인프라 기동

```bash
cd infra/load-test && terraform apply
terraform output ssm_connect    # SSM 접속 정보
terraform output private_ips    # 서비스별 내부 IP
```

### 3-3. 모드별 배포 절차

각 모드 측정은 `payment.compose.yml`에서 `CANCEL_PUBLISH_MODE`를 바꾸고 `--force-recreate`로 재기동한다.

```bash
# INLINE (기준선)
CANCEL_PUBLISH_MODE=INLINE \
SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true \
OTEL_JAVAAGENT= \
LOADTEST_QUERYCOUNT_ENABLED=false \
IMAGE_NS=<dockerhub-user> \
  ./infra/load-test/deploy/ssm-deploy.sh

# 또는 SSM send-command로 payment 노드만 재기동
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=payment" \
  --parameters 'commands=["CANCEL_PUBLISH_MODE=INLINE SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true OTEL_JAVAAGENT= LOADTEST_QUERYCOUNT_ENABLED=false docker compose -f /opt/payment.compose.yml up -d --force-recreate"]'

# INLINE_ASYNC (상한 레퍼런스)
# CANCEL_PUBLISH_MODE=INLINE_ASYNC 로 교체, 나머지 동일

# OUTBOX
# CANCEL_PUBLISH_MODE=OUTBOX 로 교체, 나머지 동일
# CANCEL_OUTBOX_POLL_MS=10000 (기본), CANCEL_OUTBOX_BATCH_SIZE=1000 (기본)
```

> 재기동 후 `docker logs payment-service 2>&1 | tail -20`으로 기동 확인. `INLINE_ASYNC` 모드는 WARN 로그를 확인한다.

### 3-4. 관측 열기

```bash
./infra/load-test/deploy/port-forward.sh grafana
# → http://localhost:3000 → 대시보드 "publish-pattern-comparison"
```

---

## 4. P1 — 처리량/점유 실측 (3모드 런)

### 목적

브로커 RTT를 TX3 밖으로 빼면 Hikari 커넥션 점유가 줄어 rps가 오르는지, 그 델타가 얼마인지 정량화한다.

### 가설

- `OUTBOX ≥ INLINE` (~수%): OUTBOX가 TX3에서 `send().get()` RTT를 제거 → 커넥션 점유 감소 → 소폭 처리량 향상. 브로커가 같은 VPC 내라 RTT가 작으므로 델타는 **커밋 6→4(×1.5)가 아닌 수% 수준**.
- `INLINE_ASYNC ≥ OUTBOX`: fire-and-forget으로 `.get()` 블록 자체 없음 → 처리량 상한.
- **커밋 수 불변**: OUTBOX INSERT는 TX3 같은 커밋에 얹힘 → general_log 커밋 수 변화 없음(fsync 추가 없음).

### 실행

```bash
# 워밍업 (모드 바꿀 때마다)
VUS=1 DURATION=60s ./k6/run-stage.sh   # S0 smoke — 정합성 + JIT warmup

# 스트레스 스윕
for VUS in 50 100 200 400; do
  VUS=$VUS DURATION=3m PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh
done
```

### 캡처 항목 (모드당)

1. **취소 rps** — k6 `http_reqs` rate (Grafana Row 1 "취소 RPS")
2. **p95 레이턴시** — k6 `http_req_duration` p95 (Grafana Row 1 "취소 p95")
3. **Hikari active/pending** — `hikaricp_connections_active/pending{application="payment-service"}` (Grafana Row 1)
4. **general_log 커밋 수** — MySQL general_log에서 `COMMIT` 카운트 (모드 간 불변 확인)

```bash
# general_log 커밋 수 비교 (payment-service DB 노드)
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=mysql-payment" \
  --parameters 'commands=["mysql -uroot -p<pw> -e \"SELECT COUNT(*) FROM mysql.general_log WHERE argument=\\'COMMIT\\' AND event_time > NOW() - INTERVAL 5 MINUTE\""]'
```

### 결과 기록 템플릿

```
### P1 결과 — INLINE / INLINE_ASYNC / OUTBOX  (날짜/시각)
- 공통 구성: VU=200, DB=m7g.large, seed=100k, OTel=OFF, querycount=OFF, tomcat=ON

| 모드         | rps | p95(ms) | Hikari active | Hikari pending | 커밋 수(5분) |
|--------------|-----|---------|---------------|----------------|-------------|
| INLINE       |     |         |               |                |             |
| INLINE_ASYNC |     |         |               |                |             |
| OUTBOX       |     |         |               |                |             |

- 가설 검증: outbox≥inline? __ / inline_async≥outbox? __ / 커밋 수 불변? __
- 실제 델타(inline→outbox): __%
- 소견:
```

---

## 5. P2 — 지연 / 장애 / 버스트

### 5-1. e2e 발행→소비 지연

**목적**: 발행 방식에 따라 order-service가 이벤트를 받기까지 지연이 얼마나 다른지 확인한다.

**가설**: INLINE은 TX3 커밋 직후 발행이므로 **~수십 ms** (네트워크+소비). OUTBOX는 폴러 주기에 묶여 **최대 폴 주기(기본 10s)** 지연. INLINE_ASYNC는 fire-and-forget이므로 INLINE과 비슷하나 실패 감지 없음.

```bash
# OUTBOX 모드로 부하 중 Grafana Row 2 확인
# panel: "e2e 발행→소비 지연 p50/p95 (order-service)"
# expr: histogram_quantile(0.5, sum(rate(cancel_event_e2e_latency_seconds_bucket[1m])) by (le))

# 폴 노브 재런: 지연 ↓ / 빈폴링 부하 ↑ 트레이드 확인
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=payment" \
  --parameters 'commands=["CANCEL_PUBLISH_MODE=OUTBOX CANCEL_OUTBOX_POLL_MS=1000 SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true OTEL_JAVAAGENT= LOADTEST_QUERYCOUNT_ENABLED=false docker compose -f /opt/payment.compose.yml up -d --force-recreate"]'

# 동일 VU로 재런 후 Row 2 p50/p95 비교
```

**기록 포인트**:
- INLINE p50/p95 (ms)
- OUTBOX(10s 폴) p50/p95 (초)
- OUTBOX(1s 폴) p50/p95 (초) — 지연 감소 확인
- 1s 폴 시 payment-service CPU / Kafka 연결 수 변화 (부하 상승 여부)

### 5-2. 장애 주입 — Kafka 브로커 docker stop

**목적**: 브로커 중단 시 INLINE은 취소 자체가 실패하고 OUTBOX는 취소가 성공하되 이벤트 배달이 지연되는지 확인한다.

**절차**:

1. **런 중 브로커 중단** (부하가 안정적으로 돌고 있는 상태에서)

```bash
# infra 노드에서 Kafka 컨테이너 이름 확인
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=infra" \
  --parameters 'commands=["docker ps --format \"{{.Names}}\" | grep -i kafka"]'
# 출력 예: kafka-1  (이름을 메모)

# 브로커 중단
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=infra" \
  --parameters 'commands=["docker stop kafka-1"]'
```

2. **관찰 (Grafana Row 5 "장애 주입 시 취소 성공률")**:
   - `INLINE`: `send().get(5s)` 타임아웃 → TX3 롤백 → **취소 성공률 급락** (k6 에러율 상승)
   - `OUTBOX`: TX3 내 `cancel_event_outbox` INSERT만 → 커밋 성공 → **취소는 계속 성공**, 발행만 큐잉
   - Grafana Row 3 Consumer Lag: OUTBOX는 lag 누적, INLINE은 lag 없음(발행도 실패했으니)

3. **브로커 복구**

```bash
aws ssm send-command \
  --document-name "AWS-RunShellScript" \
  --targets "Key=tag:role,Values=infra" \
  --parameters 'commands=["docker start kafka-1"]'
```

4. **복구 후 관찰**:
   - OUTBOX: 폴러가 PENDING 행을 배치 발행 → Grafana Row 3 lag 급감 + Row 4 produce rate 봉우리
   - INLINE: 정상 복구 (단, 중단 구간 취소 건은 processing-recovery가 재처리)

```
### P2-장애 결과 기록 (날짜/시각)
- 브로커 중단 시간: __분
- INLINE 취소 성공률 변화: _% → _%
- OUTBOX 취소 성공률 변화: _% → _% (기대: 유지)
- OUTBOX 브로커 복구 후 백로그 배수 시간: __초
- Consumer lag 최대치 (OUTBOX): __ 건
```

### 5-3. 버스트 — Produce Rate 시간축

**목적**: OUTBOX 폴러가 배치 발행하는 봉우리 vs INLINE의 매끄러운 곡선을 시각화한다.

- Grafana Row 4 "Kafka Produce Rate — payment.cancelled":
  - INLINE: 취소 rps와 거의 동일한 평탄 곡선
  - OUTBOX: 폴 주기(10s or 1s)마다 **배치 배수 봉우리** (`CANCEL_OUTBOX_BATCH_SIZE=1000` 한계)
- 봉우리가 브로커 부하를 유발하는지 `kafka_producer_record_send_total` rate로 확인

```bash
# Produce Rate 지표 확인
curl -s http://<payment-svc-ip>:8080/actuator/prometheus | grep kafka_producer_record_send_total
```

---

## 6. 대시보드 육안 체크리스트

`publish-pattern-comparison` 대시보드 5행을 순서대로 확인한다.

| Row | 패널 | 확인 항목 | 이상 신호 |
|-----|------|-----------|-----------|
| **Row 1** 취소 처리량/점유 | 취소 RPS · 취소 p95 · Hikari active/pending | 모드별 값이 채워지는가 | Hikari pending > 0 지속 = 풀 병목 |
| **Row 2** e2e 지연 | p50/p95 (order-service) | INLINE은 수십 ms, OUTBOX는 ~폴 주기 | 값이 0이면 컨슈머 아직 미수신 — smoke 먼저 |
| **Row 3** Consumer Lag | `kafka_consumer_fetch_manager_records_lag` | 장애 주입 시 OUTBOX lag 누적 / 복구 후 배수 | lag 상한 없이 계속 증가 = 폴러 장애 |
| **Row 4** Produce Rate | `kafka_producer_record_send_total` rate | OUTBOX 봉우리, INLINE 매끄러운 곡선 | 봉우리가 없으면 폴러 미동작 확인 |
| **Row 5** 장애 주입 성공률 | 취소 성공률 · 클라측 실패율 | INLINE 급락, OUTBOX 유지 | OUTBOX도 급락이면 outbox INSERT TX 확인 |

> 패널에 "No data"가 표시되면 먼저 지표명 체크리스트(§2)로 돌아간다.

---

## 7. 판정표 (결과 기록 템플릿)

실측 후 아래 표를 채운다. `measurement-journey.md` §6 템플릿과 함께 §8 실행 로그에 append.

```
### [발행 패턴 실측] (날짜/시각)
- 구성: DB=m7g.large, seed=100k, VU=200(P1)/장애런(P2), OTel=OFF, querycount=OFF

#### P1 — 처리량/점유

| 모드         | 취소 rps | p95(ms) | Hikari active | Hikari pending | 커밋 수(불변?) |
|--------------|----------|---------|---------------|----------------|---------------|
| INLINE       |          |         |               |                |               |
| INLINE_ASYNC |          |         |               |                |               |
| OUTBOX       |          |         |               |                |               |

가설 검증:
- outbox ≥ inline? __ (델타: __%)
- inline_async ≥ outbox? __
- 커밋 수 불변? __

#### P2 — 지연/장애/버스트

| 모드 | e2e p50 | e2e p95 | 장애 시 취소 성공률 | 복구 백로그 배수 | 버스트 패턴 |
|------|---------|---------|-------------------|----------------|------------|
| INLINE | ~ms | ~ms | 급락(_%→_%) | N/A (인라인 재처리) | 매끄러움 |
| INLINE_ASYNC | ~ms | ~ms | 급락(_%→_%) | N/A | 매끄러움 |
| OUTBOX(10s) | ~s | ~s | 유지(__%) | __초 | 봉우리(10s) |
| OUTBOX(1s)  | ~ms | ~ms | 유지(__%) | __초 | 봉우리(1s) |

폴 노브 트레이드:
- OUTBOX 1s 폴: 지연 _s→_ms / CPU 변화 __% / Kafka 연결 부하 __

#### 종합 판정
- 처리량: inline vs outbox 델타 = __%  (가설: ~수%)
- 지연 극적 차이: inline ~ms vs outbox ~폴주기 __s
- 장애 거동: INLINE 취소 실패 vs OUTBOX 취소 성공(이벤트만 지연)
- 버스트: OUTBOX 봉우리 최대 __건/s (batch=1000, poll=10s)
- 소견:
```

---

## 8. 실행 체크리스트 (런 전/중/후)

**런 전**
- [ ] 이미지 빌드 최신 여부 확인 (`gh workflow run loadtest-images.yml`)
- [ ] `terraform apply` 완료, SSM 접속 확인
- [ ] general_log 활성 확인: `mysql -e "SHOW VARIABLES LIKE 'general_log'"`
- [ ] 지표명 사전 확인 (§2 체크리스트 완료)
- [ ] Grafana `publish-pattern-comparison` 대시보드 패널 모두 정상 로드

**모드 전환 시**
- [ ] `--force-recreate`로 재기동
- [ ] 기동 로그 확인 (`docker logs payment-service`)
- [ ] `INLINE_ASYNC` 모드: WARN 로그 확인
- [ ] S0 smoke 1 VU로 정합성 + 경로 확인 후 스윕 시작

**런 중**
- [ ] Row 1: rps/p95/Hikari 실시간 확인
- [ ] P2 장애 주입: `docker stop <kafka-container>` → Row 5 취소 성공률 관찰 → `docker start`

**런 후**
- [ ] 정합성 게이트: 이중취소 0 / 이중한도차감 0 / request_hash dedup 100%
- [ ] §7 판정표 채우기, `measurement-journey.md` §8 실행 로그에 append
- [ ] 대시보드 캡처 저장 (블로그 3막 자료)
- [ ] `terraform destroy` (비용 차단)

---

## 9. 주의사항 및 한계

- **INLINE_ASYNC는 이벤트 유실 위험이 있다.** 처리량 **상한** 레퍼런스로만 사용하고, 장애 주입 시 취소 성공률과 발행 성공률을 분리해 읽어야 한다 (취소는 성공해도 이벤트 유실 가능).
- **단일 Kafka 브로커** 환경이다. 브로커 장애 주입이 프로덕션 멀티 브로커 환경을 완전히 재현하지는 못한다 — 거동의 **방향성** 확인이 목적이다.
- **폴 주기 1s 재런** 시 obs 스택(Prometheus) 스크레이프 부하와 실제 앱 CPU 증가를 분리해 읽는다. 노이즈가 크면 폴 주기 재런을 별도 시간대에 진행한다.
- **커밋 수 불변 확인**은 general_log가 `SET GLOBAL general_log=ON` 상태여야 한다. 런 전 확인할 것.
- 실제 수치는 AWS 실측에서만 나온다. 로컬 스모크(도커 컴포즈)는 경로 확인용이며 수치를 §7 판정표에 기입하지 않는다.
