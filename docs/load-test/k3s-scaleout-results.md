# k3s 수평 스케일아웃 — 검증 실험 5종 결과 (2026-07-13)

> 리그: 멀티노드 k3s(server1+agent3, arm64) · 앱 Deployment(payment×3/risk×2/merchant-limit×2/order×2) · Kafka 3-broker(Strimzi) · Redis · Traefik 인그레스 · MySQL×3 외부 고정.
> 관련: [`capacity-planning.md`](./capacity-planning.md)(SLO 봉투·병목) · 스펙 `docs/superpowers/specs/2026-07-13-k3s-scaleout-design.md` §6 · Phase A(리그)·B(락 하드닝) 머지됨.
> 방법론: measure-first, fresh 키 재시딩(리플레이 방지), **유리한 숫자가 아니라 무엇을 숨기는지까지** 기록.

## 판정표

| # | 실험 | 성공기준 | 실측 | 판정 |
|---|---|---|---|---|
| ① | 스케줄러 락 안전성 | 이중발행 0 | 5 outbox 이벤트 → Kafka **정확히 5 메시지**(중복0) · outbox 5행 PUBLISHED once | ✅ PASS |
| ② | 파드 간 멱등 | 이중취소 0 | 따닥 → cancel_request **1건 COMPLETED·10000**(UK exactly-once) | ✅ PASS(핵심) / ⚠️ 발견 |
| ③ | N-replica 천장 불변 | ×1≈×3 무릎 | 210에서 ×1 **221ms** vs ×3 **53ms** | ❌ **반증** |
| ④ | 가용성(HA) | 노드 장애 시 무중단 | drain 중 **fail 0%** · p95 블립 1139ms · self-heal 3/3 | ✅ PASS |
| ⑤ | 무중단 롤링배포 | 5xx=0 | 롤링 중 **fail 1%** · p95 61ms · max 30s | ⚠️ PARTIAL |

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

## ③ N-replica 천장 불변 — 반증 (헤드라인)

open-model 도착률 스윕(`slo-arrival.js`, Traefik 경유), payment ×1 vs ×3:

| rate | ×1 p95 | ×3 p95 |
|---|---|---|
| 60~190 | 44~50ms | 42~47ms (동일) |
| **210** | **221ms**(엘보) | **53ms**(평탄) |

**예측("앱 replica는 DB 천장을 못 올린다") 반증** — 210에서 ×3이 명백히 우수. **단일 payment 파드의 Hikari 풀(10)이 210에서 병목**이었고, replica 추가가 풀 용량을 늘려 천장을 올렸다.

정직한 정제: k3s의 **빠른 in-cluster 홉**(취소당 ~45ms)이라 단일 파드 풀10 천장이 ~210. 옛 load-test 리그(크로스-EC2 느린 홉 ~330ms)에선 커넥션 점유가 커 ~190에서 막혀 "DB fsync 바운드"처럼 보였으나, **실은 상당 부분 풀-점유 바운드**였고 replica가 이를 완화한다(Little's law: throughput = pool ÷ occupancy, 파드마다 pool 추가). 진짜 DB 천장은 210 위(이번 스윕 미도달). → 블로그 22편·capacity의 "replica 무효"는 **DB-write-bound에 도달했을 때만** 성립.

## ④ 가용성(HA) — PASS

100rps 지속 중 payment 파드가 얹힌 agent 노드 `drain`(payment 파드 + kafka-broker-0 evicted):
- **fail 0%** — 실패 요청 0. 남은 2 payment 파드가 트래픽 흡수, **무중단**.
- p95 **1139ms**(정상 61ms 대비 급등) + dropped 61 — 순간 1/3 용량 상실의 지연 블립.
- self-heal: evicted 파드가 노드11에 재스케줄 → payment **3/3** 복귀. Kafka도 3브로커 복제(RF3/minISR2)로 브로커 1 상실에도 생존.

**노드 장애 = 요청 실패 0 + 짧은 지연 블립 + 자동 복구.** anti-affinity 분산이 단일 노드 장애를 견딤.

## ⑤ 무중단 롤링배포 — PARTIAL + 정직한 발견

100rps 지속 중 `rollout restart deploy/payment`(maxSurge1/maxUnavailable0):
- p50 41ms·p95 61ms — 99% 매끄러움.
- **fail 1%** · **max 30018ms**(=terminationGracePeriod 30s까지 걸린 요청 1건).

**5xx=0 아님.** graceful shutdown(25s drain)이 대부분 흡수하나, **Service 엔드포인트 제거와 SIGTERM 레이스**로 ~1% 요청이 종료 중 파드로 라우팅돼 실패. **진짜 무중단엔 preStop drain 훅**(파드가 엔드포인트에서 빠질 때까지 잠깐 더 살아있게)이 필요 — graceful shutdown 단독으론 그 레이스를 못 덮는다. 멀티파드에서만 드러나는 배포 엣지케이스.

---

## 종합

- **정합성(①②④)은 견고**: 분산락 exactly-once, DB UK 이중취소0, 노드장애 무중단. 스케일아웃 안전.
- **두 예측이 실측으로 정제/반증**:
  - ③ "replica는 천장 못 올림" → **반증**(풀-점유 바운드 구간에선 올림; DB-write-bound여야 무효).
  - ⑤ "graceful=무중단" → **부분 반증**(엔드포인트 레이스로 1% 실패; preStop 필요).
- **멀티파드에서만 드러난 엣지 2건**: ② 동시 UK 레이스 500, ⑤ 배포 엔드포인트 레이스. 단일 인스턴스 테스트가 못 잡던 것 — 스케일아웃 검증의 값.
- 후속 개선 후보: ② DuplicateKey 멱등 변환 · ⑤ preStop 훅 · ③ ×3를 더 높은 도착률로 밀어 진짜 DB 천장 규명.
