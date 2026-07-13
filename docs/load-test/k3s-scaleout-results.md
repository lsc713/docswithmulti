# k3s 수평 스케일아웃 — 검증 실험 5종 결과 (2026-07-13)

> 리그: 멀티노드 k3s(server1+agent3, arm64) · 앱 Deployment(payment×3/risk×2/merchant-limit×2/order×2) · Kafka 3-broker(Strimzi) · Redis · Traefik 인그레스 · MySQL×3 외부 고정.
> 관련: [`capacity-planning.md`](./capacity-planning.md)(SLO 봉투·병목) · 스펙 `docs/superpowers/specs/2026-07-13-k3s-scaleout-design.md` §6 · Phase A(리그)·B(락 하드닝) 머지됨.
> 방법론: measure-first, fresh 키 재시딩(리플레이 방지), **유리한 숫자가 아니라 무엇을 숨기는지까지** 기록.
> **③·⑤는 격리 리그로 재측정**(2026-07-13): payment **전용 노드 풀 ×3**(c7g.xlarge, taint+nodeSelector, 노드당 1, Guaranteed QoS) + workload 풀 ×3(m7g.xlarge, Kafka/risk/merchant/order/redis) → CPU noisy-neighbor 제거. 초기 confounded 결과는 아래 각 절에 정정 표기.

## 판정표

| # | 실험 | 성공기준 | 실측 | 판정 |
|---|---|---|---|---|
| ① | 스케줄러 락 안전성 | 이중발행 0 | 5 outbox 이벤트 → Kafka **정확히 5 메시지**(중복0) · outbox 5행 PUBLISHED once | ✅ PASS |
| ② | 파드 간 멱등 | 이중취소 0 | 따닥 → cancel_request **1건 COMPLETED·10000**(UK exactly-once) | ✅ PASS(핵심) / ⚠️ 발견 |
| ③ | N-replica 천장 불변 | ×1≈×3 무릎 | 격리: ×1 무릎~220 · ×3~260(3배 아님) · **병목=payment_db 2vCPU**(CPU95%/iowait26%/커밋대기 6/9) | ✅ **PASS(격리 재측정)** |
| ④ | 가용성(HA) | 노드 장애 시 무중단 | drain 중 **fail 0%** · p95 블립 1139ms · self-heal 3/3 | ✅ PASS |
| ⑤ | 무중단 롤링배포 | 5xx=0 | preStop A/B: OFF 0.7%·max30s → **ON 0%·max1.2s** | ✅ **PASS(preStop)** |

---

## ① 스케줄러 락 안전성 — PASS

merchant 한도 5회 업데이트 → outbox 5행. merchant-limit **×2** 파드에서 `merchant.limit.updated` 발행 결과:
- Kafka 파티션 오프셋 합 = **5**(0:2·1:1·2:2) — 중복 0.
- `limit_event_outbox` 상태: PUBLISHED 5행(각 1회).

**Phase B Redisson RLock 하드닝(소유권 unlock + 워치독)이 N=2에서 exactly-once 발행을 보장.**

> payment recovery 스케줄러(×3)는 `대상=0건` no-op이라 락을 µs만 쥐고 즉시 해제 → 스태거된 tick들이 무경합 획득(3파드 각자 실행). **락 오작동 아님** — 경합이 없으면 "1파드/tick"이 관측되지 않을 뿐. 실작업 있는 outbox의 exactly-once가 실증.

## ② 파드 간 멱등 — PASS(핵심) + 정직한 발견

동일 취소를 Traefik에 **동시 2회**(다른 payment 파드 착지) → DB: `cancel_request` **1건 COMPLETED·취소액 10000·payment CANCELLED**. `cancel_request(payment_id, request_hash)` UK가 이중취소를 물리적으로 차단.

**⚠️ 발견**: 동시 레이스의 **패자가 500**(INTERNAL_ERROR) 반환 — 정확성(무이중)은 UK가 보장하나, DuplicateKey 위반을 **멱등 응답(200)으로 변환하지 않음**. 단일 인스턴스에선 안 드러나던 멀티파드 엣지케이스. **향후 개선**: PENDING INSERT의 DuplicateKey를 잡아 진행 중/완료 결과를 멱등 반환.

## ③ N-replica 천장 — 격리 재측정으로 원인 규명 (헤드라인 정정)

**정정 요지: 이전 초안의 "210에서 ×1 221ms vs ×3 53ms → replica가 천장↑(반증)"은 아티팩트였다.** 당시 payment 파드가 Kafka broker와 같은 노드에서 **CPU limit 없이 경합**(noisy-neighbor)해 단일 파드가 CPU 굶주림으로 부풀려진 것. 전용 노드 풀 + Guaranteed QoS로 격리 재측정하니 그림이 뒤집혔다.

open-model 도착률 스윕(`slo-arrival.js`, Traefik 경유), 격리 리그 payment ×1 vs ×3:

| rate | ×1 p95 | ×3 p95 |
|---|---|---|
| 190~210 | 51~57ms | 57ms (동일) |
| 230 | **2131ms**(절벽) | — |
| 250 | — | 59ms(단독)~808ms |
| 290 | — | **4344ms**(절벽) |

- **격리하니 ×1도 210을 57ms로 여유 처리** → "×1이 210에서 이미 포화(221ms)"는 거짓. 무릎: **×1 ~220 · ×3 ~260**.
- **replica는 무릎을 3배가 아니라 ~220→~260(약 18%)만 밀었다** → 앱 티어는 천장이 아니다. 원래 성공기준 "×1≈×3 무릎"이 **오히려 지지**됨(confounded 런이 이를 거짓 반증했던 것).

**진짜 병목 = payment_db (mysql-payment, m7g.large 2 vCPU).** 4단 진단으로 확정:

| 개입 | 검증한 가설 | 결과(무릎) | 판정 |
|---|---|---|---|
| payment ×1→×3 | 앱 Hikari 풀 | ~220→~260(3배 아님) | 앱 아님 |
| `innodb_flush_log_at_trx_commit` 1→2 | payment_db fsync 내구성 | 그대로(~250) | fsync **단독** 아님 |
| merchant 10→1000 | `merchant_cancel_usage` 행 락 경합 | 그대로(~250) | 행 락 아님 |
| **250rps 중 CPU 스냅샷** | — | **payment_db 컨테이너 CPU 94.98% · iowait 25.8% · softirq 19.4% · "waiting for handler commit" 6/9 스레드** | 🔴 **payment_db 포화** |

대조군(동시 스냅샷): risk_db 50% idle(CPU 35%) · risk 파드 226m+178m · payment 파드 각 1.1~1.4/3코어 · merchant-limit 4~5m — **전부 여유. 오직 payment_db만 포화.** 2 vCPU 박스가 취소당 다건 statement(TX1/2/3 + `SELECT…FOR UPDATE` 재조회 + 커밋)를 처리하며 **CPU(softirq 20% = 매 statement 네트워크 인터럽트) + 커밋 I/O(iowait 26%) 혼합**으로 ~250rps에서 한계.

**개입 확증(관찰→개입) — payment_db vCPU 2→4배:** mysql-payment를 m7g.large(2vCPU)→**m7g.xlarge(4vCPU)**로 온라인 리사이즈 후 ×1 재측정:

| rate | 2vCPU ×1 | 4vCPU ×1 |
|---|---|---|
| 210 | 57ms(OK) | 201ms<sup>†</sup>(OK) |
| 230 | **2131ms(절벽)** | — |
| 270 | — | **3614ms(절벽)** |

무릎이 **~220→~260으로 이동(개입으로 천장이 실제 움직임 = payment_db가 벽임을 인과 확정)**. 단 vCPU 2배 투입 대비 **~15%만 상승(sub-proportional)** → payment_db는 **CPU 단독이 아니라 CPU+iowait+커밋 혼합** 바운드. flush=2·(예상)io2·vCPU **어느 단일 노브도 비례 해소 못 함**이 전부 정합. <sup>†</sup>4vCPU 런은 컨테이너 재생성으로 **InnoDB 버퍼풀 콜드**(210 p95 57→201ms는 워밍업 지터) → 무릎은 다소 저평가 가능. **진짜 해법: 더 큰 인스턴스 클래스(CPU+RAM+IOPS 동반) + 취소당 커밋/round-trip 감축**의 병행(단일 노브 아님).

**io2/fsync 함의(사전 확증으로 판정):** flush=2가 무릎을 거의 못 올린 것은 payment_db가 **fsync 단독이 아니라 CPU/softirq도 함께 바운드**이기 때문. → **io2(디스크 지연만 개선)로는 부분 해소뿐**. 진짜 레버는 **payment_db vCPU 증설**(m7g.large 2→xlarge 4)과 **취소당 커밋/round-trip 감축**(statement 수 축소 → softirq·커밋 동반 감소). ‘공짜 확증(flush 토글) → 하드웨어’ 순서 덕에 헛돈(io2) 안 씀.

**정직 정정 — 크로스-리그(load-test↔k3s) 절대 비교는 하지 않는다(불공정):** warm 단건 취소는 두 리그 다 ~43ms 유사. CPU(pod vs EC2)·홉 배치 상이로 절대 천장 숫자는 비교 불가 — 신뢰하는 건 **리그 내부 ×1 vs ×3 상대차 + payment_db 포화 규명**뿐.

## ④ 가용성(HA) — PASS

100rps 지속 중 payment 파드가 얹힌 agent 노드 `drain`(payment 파드 + kafka-broker-0 evicted):
- **fail 0%** — 실패 요청 0. 남은 2 payment 파드가 트래픽 흡수, **무중단**.
- p95 **1139ms**(정상 61ms 대비 급등) + dropped 61 — 순간 1/3 용량 상실의 지연 블립.
- self-heal: evicted 파드가 노드11에 재스케줄 → payment **3/3** 복귀. Kafka도 3브로커 복제(RF3/minISR2)로 브로커 1 상실에도 생존.

**노드 장애 = 요청 실패 0 + 짧은 지연 블립 + 자동 복구.** anti-affinity 분산이 단일 노드 장애를 견딤.

## ⑤ 무중단 롤링배포 — PASS (preStop, A/B 인과 증명)

**초기 PARTIAL(fail 1%·max 30s)을 preStop 훅으로 해소하고, 격리 리그에서 A/B로 인과를 못박았다.**

**배포 데드락 발견(전용 풀 부작용):** 전용 노드 정확히 3개 + `maxSurge:1/maxUnavailable:0` + required anti-affinity(노드당 1)면 surge 파드가 갈 노드가 없어 **Pending 영구 정지** → 롤아웃이 한 발도 못 나감(옛 파드가 안 죽어 fail 0%로 보이나 **preStop 발동조차 안 됨**). → **`maxSurge:0/maxUnavailable:1`**로 교정(옛 파드 1개씩 종료 → 빈 노드 재생성, required anti-affinity와 충돌 없음).

교정 후 150rps 지속 부하 중 `rollout restart`, preStop **A/B**:

| preStop | fail% | max | dropped | p50/p95 |
|---|---|---|---|---|
| **OFF** | 0.7% | **30,018ms** | 50 | 43/51ms |
| **ON**(`sleep 8`) | **0%** | **1,218ms** | 0 | 43/51ms |

**인과 확정:** Service 엔드포인트 제거와 SIGTERM 레이스로, OFF는 종료 중 파드로 라우팅된 요청이 gracePeriod(30s)까지 매달려 실패(이전 PARTIAL의 max 30s를 정확히 재현). **preStop drain(8s)이 엔드포인트 전파를 기다려 레이스를 소거** → fail 0%·max 1.2s. p50/p95는 OFF·ON 동일 = preStop은 **꼬리(tail)만** 잡는다. graceful shutdown 단독으론 못 덮던 레이스를 preStop이 덮음을 대조로 증명.

---

## 종합

- **정합성(①②④)은 견고**: 분산락 exactly-once, DB UK 이중취소0, 노드장애 무중단. 스케일아웃 안전.
- **격리 재측정이 confounded 결론을 뒤집음**:
  - ③ "replica는 천장 못 올림" → 격리로 **입증**(무릎 ~220→~260, 3배 아님). 초기 '반증(×3 53ms)'은 **noisy-neighbor CPU 아티팩트**였다. 진단 사다리(×3·flush=2·merchant1000 전부 무효 → CPU 스냅샷)로 **병목=payment_db 2vCPU**(CPU95%/iowait26%/커밋대기 6/9) 확정. **io2는 부분 해소뿐** — 레버는 DB vCPU 증설 + 커밋/statement 감축.
  - ⑤ "graceful=무중단" → preStop **A/B로 인과 확정**(OFF 0.7%·max30s → ON 0%·max1.2s). 부수 발견: 전용풀+surge 롤아웃 데드락 → `maxSurge:0/maxUnavailable:1` 교정.
- **멀티파드·전용풀에서만 드러난 엣지 3건**: ② 동시 UK 레이스 500 · ⑤ 배포 엔드포인트 레이스 · ⑤ 전용풀 surge 데드락. 단일 인스턴스 테스트가 못 잡던 것 — 스케일아웃 검증의 값.
- 후속 개선 후보: ② DuplicateKey 멱등 변환 · **③ payment_db vCPU 증설(m7g.large→xlarge) + 취소당 커밋 감축** · ⑤ 매니페스트에 preStop + `maxSurge:0/maxUnavailable:1` 반영.
