# 결제 취소 시스템 부하 실측 여정 (Load Test Journey)

> 생성일: 2026-07-09
> 목적: 취소 플로우의 **멱등성·동시성·부분취소**가 실제 부하에서 어떻게 동작·붕괴하는지 계층별로 분리 관측한다.
> 구성도: [`topology.html`](./topology.html) (mermaid, 브라우저로 열기)

---

## 0. 실측 원칙

1. **부하 생성기와 SUT는 무조건 분리** — 부하 CPU가 앱을 침범하면 측정이 오염된다.
2. **DB는 앱과 다른 인스턴스** — 앱 CPU와 DB I/O 경합을 분리해야 "느린 게 로직인지 lock인지" 구분된다.
3. **핫패스 서비스만 단독 분리, 콜드패스는 합침** — payment / risk / 각 DB는 단독, merchant-limit·order는 합침. (근거: 취소 동기 경로 분석)
4. **서버만 분리하고 지표를 안 보면 실측이 아니다** — 아래 계층별 지표를 반드시 함께 수집한다.
5. **한 번에 한 변수만** — VU, 데이터 분포, 네트워크 지연을 동시에 바꾸지 않는다.

---

## 1. 관측 지표 (계층별)

| 계층 | 지표 | 왜 (이 시스템 특화) |
|------|------|---------------------|
| **k6 (클라이언트)** | p50/p95/**p99** 지연, TPS, 에러율, 에러코드 분포 | 사용자 체감. error-catalog 코드별 분류가 핵심 |
| **앱 (payment/risk)** | **HikariCP active/pending**, Tomcat 스레드, JVM heap/**GC pause**, CPU | pool pending>0 = 병목 신호. GC pause가 p99를 끌어올림 |
| **MySQL** | **`innodb_row_lock_waits`**, lock wait time, **deadlock 수**, active threads, slow query, buffer pool hit | TX3 `SELECT FOR UPDATE` 경합 = 이 시스템 병목 1순위 |
| **Redis** | **hit ratio**, 단일 키 경합, 분산락 획득 실패율 | `daily_limit` 폴백(Redis→DB스냅샷→HTTP) 검증 + 스케줄러 분산락 |
| **Kafka** | consumer lag(order-service), producer 발행 지연, rebalance | AFTER_COMMIT 비동기라 동기 지연 밖이지만 lag 누적은 정합성 리스크 |
| **정합성(Correctness)** | 이중 취소 0, 이중 한도차감 0, request_hash dedup 100%, `failed_kafka_event` 적재량 | **성능보다 우선하는 하드 게이트.** 부하에서 깨지면 실패 |

> 최소 관측 스택: 각 앱 **Micrometer → Prometheus → Grafana(t4g.medium)** + `node_exporter` + `mysqld_exporter` + `redis_exporter`.

---

## 2. 네트워크 / AZ 결정 (서버간 통신 실측)

**질문: 서버간 통신인데 same-AZ여도 되나?**

| 방식 | 지연 | 언제 쓰나 | 트레이드오프 |
|------|------|-----------|--------------|
| **Same-AZ (기본)** | ~0.1–0.5ms | 병목 탐색 baseline | 노이즈 최소 → compute/lock 병목이 깨끗이 보임. **단, 실제 네트워크 지연은 숨김** |
| **Cross-AZ** | ~1ms+ RTT | prod가 멀티 AZ일 때 실측 | cross-AZ 전송료($0.01/GB, 부하 트래픽은 소량), payment→risk 동기 호출마다 RTT 가산 |
| **netem 지연 주입** | 임의(예: +5ms) | **네트워크 민감도 실측 (추천)** | 실제 AZ 안 나눠도 `tc qdisc`로 payment→risk 링크에 인위적 RTT 주입 → **타임아웃/서킷브레이커 반응 관측** |

**판단 기준:**
- **1차(baseline)는 same-AZ로 간다.** 목적이 "compute/lock 병목 탐색"이므로 네트워크 노이즈를 줄이는 게 맞다. same-AZ여도 **문제없다.**
- 이 시스템은 payment→risk가 **동기 HTTP 핫패스**다. prod가 멀티 AZ 예정이면, baseline 이후 **payment→risk 링크에만** cross-AZ 또는 `netem +1~5ms`를 주입해 별도 측정한다.
  - 관측 포인트: RTT 가산 시 p99가 선형 증가하는가, **서킷브레이커가 열리는가**, 타임아웃/재시도 폭풍이 생기는가.
- 반대로 여러 축을 한꺼번에 바꾸지 마라. AZ는 "compute 병목 다 찾은 뒤" 별도 스테이지로.

---

## 3. VU 램프업 스테이지 (2축)

VU 수만이 아니라 **데이터 분포**가 이 시스템의 병목을 결정한다.

### 축 A — 처리량 (분산된 payment)

각 요청이 서로 다른 payment/merchant → lock 경합 없이 순수 throughput/CPU/pool 한계 탐색.

| 스테이지 | VU | 유지 | 목적 | 관측 포인트 |
|---------|-----|------|------|-------------|
| S0 smoke | 1 | 1분 | 정합성 + warmup(JIT/pool/Flyway) | 에러 0, 응답 정상 |
| S1 baseline | **10** | 3분 | 무경합 기준선 (p50/p95 확정) | 깨끗한 지연 수치 |
| S2 ramp | 10→50→100 | 각 3분 | **knee 탐색** | p99 꺾이는 지점, pool pending 시작점 |
| S3 stress | 100→붕괴까지 | step | **breaking point** | 에러율 급등, 타임아웃 캐스케이드 |
| S4 soak | knee의 70% | **30분+** | 메모리 누수, pool 고갈, GC 추세 | heap 우상향 여부, lag 누적 |

### 축 B — 경합 (동일 payment / 동일 merchant)

`idempotency-test.js`, `compensation-test.js` 활용.

| 시나리오 | 부하 | 목적 | 관측 포인트 |
|---------|------|------|-------------|
| 따닥 멱등 | **같은 payment_id**에 10 VU 동시 | request_hash UNIQUE 차단 | 정확히 1건만 성공, 나머지 멱등 응답 |
| row lock 경합 | 같은 payment 부분취소 동시 | TX3 `FOR UPDATE` 대기 | `innodb_row_lock_waits`, lock timeout |
| **핫 merchant** | 한 merchant에 부하 집중 | `merchant_cancel_usage` 단일 row + `daily_limit` 단일 Redis 키 경합 | 한도 차감 직렬화 지점, Redis 단일키 hotspot |
| 보상 경로 | risk 차감 후 실패 유도 | compensation_retry 재시도 | 보상 성공률, 중복 보상 0 |

---

## 4. 더 측정할 것 (발산 → 우선순위)

브레인스토밍 결과를 우선순위로 분류. ★ = 이 시스템에서 특히 중요.

### 필수 (P0)
- ★ **정합성 게이트**: 부하 중 이중 취소 / 이중 한도차감 / request_hash 누락 = **즉시 실패**. 성능보다 우선.
- ★ **스케줄러 간섭**: pending-recovery(60s)·processing-recovery(60s)·outbox-publisher(10s)·compensation-retry(30s)가 부하와 **동시에 돌며 DB lock·Redis 분산락 경합**. 스케줄러 on/off 비교 측정.
- ★ **HikariCP 고갈**: connection acquisition time, pending threads. 취소 TX가 길어 pool이 병목 1순위 후보.
- ★ **DB deadlock / lock wait timeout**: 빈도 + 발생 시 상태 정합성 유지되는가.

### 중요 (P1)
- ★ **서킷브레이커** (payment→risk, payment→merchant-limit): risk 지연/실패 주입 시 open/half-open/closed 전이. Resilience4j 지표.
- **outbox-publisher 배치(1000건) 발행 스파이크**: Kafka 발행 지연 + `failed_kafka_event` 적재.
- **에러 taxonomy**: 부하에서 어떤 error-catalog 코드가 뜨나 (비즈니스 vs 인프라 분리).
- **타임아웃/재시도 폭풍**: 다운스트림 느려질 때 재시도가 부하를 증폭시키는가.
- **cold start / warmup**: 첫 요청 지연 (JIT, pool fill). baseline 오염 방지 위해 워밍업 필수.

### 관찰 (P2)
- **GC pause ↔ p99 상관**: pause가 tail latency를 끌어올리는 정도.
- **동기 fan-out tail 증폭**: payment가 risk를 동기 호출 → tail이 곱해짐.
- **backpressure 거동**: breaking point에서 우아한 degradation인가 캐스케이드인가.
- **KST 날짜 경계**(`daily_limit:{merchantId}:{kstDate}`): 자정 롤오버 (부하 범위 밖일 수 있음, 기능 테스트로).

---

## 5. 판단 기준 (Pass / Knee / Breaking point)

| 판정 | 정의 | 기준(초안 — 실측 후 조정) |
|------|------|---------------------------|
| **Pass** | 정상 처리 구간 | 에러율 < 1% AND p99 < (baseline p99 × 3) AND 정합성 위반 0 |
| **Knee (무릎)** | 성능이 비선형으로 꺾이는 지점 | p99가 급격히 상승 시작 **또는** HikariCP pending > 0 지속 **또는** row_lock_waits 급증 |
| **Breaking point** | 서비스 붕괴 | 에러율 > 5% **또는** 타임아웃 캐스케이드 **또는** 서킷 open 고착 |
| **정합성 게이트(하드)** | 부하와 무관하게 반드시 | **어떤 부하에서도** 이중 취소 0, 이중 한도차감 0, request_hash dedup 100%. 하나라도 깨지면 **성능 수치와 무관하게 실패** |

> 운영 목표 용량(capacity)은 보통 **knee의 60~70%** 로 잡는다.

---

## 6. 결과 기록 템플릿

각 스테이지 실행마다 아래를 채운다.

```
### [스테이지] S?-축?  (날짜/시각)
- 구성: VU=?, 데이터분포=?, AZ/netem=?, 스케줄러=on/off
- 처리량: ? TPS
- 지연: p50=? / p95=? / p99=? (ms)
- 에러율: ?% (코드별: ...)
- 병목 지표: HikariCP pending=?, row_lock_waits=?, deadlock=?, GC pause=?
- 정합성: 이중취소=?, 이중한도차감=?, dedup=?%
- 판정: Pass / Knee / Breaking
- 소견/다음 액션:
```

---

## 7. 실행 절차 (Runbook)

부하 테스트 1회 = 아래 순서. **기록(6→7단계)을 빼먹으면 실측이 아니다.**

0. **이미지 준비(코드 바뀐 경우만)** — GitHub Actions `loadtest-images.yml` 실행 → Docker Hub 갱신
   - `gh workflow run loadtest-images.yml` (사전: `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` secret)
1. **인프라 기동** — `cd infra/load-test && terraform apply`
   - `terraform output ssm_connect` / `private_ips` 확인
2. **배포** — `IMAGE_NS=<dockerhub-user> ./infra/load-test/deploy/ssm-deploy.sh`
   - SSM `send-command`로 role 태그별 인프라→DB→앱 순서 일괄 배포 (호스트는 Docker Hub pull만)
   - **관측 자동 포함**: node-exporter(전 호스트) + obs 스택(Prometheus/Grafana/exporter). 로그 검색까지 원하면 `LOG_CLOUDWATCH=1`.
2b. **관측 열기(권장)** — `./infra/load-test/deploy/port-forward.sh grafana` → 브라우저 `localhost:3000`. 부하 중 이 대시보드만 보면 됨(CLI 로그 archaeology 불필요).
3. **워밍업** — S0 smoke(1 VU) 1분: 정합성 확인 + JIT/pool/Flyway warmup (baseline 오염 방지)
4. **스테이지 실행** — §3 순서대로. **한 번에 한 변수만** 바꾼다.
   - 축 A(처리량): `STAGE=baseline ./k6/run-stage.sh` — S1→S2→S3→S4
   - 축 B(경합): `SCRIPT=k6/hot-merchant.js VUS=30 ./k6/run-stage.sh`, `k6/idempotency-test.js`
   - Grafana 연동: `PROM=http://10.0.1.50:9090/api/v1/write` / 로컬 HTML 리포트: `REPORT=report.html`
   - (선택) 네트워크: payment→risk에 `tc netem` 지연 주입 후 서킷/타임아웃 관측
5. **관측 수집** — Grafana(`port-forward.sh grafana`)에서 §1 지표 캡처 (앱 pool/GC, MySQL row_lock/deadlock, Redis hit, Kafka lag). 로그는 CloudWatch Logs Insights(`/loadtest/apps`).
6. **정합성 검증** — 이중취소/이중한도차감/dedup (성능 무관 하드 게이트)
7. **기록** — §6 템플릿으로 **§8 실행 로그에 append**. 판정(Pass/Knee/Breaking) 명시
8. **정리** — `terraform destroy` (NAT Gateway 유휴 비용 차단)

> 구성이 바뀌면 `topology.html`도 재생성한다 (mermaid-diagram 스킬).

---

## 8. 실행 로그

> 실측할 때마다 §6 템플릿으로 아래에 append.

### [스테이지] S0-smoke (2026-07-10, 로컬 docker-compose)
- 구성: VU=1, iter=20, 데이터분포=분산(200건 SQL 시딩), TARGET=local, 스케줄러=on
- 처리량: 9.3 req/s (smoke — 부하 아님, 워밍업/정합성 확인용)
- 지연: p50=58ms / p95=149ms / p99<1000ms(threshold pass) / max=942ms(첫 요청 콜드)
- 에러율: 0% (http_req_failed 0/20)
- 정합성: 20/20 status=COMPLETED (실제 TX3 취소 완료), 이중취소 없음
- 판정: **Pass** (threshold 3개 전부 통과)
- 소견: 파이프라인(docker→앱4→SQL시딩→k6) E2E 정상 확인. seed.sh awk 작은따옴표를 octal(`\047`)로 수정(hex `\x27C` 오파싱 버그). 다음: baseline(10 VU 3분) → ramp.

### [스테이지] S0-smoke (2026-07-10, **AWS** 분리 인프라)
- 구성: VU=1, iter=20, 데이터분포=분산(5000건 SQL 시딩), TARGET=aws, 스케줄러=on
- 지연: med=80ms / p95=161ms / p99=1.14s(콜드 첫 요청 max=1.39s가 20샘플 p99 왜곡)
- 에러율: 0% / 정합성: 20/20 status=COMPLETED
- 판정: **Pass** (p99 threshold cross는 워밍업 노이즈). AWS E2E 파이프라인 검증 완료.
- 인프라 배포 중 라이브 버그 3건 수정: SG description ASCII화, SSM 문서명 `AWS-RunShellScript`, ssm-deploy 멀티라인 JSON 전달(+bash3.2 case).

### [스테이지] S1-baseline (2026-07-10, **AWS** 분리 인프라)
- 구성: VU=10, 3분, 데이터분포=분산(**100k** SQL 시딩; 5k는 30초만에 소진→재시딩), TARGET=aws, 스케줄러=on
- 처리량: **~190 rps** (34,194건/3분)
- 지연: **med=53ms / p95=60ms / p99=65ms** (워밍업 후 매우 타이트, 지연 자체는 우수)
- 에러율: **7.63%** (2610/34194) — cancel_success_rate 92.36%
- 판정: **Fail (robustness 게이트)** — 지연은 Pass급이나 에러율 7.63%
- **근본원인(축 B 심화로 확정)**: risk `ValidateAndReserveService`의 **merchant별 Redis 분산락(SET NX, 대기·재시도 없음)**. seed가 merchant 10개뿐 → 190 rps가 10개 merchant 락에 집중, 동일 merchant 동시 취소 시 락 못 잡은 쪽이 즉시 `RISK_SERVICE_UNAVAILABLE`로 거부.
  - 초반 소수 `merchant_cancel_usage` INSERT 데드락(1213/40001)도 관측 → 락 TTL(5s)이 TX 중 만료되어 동시 진입한 2차 효과.
- 상세·정량화는 아래 **hot-merchant(축 B)** 참조.

### [스테이지] hot-merchant 축 B 경합 스윕 (2026-07-10, **AWS**)
- 구성: 단일 merchant 집중(신선 풀 merchant 21~24, 각 20k 미취소), 각 VUS 20초, TARGET=aws
- 동시성 → 에러율 곡선:

  | VUS | rps | **에러%** | p95 | 성공률 |
  |-----|-----|---------|-----|--------|
  | 1 (대조) | 22 | **0.00%** | 48ms | 100% |
  | 5 | 115 | **32.7%** | 54ms | 67% |
  | 20 | 292 | **99.93%** | 76ms | 0.07% |
  | 50 | 299 | **99.95%** | 176ms | 0.05% |

- **근본원인 확정**: `ValidateAndReserveService.execute()` L37-42 — `lock:risk:merchant:{merchantId}` **Redis `SET NX` 분산락**. 락 획득 실패 시 **대기·큐·재시도 없이 즉시** `ServiceUnavailableException.riskServiceUnavailable()` throw → `RISK_SERVICE_UNAVAILABLE`(503).
- **해석**: 이 락은 merchant별 취소를 **직렬화**(→ 단일 merchant DB 데드락 0)하지만, **동시 요청을 그대로 거부**로 변환. 핫 merchant(대형 가맹점/플래시)는 취소가 사실상 1건씩만 통과, 나머지 전량 fail-fast. 지연이 낮은 채로 에러율만 폭증하는 시그니처가 이를 증명.
- **정합성은 안전**: 실패분은 차감 전 거부라 이중차감/이중취소 없음(하드 게이트 통과). 문제는 **가용성**.
- **개선 후보**: (a) 락에 **bounded wait + 재시도**(예: Redisson tryLock timeout), (b) 앱 락 제거하고 DB `INSERT ... ON DUPLICATE KEY UPDATE used_amount=used_amount+?` **원자 upsert + 행락**으로 대체, (c) merchant별 취소를 큐잉. 락 TTL(5s) < 최악 TX 시간이면 데드락 재발하므로 TTL/재시도 함께 손봐야 함.

### [스테이지] AFTER — 원자 조건부 UPDATE 적용 (2026-07-10, **AWS**, PR #43)
개선안 (b) 적용: Redis 분산락·FOR UPDATE 제거 → DB 원자 조건부 UPDATE(`WHERE used+amt<=daily_limit`). 동일 부하로 재측정한 before/after:

**축 A baseline (10 VU, 3분, 분산):**
| | BEFORE | AFTER |
|---|--------|-------|
| 에러율 | 7.63% | **0.00%** (0/33,705) |
| 처리량 | ~190 rps | ~187 rps |
| 지연 | med 53 / p99 65ms | med 53 / p95 60ms, **max 273→117ms**(데드락 stall 소멸) |

**축 B hot-merchant (단일 merchant, VUS 스윕, 신선 풀):**
| VUS | BEFORE 거부% | **AFTER 거부%** | AFTER rps | AFTER p95 | RISK_SERVICE_UNAVAILABLE |
|-----|------------|---------------|-----------|-----------|--------------------------|
| 1 | 0% | 0.00% | 15 | 70ms | 0 |
| 5 | 33% | **0.00%** | 89 | 65ms | 0 |
| 20 | 99.93% | **0.00%** | 171 | 130ms | 0 |
| 50 | 99.95% | **0.00%** | 175 | 300ms | 0 |

- **판정: Pass.** 핫 merchant 99.9% 거부 → **0%**. `RISK_SERVICE_UNAVAILABLE` 0건.
- **설계 의도 실증**: 경합이 **가용성(거부) → 지연(p95 70→300ms)** 으로 전환. 요청이 튕기는 대신 DB 행락에서 잠깐 대기 후 **전부 성공**(건강한 백프레셔). AFTER rps는 실제 성공 처리량(BEFORE의 290rps는 대부분 fast 거부의 가짜 처리량).
- **관측**: 이번 실측부터 obs(Grafana) 정상 기동 — `port-forward.sh grafana`로 에러율/지연 대시보드 확인 가능.
- 코드 증명(단위): 단일 merchant 50스레드 IT에서 초과차감 0·spurious 거부 0(동시성 테스트).

### [실험] daily_limit 해석 층 3-config + netem (2026-07-10, **AWS**, PR #45)
"HTTP-in-TX 비용"을 실측. 플래그로 해석 경로 토글: **A**(Redis 캐시 ON) / **B**(캐시 OFF→DB 스냅샷) / **C**(캐시+스냅샷 OFF→매 요청 merchant-limit HTTP). 분산 부하(10 merchant).

**1) VU 스윕 (merchant-limit 정상 속도):** A/B/C **차이 없음**. 20 VU: A 162 / B 182 / C 172 rps(±노이즈, 에러 0). VU를 100/200/400으로 밀어도 A≈C, **rps ~185에서 포화**(천장이 config 무관 → 병목은 해석 층이 아니라 payment→risk 동기 홉). **정상 상태에선 HTTP-in-TX가 공짜.**

**2) netem — merchant-limit egress 지연 주입 (VU 60):** 여기서 절벽이 드러남.
| 지연 | A(cache) rps/p95 | C(HTTP 매번) rps/p95 |
|------|------------------|----------------------|
| 0ms | 174 / 380ms | 170 / 388ms |
| 100ms | 176 / 333ms | **11.5 / 5.13s** |
| 200ms | 166 / 361ms | **6.0 / 9.96s** |

- **A는 불변, C는 100ms에서 15배·200ms에서 26배 붕괴.** 매 요청이 느린 HTTP를 **TX 안에서** 대기 → DB 커넥션 점유 → HikariCP 풀(10) 포화.
- **판정**: HTTP-in-TX는 **잠복 시한폭탄**. merchant-limit가 빠르면 공짜, 100ms만 느려져도 재앙. Redis 캐시는 성능 최적화가 아니라 **회복탄력성 방패**(느린 의존성을 TX 밖으로 몰아냄)였음.
- **후속(limit 해석 TX 밖으로)의 정당화**: 성능이 아니라 **의존성 열화 시 tail 위험 제거**. 실서비스는 2단 캐시가 보호하나 "캐시 콜드 버스트 + merchant-limit 슬로우" 겹침이 C의 붕괴를 현실화.
- netem은 실험 후 원복(`tc qdisc del`).

---

## 9. 인스턴스 사이징 (패밀리 규칙 + 역할별 근거)

**사이징 원칙**: 핫패스(payment/risk)가 **병목이 돼야** 실측이 의미 있다.
부하생성기·DB는 병목이 되면 안 되되, **과하게 잡으면 병목을 "덮어"** 측정을 흐린다.
(예: DB RAM이 크면 버퍼풀이 I/O 병목을 감춰 "무릎"이 안 보인다.)

### Graviton 패밀리 규칙 (전부 ARM, t4g만 Graviton2)

| 패밀리 | 성격 | vCPU당 RAM | `large` | `xlarge` |
|--------|------|-----------|---------|----------|
| `c7g` | Compute 최적화 | 2 GB | 2 vCPU / 4 GB | 4 vCPU / 8 GB |
| `m7g` | 범용 | 4 GB | 2 vCPU / 8 GB | 4 vCPU / 16 GB |
| `r7g` | 메모리 최적화 | 8 GB | 2 vCPU / 16 GB | 4 vCPU / 32 GB |
| `t4g` | 버스터블(CPU 크레딧) | 1 GB | — | small 2/2 · medium 2/4 |

> 사이즈 규칙: `large`=2 vCPU, `xlarge`=4 vCPU. RAM은 패밀리(vCPU당 GB)로 결정.

### 역할별 배치 (`infra/load-test/instances.tf`)

| 역할 | 타입 | vCPU/RAM | 근거 |
|------|------|----------|------|
| k6 (부하생성) | `c7g.xlarge` | 4 / 8 GB | 생성기가 병목되면 안 됨 → 여유 확보 |
| payment (핫) | `c7g.xlarge` | 4 / 8 GB | **측정 주인공**. compute 최적화 |
| risk (핫) | `c7g.xlarge` | 4 / 8 GB | 한도차감 동시성 주인공 |
| cold-svc | `c7g.large` | 2 / 4 GB | merchant-limit + order 합침(콜드) |
| mysql-payment | `m7g.large` | 2 / 8 GB | TX3 row lock 대상. **r7g(16GB)는 버퍼풀이 I/O 병목을 덮어** m7g로 하향 |
| mysql-risk | `m7g.large` | 2 / 8 GB | 한도 소진 경합. 동일 근거 |
| cold-db | `c7g.large` | 2 / 4 GB | merchant+order DB(콜드), 트래픽 소량 → 4GB 충분 |
| infra | `m7g.large` | 2 / 8 GB | Redis + Kafka(1-broker). Kafka page cache용 RAM 유지 |
| obs | `t4g.medium` | 2 / 4 GB | Prometheus+Grafana. 9타깃 히스토그램 스크레이프엔 2GB/버스트 크레딧 부족 → 4GB |

**핵심 판단 3가지**
1. **DB는 m7g/c7g로 충분** — 버려도 되는 부하테스트 DB에 r7g(vCPU당 8GB)는 과잉이고, RAM이 크면 I/O 병목을 가려 실측 신뢰도를 떨어뜨린다.
2. **핫패스만 c7g.xlarge 유지** — knee/breaking을 payment/risk에서 관측하려면 이들이 먼저 포화돼야 한다.
3. **obs는 오히려 t4g.small→medium 상향** — 관측 스택이 죽으면 실측 데이터를 잃는다.

> knee를 더 낮은 VU에서 빨리·싸게 보고 싶으면 payment/risk를 `c7g.large`(2 vCPU)로 낮춰 무릎을 앞당기는 선택지도 있다(측정 목적에 따라).

---

## 10. white-box 관측 (트레이스 + 요청당 쿼리 수)

- **분산 트레이싱**: OTel Java agent → Grafana Tempo(obs :4317). 활성화는 실측 compose env:
  `OTEL_JAVAAGENT="-javaagent:/otel/opentelemetry-javaagent.jar" docker compose ... up -d --force-recreate`
- **요청당 쿼리 수**: `LOADTEST_QUERYCOUNT_ENABLED=true` → Grafana "요청당 쿼리 수" 대시보드. `uri`별 p95로 N+1 감시.
- **왜곡 주의**: 트레이싱 100%(parentbased_always_on) + proxy 래핑은 오버헤드다. baseline과 비교 시 두 토글을 끈 채 먼저 재고, 벌어지면 `OTEL_TRACES_SAMPLER=traceidratio` + ratio env로 낮춘다.
