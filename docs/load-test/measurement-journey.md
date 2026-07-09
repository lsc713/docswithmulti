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

> 최소 관측 스택: 각 앱 **Micrometer → Prometheus → Grafana(t4g.small)** + `node_exporter` + `mysqld_exporter` + `redis_exporter`.

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

1. **인프라 기동** — `cd infra/load-test && terraform apply`
   - `terraform output ssm_connect` / `private_ips` 확인
2. **배포** — SSM 접속(`aws ssm start-session --target <id>`) 후 role별 컨테이너 기동
   - `infra/load-test/deploy/` — 인프라→DB→앱 순서 (compose가 사설 IP 배선 주입)
   - `infra/load-test/observability/` — node-exporter(9대 전부) + obs 스택(Prometheus/Grafana/exporter)
3. **워밍업** — S0 smoke(1 VU) 1분: 정합성 확인 + JIT/pool/Flyway warmup (baseline 오염 방지)
4. **스테이지 실행** — §3 순서대로. **한 번에 한 변수만** 바꾼다.
   - 축 A(처리량): `k6/load-test.js` — S1→S2→S3→S4
   - 축 B(경합): `k6/idempotency-test.js`, `k6/compensation-test.js`
   - (선택) 네트워크: payment→risk에 `tc netem` 지연 주입 후 서킷/타임아웃 관측
5. **관측 수집** — Grafana에서 §1 지표 캡처 (앱 pool/GC, MySQL row_lock/deadlock, Redis hit, Kafka lag)
6. **정합성 검증** — 이중취소/이중한도차감/dedup (성능 무관 하드 게이트)
7. **기록** — §6 템플릿으로 **§8 실행 로그에 append**. 판정(Pass/Knee/Breaking) 명시
8. **정리** — `terraform destroy` (NAT Gateway 유휴 비용 차단)

> 구성이 바뀌면 `topology.html`도 재생성한다 (mermaid-diagram 스킬).

---

## 8. 실행 로그

> 실측할 때마다 §6 템플릿으로 아래에 append.

_(아직 실행 전)_
