# k3s 스케일아웃 Phase C — 검증 실험 Implementation Plan (measure-first 런북)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`). 이 플랜은 코드가 아니라 **실측 런북** — 각 태스크 = 하나의 실험, "테스트"는 검증 명령/성공기준이다.

**Goal:** Phase A 리그 + Phase B 코드 위에서 스케일아웃의 5가지 주장을 **실측으로 증명**한다 — ① 스케줄러 락 안전성 ② 파드 간 멱등 ③ N-replica가 DB 천장(≈190rps)을 못 올림 + Traefik 오프셋 ④ 노드 장애 HA ⑤ 무중단 롤링배포.

**Architecture:** `infra/k3s-scaleout` 리그 재기동(Phase A 절차) + `infra/k8s` 매니페스트(Phase B 반영). k6가 Traefik(node:80) 경유 부하. 검증은 SSM으로 server 노드 `k3s kubectl` + k6 노드 부하.

**Tech Stack:** k3s · Traefik · k6(`k6/slo-arrival.js` 재사용) · SSM · Prometheus/Grafana(obs).

## Global Constraints

- **billable** — Task 1(apply)부터 과금. 완료/중단 시 `terraform destroy`(Task 7) 필수.
- 기존 `infra/load-test` 불변. 리그는 `infra/k3s-scaleout`(Phase A). 코드 변경 없음(순수 측정).
- **DB는 외부 고정**(병목 통제변수). replica 스케일은 앱 티어만.
- 실측 방법론(기존 시리즈 준수): 공정 비교 위해 관측 오버헤드 최소, 멱등 리플레이 방지(fresh 키), 결과는 유리한 숫자가 아니라 **무엇을 숨기는지까지** 기록.
- **이미지 전제**: `camelia9999/cancel-loadtest:merchant-limit-latest`가 **Phase B 락 하드닝 포함**해야 함(main 머지 후 CI 재빌드). Task 1에서 확인, 미반영 시 재빌드·재푸시 후 진행.

## 스코프 노트

스펙 §6의 실험 5개. Phase A(리그 골격)·Phase B(락 하드닝·graceful shutdown) 완료 전제. 실험 1의 "락 off→N-발화 재현"은 **락 우회 플래그가 없어**(코드 미존재) 기본은 **positive 증명**(락 ON에서 N replica가 tick당 1개만 발화)으로 하고, negative는 optional(§실험1 참고).

## File Structure

```
(신규 산출물)
docs/load-test/k3s-scaleout-results.md   ← 5실험 결과 문서(Task 7)
(재사용, 변경 없음)
infra/k3s-scaleout/*                       terraform 리그
infra/k8s/*                                매니페스트
k6/slo-arrival.js                          open-model 스윕(exp3)
```

---

### Task 1: 리그 기동 + 배포 + k6 부트스트랩 + seed [과금 시작]

**Files:** (실행 태스크 — 코드 변경 없음)

**Interfaces:**
- Produces: 전 파드 Ready 클러스터 + Traefik 진입점(agent node IP:80) + k6 노드에 k6+repo+slo-arrival + payment_db 시드.

- [ ] **Step 1: 이미지 Phase B 반영 확인**

Run:
```bash
curl -s "https://hub.docker.com/v2/repositories/camelia9999/cancel-loadtest/tags?page_size=100" \
  | grep -o '"name":"merchant-limit-latest","[^}]*"last_updated":"[^"]*"'
```
Expected: `merchant-limit-latest`의 `last_updated`가 Phase B 머지(2026-07-13) 이후. 이전이면 CI 재빌드 트리거(빈 커밋 push 또는 workflow_dispatch) 후 대기.

- [ ] **Step 2: 리그 기동 (Phase A 절차 재현)**

`infra/k3s-scaleout`에서 Phase A 플랜(`2026-07-13-k3s-scaleout-phase-a.md`) **Task 2·2.5·3·4·5**를 순서대로 실행:
```bash
cd infra/k3s-scaleout && AWS_REGION=ap-northeast-2 terraform apply -auto-approve
# k3s 설치(부팅 curl 대신 SSM 명시 설치 — Phase A 학습): server → agents
#   (Phase A Task 2 Step 3의 SSM k3s 설치 스니펫 사용, token은 terraform.tfvars)
# 외부 mysql×3 배포(Phase A Task 2.5), Strimzi+Kafka(Task 3), Redis/Config+앱(deploy.sh)
```
Expected: `k3s kubectl get pods` — payment 3/3·risk 2/2·merchant-limit 2/2·order 2/2·redis·Kafka 3-broker 모두 Running. Traefik LB EXTERNAL-IP=node IPs.

- [ ] **Step 3: k6 노드 부트스트랩 + payment_db 시드**

k6 노드(10.0.1.20)에 k6 arm64 + repo + mysql client 부트스트랩(load-test 리그의 부트스트랩 패턴 재사용, SSM base64). 그리고 slo-arrival 용 시드:
```bash
# k6 노드에서 (SSM): merchant 10 생성(in-cluster merchant-limit는 외부 미노출 →
#   merchant는 SQL 직접 시드 or Traefik 경유 X → merchant_db(10.0.1.32:3306) 직접 INSERT)
# payment 100k 시드: mysql -h10.0.1.30 (k6/seed/seed.sh, MYSQL_HOST=10.0.1.30 MYSQL_PORT=3306)
SEED_COUNT=100000 MYSQL_HOST=10.0.1.30 MYSQL_PORT=3306 bash k6/seed/seed.sh
```
> 주의: seed.sh는 merchant를 HTTP(merchant-limit)로 만든다 — k3s에선 merchant-limit이 ClusterIP라 k6 노드에서 직접 도달 불가. **merchant는 merchant_db(10.0.1.32:3306)에 SQL 직접 시드**하거나, server 노드에서 in-cluster curl 파드로 생성 후 그 merchantId로 payment 시드. (Phase A 스모크의 in-cluster 방식 재사용.)

Expected: `k6/seed/paymentKeys.json`에 100k 신규 키(pool[0] 미취소 게이트 통과).

---

### Task 2: 실험 ① 스케줄러 락 안전성

**성공기준:** payment ×3에서 recovery tick당 **정확히 1개 파드만** 실제 작업 수행 · merchant-limit 하드닝 락으로 **이중발행 0**.

- [ ] **Step 1: payment recovery 스케줄러 — tick당 1파드만 발화 관측**

payment ×3 상태에서 60초 이상 로그를 모아 recovery 스케줄러의 실작업 로그가 tick(60s)마다 **한 파드에서만** 찍히는지 확인(나머지는 "락 획득 실패 skip"):
```bash
# server 노드(SSM): 최근 3분 payment 로그에서 pending/processing-recovery 실행 라인 파드별 집계
K="/usr/local/bin/k3s kubectl"
$K logs -l app=payment --prefix --tail=500 --since=3m \
  | grep -E 'pending-recovery|processing-recovery' \
  | grep -vE '락 획득 실패' | sed 's#\[pod/\([^/]*\).*#\1#' | sort | uniq -c
```
Expected: 각 tick 시각에 대해 **실작업 라인이 파드 1개에만** 존재(동시 다발 없음). (recovery 대상 0건이어도 "실행 now=... 대상=0건" 라인이 한 파드에서만.)

- [ ] **Step 2: merchant-limit 하드닝 — 이중발행 0**

merchant 한도 변경으로 outbox 이벤트를 유발하고, merchant-limit ×2에서 `merchant.limit.updated`가 **정확히 1회** 발행되는지 확인:
```bash
# in-cluster curl 파드로 merchant 한도 update(outbox 행 생성) → merchant.limit.updated 발행 유발
$K run curlu --image=curlimages/curl --restart=Never -i --rm --command -- \
  curl -s -XPUT http://merchant-limit:8082/v1/merchants/2/cancel-limit -H 'Content-Type: application/json' -d '{"dailyLimit":900000000}'
sleep 12
# merchant-limit 로그: "Outbox 발행 완료. count=" 가 두 파드 합쳐 1회만
$K logs -l app=merchant-limit --prefix --since=2m | grep 'Outbox 발행 완료' 
# order/risk consumer 쪽 merchant.limit.updated 처리도 1회(중복 없음) 확인(risk 로그)
```
Expected: `Outbox 발행 완료` 로그가 **한 파드에서 1회**(양 파드 동시 발행 없음). risk 컨슈머가 중복 없이 1회 처리.
> (optional·negative) 락 off→N-발화 재현은 우회 플래그가 없어 생략. 필요 시 별도 소기능(락 bypass 플래그) 추가 후.

---

### Task 3: 실험 ② 파드 간 멱등

**성공기준:** 같은 취소 요청(동일 request_hash)을 Traefik으로 따닥 → 서로 다른 payment 파드에 착지해도 **취소 1건**(cancel_request UK), 이중취소 0.

- [ ] **Step 1: 신규 payment 1건 시드 + 따닥 동시 취소**

```bash
# fresh payment 시드(Phase A 스모크 방식) → pkey, paymentItemId 확보
# Traefik(node:80)로 동일 취소를 병렬 2회(&) 발사 — LB가 다른 파드로 분산
NODE=10.0.1.11
for i in 1 2; do curl -s -XPOST "http://$NODE/v1/payments/$PKEY/cancel" \
  -H 'Content-Type: application/json' \
  -d "{\"cancelItems\":[{\"paymentItemId\":$PIID}],\"cancelReason\":\"idem\"}" & done; wait
```
Expected: 두 응답 중 하나는 신규 취소(COMPLETED), 다른 하나는 멱등 응답(동일 cancelRequestId, COMPLETED) — **둘 다 200, cancelRequestId 동일**.

- [ ] **Step 2: DB로 이중취소 0 확인**

```bash
# payment_db: 해당 payment의 cancel_request COMPLETED 가 1건, cancel_amount 총합이 아이템 1개분(10000)
$K run mysqlv --image=mysql:8.0 --restart=Never -i --rm --command -- \
  mysql -h10.0.1.30 -upayment -ppayment payment_db -N -e \
  "SELECT COUNT(*), SUM(cancel_amount) FROM cancel_request WHERE payment_id=(SELECT id FROM payment WHERE payment_key='$PKEY') AND status='COMPLETED';"
```
Expected: `1  10000.00` (취소 1건, 이중차감 없음). 어느 파드가 처리했든 DB UK가 두 번째를 멱등 수렴.

---

### Task 4: 실험 ③ N-replica 천장 불변 + Traefik 오프셋

**성공기준:** payment ×1 vs ×3에서 open-model 무릎(p95<500ms 상한)이 **≈190rps로 동일**(DB바운드 증명). Traefik 홉 오프셋 정량화(직결 대비).

- [ ] **Step 1: payment ×1로 스케일 + slo-arrival 스윕(Traefik 경유)**

```bash
$K scale deploy/payment --replicas=1 && $K rollout status deploy/payment
# k6 노드: Traefik(node:80)를 타깃으로 slo-arrival 스윕
TARGET=aws PAYMENT_URL=http://10.0.1.11 \
  K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
  K6_PROMETHEUS_RW_TREND_STATS='p(50),p(95),p(99),max' \
  k6 run -o experimental-prometheus-rw k6/slo-arrival.js   # slo-result.json 회수
```
Expected: 무릎 ≈190rps(제출된 slo-result.json의 rate별 p95 곡선). **재시딩 후 pool[0] 기취소=0 게이트 준수**(리플레이 방지).

- [ ] **Step 2: payment ×3로 스케일 + 재시딩 + 동일 스윕**

```bash
$K scale deploy/payment --replicas=3 && $K rollout status deploy/payment
# fresh 재시딩(×1에서 키 소진) 후 동일 slo-arrival 스윕
```
Expected: 무릎 여전히 **≈190rps**(×1과 노이즈 범위 내 동일) → **앱 replica는 DB 천장을 못 올림** 증명. (올라가면 병목 재평가.)

- [ ] **Step 3: Traefik 오프셋 — 직결 vs 인그레스 p95 비교**

동일 도착률(예 100rps 단일 stage)에서 (a) Traefik(node:80) 경유 vs (b) payment ClusterIP 직결(server에서 port-forward 또는 in-cluster k6) p95 차이 측정 → 블로그 22편 "내부 서비스타임" 캐비앗의 오프셋 실측.
Expected: 오프셋 = 상수 수 ms 수준(무릎 위치 불변 재확인).

---

### Task 5: 실험 ④ 가용성(HA) — 노드 장애

**성공기준:** 정상 부하 중 payment 파드가 얹힌 agent 노드를 drain/terminate → Service 지속(타 파드가 계속 응답), 5xx 짧은 블립 후 self-heal 회복.

- [ ] **Step 1: 저부하 지속 중 노드 drain**

```bash
# k6: 100rps 고정 도착률 지속(constant-arrival-rate, 5분) 시작(백그라운드)
# 그 사이 payment 파드가 있는 agent 노드 하나 drain
$K get pod -l app=payment -o wide     # 대상 노드 식별
$K drain ip-10-0-1-1X... --ignore-daemonsets --delete-emptydir-data --force --grace-period=30
```
Expected: k6 지표에서 5xx가 **짧게 튐**(drain 순간 그 파드로 가던 연결) 후 0으로 복귀. k8s가 파드를 타 노드에 재스케줄(payment 3/3 회복). **회복 시간(5xx>0 지속) 측정.**

- [ ] **Step 2: 회복 확인 + uncordon**

```bash
$K get pod -l app=payment -o wide     # 재스케줄로 3개 Running 회복
$K uncordon ip-10-0-1-1X...
```
Expected: payment 3/3 Running(anti-affinity가 재배치), k6 성공률 정상 복귀.

---

### Task 6: 실험 ⑤ 무중단 롤링배포

**성공기준:** 정상 부하 중 `rollout restart` → **5xx=0**(graceful shutdown이 in-flight drain + readiness가 미준비 파드로 라우팅 차단).

- [ ] **Step 1: 저부하 지속 중 롤링 재시작**

```bash
# k6: 100rps 고정 도착률 지속(5분) 중
$K rollout restart deploy/payment
$K rollout status deploy/payment --timeout=180s   # maxSurge1/maxUnavailable0 롤링
```
Expected: 롤링 동안 k6 **http_req_failed(5xx) = 0**. (graceful=25s drain + readiness 프로브가 새 파드 준비 전 트래픽 차단 + terminationGracePeriod 30s.)

- [ ] **Step 2: 판정**

k6 요약에서 롤링 구간 5xx 카운트 확인.
Expected: 5xx 0(또는 극소수 → readiness/grace 튜닝 신호). Phase B graceful shutdown의 행동 검증 완료.

---

### Task 7: 결과 문서화 + destroy

**Files:**
- Create: `docs/load-test/k3s-scaleout-results.md`

- [ ] **Step 1: 5실험 결과 표로 정리**

`docs/load-test/k3s-scaleout-results.md`에 실험별 성공기준·실측치·판정 기록:
| 실험 | 성공기준 | 실측 | 판정 |
(① 락 tick당 1파드·이중발행0 / ② 멱등 1건·이중취소0 / ③ 무릎 ×1≈×3≈190·오프셋 Nms / ④ HA 회복 Ns / ⑤ 롤링 5xx=0). capacity-planning.md·블로그(22편 계열)에서 역링크.

- [ ] **Step 2: destroy (과금 정지)**

```bash
cd infra/k3s-scaleout && terraform destroy -auto-approve
```
Expected: `Destroy complete`, 인스턴스 0.

- [ ] **Step 3: Commit**

```bash
git add docs/load-test/k3s-scaleout-results.md
git commit -m "docs(load-test): k3s 스케일아웃 검증 실험 5종 결과 (락·멱등·천장불변·HA·롤링)"
```

---

## Self-Review

**Spec coverage (§6):** 실험 ①~⑤ → Task 2~6 각각 ✓. 결과 문서화·destroy → Task 7 ✓. 리그/시드 → Task 1 ✓.

**Placeholder scan:** `$PKEY`·`$PIID`·`ip-10-0-1-1X`는 런타임 값(획득 방법 명시). TBD 없음.

**측정 정직성 체크:** ③은 fresh 재시딩+게이트로 리플레이 방지(SLO 런 교훈), ①negative(off→N-fire)는 우회 플래그 부재로 positive만 — **한계를 명시**(숨기지 않음). ④/⑤는 저부하로 측정(고부하 중 노드 장애는 별개 축).

**의존:** merchant-limit 이미지가 Phase B 포함(Task 1 Step 1 게이트). slo-arrival은 `PAYMENT_URL`로 Traefik 타깃(config.js 오버라이드). k6 노드는 수동 부트스트랩(ssm-deploy 미프로비저닝 — load-test 리그와 동일).

---

## Execution Handoff

Phase C는 **billable**(리그 필요). Task 1(apply)부터 과금, Task 7 destroy 필수. Phase A/B 완료(main 머지) 전제.
