# AWS 실측 런 계획 — 2026-07 (관측 스모크 + 소비/정합성 + realistic-mix + 3-config 갱신)

> 생성일: 2026-07-12
> 목적: 발행 라인(16~19편) 종료 후 **남은 미측정 축**을 한 판에 닫는다 — ⑤ Kafka 소비/정합성(미측정), ⑦ 현실 취소 믹스(미커밋), ④ 3-config 220 baseline 갱신, ③ white-box 관측 런타임 스모크(툴 검증).
> 관련: [`measurement-journey.md`](./measurement-journey.md) §7(인프라 기동·SSM·기록 템플릿) · [`saturation-diagnosis.md`](./saturation-diagnosis.md)(USE·Hikari) · [`publish-pattern-benchmark.md`](./publish-pattern-benchmark.md) §2(Kafka 지표명 사전확인)
> 상태: **미실행** — 이 문서는 다음 세션이 `terraform apply` 후 그대로 실행하는 절차서다.

---

## 0. 스코프 — 무엇을 재고, 무엇을 안 재는가

발행 라인 종료 시점의 백로그를 **재분류**해 실제 미측정만 남겼다:

| 축 | 실제 상태 | 이번 런 |
|---|---|---|
| ⑤ Kafka 소비/정합성 | **한 번도 안 잼** (지금껏 취소 *생성* 축만 측정) | ✅ Phase M (무료 피기백) |
| ⑦ realistic-mix | 미커밋 + 시드 SQL 미검증 | ✅ Phase M (스모크→측정→커밋) |
| ④ 3-config (A/B/C) | 이미 측정(PR #45/#47, 185 baseline). 결론 성립하나 커밋6→4 후 220 baseline 갱신 | ✅ Phase M (사용자 선택) |
| ③ white-box 관측 스모크 | 툴 검증(측정 아님) | ✅ Phase O |
| ② DB 2→4 vCPU | 최후 수단(돈 레버) | ❌ 이번 런 제외 |
| ⑥ limit 해석 TX 밖으로 | **이미 데이터로 정당화**(PR #47 netem C 15배 붕괴 = tail 위험). 측정 아닌 **코드 PR** | ❌ 런 대상 아님 (별도 코드 PR) |

### 토글이 상충 → 2페이즈로 분리

- **③ 관측 스모크**는 `OTEL_JAVAAGENT` ON + `LOADTEST_QUERYCOUNT_ENABLED` ON 필요(트레이스·쿼리곡선 봐야).
- **⑤⑦④ 처리량**은 확립된 방법론상 OTel OFF + QC OFF(agent 오버헤드 노이즈 제거, 공정 rps — 커밋 6→4 재측정과 동일 토글).

한 런 안에서 **Phase O(관측 ON) → Phase M(관측 OFF)** 순서로 돌린다. 재배포로 토글 전환.

---

## 1. Prep — 공통 기동 (measurement-journey §7 준수)

```bash
export IMAGE_NS=camelia9999          # Docker Hub 네임스페이스
export AWS_REGION=ap-northeast-2      # SSM 로컬 실행 필수(글로벌 sts만 리전 없이 통과)
# SSM 문서명은 AWS-RunShellScript (이 계정에 AWS-RunShellCommand 없음)
```

- [ ] `terraform apply` (obs 온디맨드 spot=false — PR #42). repo는 public 유지(복원 불필요).
- [ ] **docker READY 폴링 후 배포** — 부팅 직후 user_data(docker 설치) 완료 전 ssm-deploy 하면 infra 컨테이너 누락. `docker info` READY 확인 후 `ssm-deploy.sh`.
- [ ] **order_db 미러** — seed는 payment_db만 채운다. order consumer가 이벤트를 처리하려면 `orders`+`order_item`이 payment_item과 같은 id로 있어야 함(없으면 e2e·정합성 축 전멸). `mysql root@10.0.1.32:3307`로 payment_item→order_db 미러.
- [ ] **k6 arm64** — m7g(Graviton)이라 amd64 바이너리는 "Exec format error". `k6 linux-arm64` 다운로드. k6 호스트는 ssm-deploy 미프로비저닝 → k6·repo·mysql-client·seed 수동 부트스트랩.
- [ ] `port-forward.sh grafana kafka-ui prometheus actuator` (PR #42) → 브라우저 대시보드.
- [ ] **Prometheus 메모리 감시** — k6 remote-write 고카디널리티가 스트레스에서 Prometheus를 OOM시킨 전례(OUTBOX 런). realistic-mix의 `path` 태그는 값 3개(new/rehit/partial)라 저카디널리티 → OK 예상. OOM 조짐이면 k6 remote-write 끄고 **k6 web dashboard + DB 직접 샘플**로 전환.

---

## 2. Phase O — white-box 관측 런타임 스모크 (OTel/QC ON)

**목표**: 관측 툴이 런타임에 실제로 신호를 내는지 검증(처리량 숫자 아님). 이게 끝나야 "실측으로 증명" 블로그 스레드가 닫힌다.

```bash
# 재배포 env — 관측 전부 ON, 저부하
OTEL_JAVAAGENT="-javaagent:/otel/opentelemetry-javaagent.jar" \
LOADTEST_QUERYCOUNT_ENABLED=true \
SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true
```

- [ ] 저부하 런 (VU 60, 2~3분). 취소 발생시켜 트레이스/메트릭 채우기.
- [ ] **(1) 3-홉 트레이스**: Grafana Tempo에서 `payment → risk → merchant-limit` span 트리 렌더 확인.
- [ ] **(2) 쿼리수 곡선**: 대시보드 `db.queries.per_request{uri}` — 취소 uri가 ~18(또는 커밋4 반영값) 근처 곡선.
- [ ] **(3) netem span 폭**: merchant-limit 호스트에 지연 주입 후, 그날 첫 취소(onset)의 merchant-limit 홉 span이 **넓어지는지**(=11편 "커넥션 점유" 시각화).
  ```bash
  # merchant-limit(cold-svc) egress 지연 주입 / 원복
  sudo tc qdisc replace dev ens5 root netem delay 100ms
  sudo tc qdisc del  dev ens5 root                        # 원복 (Phase M 전 반드시)
  ```

**판정**: 3개 다 보이면 ③ 닫힘. netem은 **Phase M 전에 반드시 원복**(안 그러면 처리량 오염).

---

## 3. Phase M — 처리량 + 소비/정합성 (OTel/QC OFF, 공정 rps)

```bash
# 재배포 env — 관측 OFF, tomcat만 ON (커밋 6→4 재측정과 동일 토글)
OTEL_JAVAAGENT= \
LOADTEST_QUERYCOUNT_ENABLED=false \
SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true
```

고정: DB `m7g.large` · 시드 100k(3-config용) · stress 스윕 50→400VU 6분.

### 3a. ⑦ realistic-mix — 시드 스모크 먼저 (커밋 전 필수 게이트)

미검증 SQL(다중아이템 크로스조인·파레토 awk·GROUP BY JSON)을 **작은 시드로 먼저 검증**:

```bash
SEED_COUNT=2000 ITEMS_PER_PAYMENT=3 HOT_MERCHANT_COUNT=2 \
HOT_TRAFFIC_PCT=80 TIGHT_DAILY_LIMIT=200000000 bash k6/seed/seed.sh
```
- [ ] `paymentKeys.json`에 `paymentItemIds` **배열**이 나오나 (다중아이템)
- [ ] 핫 2가맹점에 ~80% 몰렸나 (파레토)
- [ ] 타이트 한도 초과 시 거부 나오나 (FAILED 창발)

스모크 통과 후 본 측정:
```bash
TARGET=aws VUS=300 DURATION=6m PROM=http://10.0.1.50:9090/api/v1/write \
  k6 run k6/realistic-mix.js
```
- [ ] `path` 태그(new/rehit/partial)별 성공률·지연
- [ ] **균등 220 대비 편중/재히트의 유효 처리량** — 현실 믹스가 처리량을 얼마나 깎나
- [ ] **스모크+측정 통과 시** → 브랜치 `feat/realistic-mix-scenario`로 `k6/realistic-mix.js`+`k6/seed/seed.sh` 커밋+PR

### 3b. ④ 3-config 220 baseline 갱신 (A/B/C)

`ValidateAndReserveService` 플래그(`risk.limit.{cache,snapshot}.enabled`) 재배포 토글. 100k 균등 시드로 각 config VU 스윕:

| config | cache | snapshot | 의미 |
|---|---|---|---|
| A | on | — | 현행(캐시 히트) |
| B | off | on | DB 스냅샷 |
| C | off | off | 매 요청 merchant-limit HTTP (HTTP-in-TX) |

```bash
RISK_LIMIT_CACHE_ENABLED=<t/f> RISK_LIMIT_SNAPSHOT_ENABLED=<t/f>  # 재배포
```
- [ ] 정상 merchant-limit에서 A/B/C rps 차이 — 커밋6→4 후 **220 baseline**에서도 "정상상태 HTTP-in-TX 공짜"가 유지되나(185 때 결론 재확인)

### 3c. ⑤ Kafka 소비/정합성 (위 런들에 피기백 — 별도 부하 불필요)

지표명 사전확인은 [`publish-pattern-benchmark.md`](./publish-pattern-benchmark.md) §2 체크리스트 재사용(`cancel_event_e2e_latency`, `kafka_consumer_fetch_manager_records_lag`, `failed_kafka_event`).

- [ ] **① consumer lag**: order-service `kafka_consumer_fetch_manager_records_lag` — 비동기라 처리량엔 안 보이나 정합성 지연. 스트레스 중 lag 상한·해소 시간.
- [ ] **② failed_kafka_event / DLQ**: `failed_kafka_event` 적재분 + RetryRouter/DLQ 경로 탄 것 있는지.
- [ ] **③ e2e 정합성**(런 종료 후 DB 대조):
  - 최종 **order 상태 == payment 상태** (order_db vs payment_db, cancelRequestId 조인)
  - **이중취소 0** (같은 payment_item 두 번 취소 없음)
  - **dedup 100%** (producer_send 대비 order 고유 처리 = 1:1, 발행-패턴 런의 라이브락 지문과 대비)

---

## 4. 판정표

| 축 | 성공 기준 |
|---|---|
| ③ 관측 스모크 | Tempo 3-홉 트레이스 + 쿼리곡선 + netem span 폭 3개 모두 렌더 |
| ⑦ realistic-mix | 시드 SQL 3항목 검증 통과 + path별 성공률 수집 + 유효처리량 vs 균등220 정량화 → PR |
| ④ 3-config | 220 baseline에서 A/B/C 차이(정상상태 공짜 재확인 or 반증) |
| ⑤ Kafka 소비 | lag 해소됨 + DLQ 0(또는 원인규명) + e2e 정합성 3항목 통과 |

---

## 5. Teardown

- [x] netem 원복 확인 (`tc qdisc show dev ens5`가 clean)
- [x] `terraform destroy` (종료 시 필수 — 과금 방지)
- [x] 결과 기록(아래 §6)

---

## 6. 결과 (2026-07-12 실행)

### Phase O — 관측 스모크 ✅ (3/3, "실측으로 증명" 스레드 닫힘)
- **3-홉 트레이스**: payment→risk→merchant-limit onset 트레이스 10건(~2s) Tempo 렌더.
- **쿼리수**: cancel uri `db_queries_per_request` count 31,949(취소수 일치), 취소당 **12.0 SQL**(18총=12SQL+6COMMIT 분해와 정합).
- **netem span 폭**: config C+netem 100ms → 취소 54ms→**970ms**, merchant-limit 홉이 span 지배("커넥션 점유=span 폭", 11편 시각화). fresh 키 필요(멱등 리플레이는 risk 미경유).

### ④ 3-config 220 baseline 갱신
| config | 해석 경로 | rps(스윕평균) | 취소 |
|---|---|---|---|
| A (Redis 캐시) | 캐시 히트 | **188.8** | 68,368·100% |
| B (DB 스냅샷) | non-locking read | **192.7** | 69,756·100% |
| C (HTTP-in-TX 매번) | merchant-limit HTTP | **186.4** | 67,502·100% |

→ 스프레드 ~3% = 노이즈. **정상상태 daily_limit 해석층은 병목 아님**(A≈B≈C, 185 baseline 결론 재확인). HTTP-in-TX 정상상태 공짜 — 위험은 의존성 지연 시만(PR #47 netem 15배 붕괴).

### ⑤ Kafka 소비/정합성 (config-B 깨끗한 측정)
- **consumer lag → 0** (payment.cancelled 170,093 전량 소비).
- **DLQ 델타 = 0**, **retry 델타 = 0** (올바른 미러로 실패 0).
- **e2e 정합**: processed_cancel_event 101,062 ≈ orders CANCELLED 101,072 ≈ order_item CANCELLED 101,102. dedup(UK)로 exactly-once.
- **장애 경로 실증(보너스)**: order_db 미러 초기 버그(`OrderStatus.CREATED` 부재)로 66,822건이 **RetryRouter→retry(174k 재발행)→DLQ** 경로 탐 → 컨슈머 장애 처리 실증. 수정(status=PAID) 후 config-B는 델타 0.
- **미러 방법**: payment_item→order_db(orders.id=payment.id, order_item.id=order_item_id, status=PAID). 두 DB 별도 인스턴스라 k6 호스트에서 배치 INSERT IGNORE. `orders.status`는 유효 enum(PAID) 필수 — CREATED 아님.

### ⑦ realistic-mix
- path mix: **new 60% · partial 25% · rehit 15%**(REHIT_PCT 정확).
- 성공률: 집계 **25.2%**, per-path **rehit 39.2% · new 22.9% · partial 22.6%**.
- **해석**: 핫 2가맹점 80% 편중 + 타이트 한도(2억) 소진 → 대량 거부. rehit은 멱등 리플레이라 상대적 높음. **"균등 100% 성공"의 현실 보정.** 시드 SQL(다중아이템·파레토·JSON) 스모크 검증 통과 → PR #62.

### 방법론 메모
- 토글 2페이즈 분리 유효(관측 ON vs 공정 rps OFF).
- **멱등 리플레이 함정**: 이미 취소된 키 재취소는 risk/merchant-limit 미경유(3.68ms) → config/netem 실험엔 fresh 키 재시딩 필수.
- kafka-exporter 호스트 포트 미매핑 → consumer lag은 브로커 `kafka-consumer-groups`(cp-kafka, no .sh) 직접. DLQ는 `payment.cancelled.DLQ`.
- SSM 따옴표 지옥 → 스크립트 base64 전송이 안전.
