# Scale 블로그 시리즈 집필 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (권장) 또는 superpowers:executing-plans 로 태스크별 실행. 스텝은 체크박스(`- [ ]`)로 추적.

**Goal:** k3s 수평 스케일아웃 여정을 다루는 `Scale` 블로그 시리즈(2막 9편) + 앵커노트 확장 + 인덱스 등재를 완성한다.

**Architecture:** 각 편은 Obsidian vault의 마크다운 1파일. 서사 중심 + 개념은 앵커노트 링크. 근거 수치는 `docs/load-test/k3s-scaleout-results.md`에서 그대로 인용(과장 금지). 앵커노트를 먼저 확장(편들이 링크하므로) → 편 00~08 → 인덱스/브릿지.

**Tech Stack:** Markdown (Obsidian), callout 블록(`[!summary]`/`[!info]`), 위키링크 `[[..]]`.

## Global Constraints

- **Vault 경로:** 편 = `<VAULT>/Posts/Scale/NN.제목.md`, 앵커노트 = `<VAULT>/Posts/Notes/쿠버네티스 정리.md`, 인덱스 = `<VAULT>/index.md`. `<VAULT>` = `/Users/juho/Library/Mobile Documents/iCloud~md~obsidian/Documents/다이어리/public`.
- **언어:** 한국어. **제목:** 반전 단정형(Load 톤: "데드락인 줄 알았다").
- **수치 정확성:** 모든 실측 숫자는 `docs/load-test/k3s-scaleout-results.md`와 **정확히 일치**. 미검증/과장 주장 금지(Load 정직성 기준). 추론은 "추론"으로 표기.
- **편 필수 블록:** 상단 `> [!summary] 핵심\n> <한 줄>`, 하단 아래 `[!info] 시리즈` 블록(막·앞뒤편·앵커노트).
- **`[!info] 시리즈` 블록 템플릿(모든 편 동일, 현재 편만 굵게):**
  ```
  > [!info] Scale 시리즈 — k3s 수평 스케일아웃
  > **1막 여러 대로 안전하게** · [[00.수직 다음, 수평으로]] · [[01.자립 리그를 k3s로 깔다]] · [[02.파드가 늘면 뭐가 깨지나]] · [[03.노드가 죽어도]] · [[04.배포가 조용히 터진다]]
  > **2막 늘리면 정말 빨라지나** · [[05.replica가 천장을 올린 줄 알았다]] · [[06.4단 진단 끝에 payment_db]] · [[07.운영 함정]] · [[08.매니페스트 행단위 해부]]
  > ← [[이전편]] · [[다음편]] → · 개념: [[쿠버네티스 정리]]
  ```
- **개념은 인라인 최소화** → `[[쿠버네티스 정리]]` 링크. 그 편의 논지에 필요한 만큼만 설명.
- **길이:** Load 편 분량 참고(중편, ~150~250줄). 코드/YAML 인용은 실제 파일에서.
- 새 측정·코드 변경 없음. Load 편 본문 개작 금지(브릿지 링크만).

---

## File Structure

- Create: `Posts/Scale/00.수직 다음, 수평으로.md` ~ `08.매니페스트 행단위 해부.md` (9편)
- Modify: `Posts/Notes/쿠버네티스 정리.md` (개념 확장)
- Modify: `index.md` (Scale 시리즈 블록 등재)
- Modify(최소): `Posts/Load/22.같은 190인데 p95가 20배 달랐다.md`, `Posts/Load/13~15` (Scale로 전방 링크 1줄 — 선택)

---

### Task 1: 앵커노트 확장 — 쿠버네티스 정리.md

**Files:** Modify `<VAULT>/Posts/Notes/쿠버네티스 정리.md`

**이유:** 편들이 이 노트를 링크하므로 먼저 완성.

- [ ] **Step 1: 현재 노트 읽기** — 기존 헤딩(Pod·Deployment·RollingUpdate·HPA·Service) 파악, 톤 맞추기.
- [ ] **Step 2: "Service 종류 + Ingress" 섹션 추가** — 4종 표: ClusterIP(내부 전용, risk/merchant-limit/order/redis/kafka-bootstrap), NodePort(전 노드 포트 30000~32767, 우리는 merchant-limit-np:31574 시드용), LoadBalancer(k3s는 klipper-lb/svclb로 노드:80, Traefik이 이 타입), ExternalName(미사용). Ingress = L7 라우팅 규칙(Service 아님), Traefik이 path `/`→payment Service.
- [ ] **Step 3: "Control plane 컴포넌트" 섹션 추가** — 표: kube-apiserver(:6443 정문)·kube-scheduler(배치 결정, nodeSelector/taint/anti-affinity)·controller-manager(Deployment→RS→Pod 루프)·datastore(k3s=SQLite 단일서버)·kubelet(전 노드, containerd 실행)·kube-proxy(전 노드, iptables L4 분산)·flannel(CNI, VXLAN 10.42.0.0/16)·CoreDNS(서비스명→ClusterIP)·Traefik+klipper-lb. 각 "우리 케이스" 한 줄.
- [ ] **Step 4: "Strimzi — Kafka 오퍼레이터" 섹션 추가** — Operator 패턴, CRD(Kafka·KafkaNodePool·KafkaTopic) 선언→reconcile, StatefulSet 수동관리 대체. 우리: 3-broker KRaft RF3/minISR2 + 4토픽 선언 배포.
- [ ] **Step 5: "관련 노트" 갱신** — `[[결제 취소 멱등성 설계]]`, Scale 시리즈(특히 08 매니페스트) 상호링크 추가.
- [ ] **Step 6: 검증** — 헤딩 구조 일관, 우리 실제 값(31574·10.42.0.0/16·SQLite·3-broker) 정확, 링크 오타 0.

---

### Task 2: 00.수직 다음, 수평으로 (서문)

**Files:** Create `<VAULT>/Posts/Scale/00.수직 다음, 수평으로.md`

- [ ] **Step 1: `[!summary]`** — "한 대를 쥐어짜 ~220rps DB 벽까지 갔다(Load). 이제 여러 대로 늘린다 — 안전한가, 정말 빨라지나."
- [ ] **Step 2: 본문 beats** — (1) Load 요약: 커밋수·fsync가 벽, ~220rps, 한 토폴로지. (2) 남은 두 질문: 정합성/가용성(안전) + 스케일 천장(속도). (3) k3s 선택 이유(경량 쿠버, 자립 리그로 오케스트레이션 실측). (4) 이 시리즈 지도(1막/2막).
- [ ] **Step 3: 링크** — Load `[[22.같은 190인데 p95가 20배 달랐다]]` 역참조, `[!info] 시리즈` 블록(이전편 없음, 다음 `[[01...]]`).
- [ ] **Step 4: 검증** — Load 수치 인용 정확, 블록 형식.

---

### Task 3: 01.자립 리그를 k3s로 깔다 (인프라)

**Files:** Create `<VAULT>/Posts/Scale/01.자립 리그를 k3s로 깔다.md`

- [ ] **Step 1: `[!summary]`** — "server1 + agent + 외부 DB 3대. Strimzi가 Kafka를, Traefik이 입구를, flannel이 파드망을."
- [ ] **Step 2: 본문 beats** — 노드 구성(server c7g.large taint + agent + 외부 DB .30/.31/.32 + k6/obs; 격리벤치 땐 pay×3 c7g.xlarge + work×3 m7g.xlarge). Strimzi로 Kafka 3-broker(KRaft RF3/minISR2) 선언 배포. Traefik :80. flannel VXLAN 10.42.0.0/16. datastore SQLite. **외부 DB는 클러스터 밖 사설 IP 직결(ConfigMap 주입)=병목 통제변수.** 왜 self-hosted k3s인가(관리형 대신 자립).
- [ ] **Step 3: 개념 링크** — `[[쿠버네티스 정리]]`(control plane·Strimzi·Service/Ingress). 인라인은 흐름에 필요한 만큼만.
- [ ] **Step 4: 도식 링크** — `docs/architecture/k3s-topology.html` 참조.
- [ ] **Step 5: `[!info]` 블록 + 검증** — 값(3-broker·10.42·31574 등) 정확.

---

### Task 4: 02.파드가 늘면 뭐가 깨지나 (멀티파드 정합성)

**Files:** Create `<VAULT>/Posts/Scale/02.파드가 늘면 뭐가 깨지나.md`

- [ ] **Step 1: `[!summary]`** — "N>1에서 새로 생기는 3가지 깨짐과 그 방패: UK·분산락·dedup."
- [ ] **Step 2: 본문 beats** — 문제→기제 3종: ① 따닥 다른 파드 착지→이중취소: `request_hash=SHA-256(paymentKey+정렬 paymentItemIds)`, `cancel_request(payment_id,request_hash)` UK. ② `@Scheduled` 폴러 N중복 발행: Redisson RLock(`tryLock(0, 워치독)`, 소유권 unlock)→exactly-once. ③ Kafka 재분배 재처리: `processed_cancel_event` UK dedup. **실측:** exp① 5 이벤트→Kafka 정확히 5(중복0); exp② 따닥→cancel_request 1건 COMPLETED. **정직한 발견:** exp② 레이스 패자 500(DuplicateKey 멱등 미변환) — 단일 인스턴스가 못 잡던 엣지.
- [ ] **Step 3: 링크** — `[[동시성 제어 4종 — 락 결정 트리]]`, `[[결제 취소 멱등성 설계]]`.
- [ ] **Step 4: `[!info]` + 검증** — 결과문서 §①②와 수치 일치.

---

### Task 5: 03.노드가 죽어도 (HA)

**Files:** Create `<VAULT>/Posts/Scale/03.노드가 죽어도.md`

- [ ] **Step 1: `[!summary]`** — "노드 한 대를 drain — 실패 0%, 짧은 블립, 자동 복구."
- [ ] **Step 2: 본문 beats** — 100rps 중 payment 얹힌 agent drain(payment+kafka-broker-0 evict): **fail 0%**, p95 블립 **1139ms**(정상 61ms), dropped 61. 남은 2파드 흡수=무중단. self-heal: 재스케줄→payment 3/3 복귀. Kafka RF3/minISR2로 브로커1 상실 생존. 기제: anti-affinity(노드당1)+Deployment self-heal.
- [ ] **Step 3: 링크/검증** — `[[쿠버네티스 정리]]`, 결과문서 §④ 일치.

---

### Task 6: 04.배포가 조용히 터진다 (무중단 롤링)

**Files:** Create `<VAULT>/Posts/Scale/04.배포가 조용히 터진다.md`

- [ ] **Step 1: `[!summary]`** — "롤아웃이 멈춘 줄도 몰랐다. preStop을 켜니 꼬리가 30초→1.2초."
- [ ] **Step 2: 본문 beats** — (1) surge 데드락: 전용 노드 3개 + `maxSurge:1/maxUnavailable:0` + required anti-affinity → surge 파드 갈 노드 없어 Pending, 롤아웃 정지(옛 파드 안 죽어 fail 0 착시). → `maxSurge:0/maxUnavailable:1` 교정. (2) preStop A/B(150rps 중 rollout restart): **OFF fail 0.7%·max 30,018ms·dropped 50**(엔드포인트 제거 vs SIGTERM 레이스), **ON(sleep8) fail 0%·max 1,218ms·dropped 0**. p50/p95 동일=preStop은 꼬리만.
- [ ] **Step 3: 링크/검증** — `[[쿠버네티스 정리]]`(RollingUpdate), 결과문서 §⑤ 일치. 1막 마지막 편(2막으로 넘어가는 훅 한 줄).

---

### Task 7: 05.replica가 천장을 올린 줄 알았다 (반전 헤드라인)

**Files:** Create `<VAULT>/Posts/Scale/05.replica가 천장을 올린 줄 알았다.md`

- [ ] **Step 1: `[!summary]`** — "×3이 ×1보다 4배 빨랐다 — 격리해보니 그 차이가 통째로 착시였다."
- [ ] **Step 2: 본문 beats** — (1) 공유리그: 210에서 ×1 **221ms** vs ×3 **53ms** → "replica가 천장↑(반증)" 결론. (2) 의심: payment 파드가 Kafka broker와 CPU 공유·limit 미설정. (3) 전용 노드풀+Guaranteed QoS 격리 재측정 → **×1도 210=57ms, ×3도 57ms(동일)**. 무릎 ×1 ~220 · ×3 ~260(3배 아님). (4) 반전: "replica 천장↑"은 **noisy-neighbor CPU 아티팩트**, 원 가설(앱 replica는 천장 못 올림)이 오히려 입증. 정정이 정정을 뒤집음.
- [ ] **Step 3: 링크** — `docs/architecture/k3s-topology-benchmark.html` 참조, 다음편(진단)으로 훅.
- [ ] **Step 4: `[!info]` + 검증** — 결과문서 §③(반전) 수치 일치, "격리 vs 공유" 대비 명확.

---

### Task 8: 06.4단 진단 끝에 payment_db (근본원인)

**Files:** Create `<VAULT>/Posts/Scale/06.4단 진단 끝에 payment_db.md`

- [ ] **Step 1: `[!summary]`** — "×3·flush·merchant를 다 바꿔도 벽은 그대로. 스냅샷을 찍으니 payment_db 한 놈만 빨갰다."
- [ ] **Step 2: 본문 beats(진단 사다리)** — ① ×1→×3 무효(무릎 ~220→~260, 3배 아님)→앱 아님. ② `innodb_flush_log_at_trx_commit` 1→2 무효(무릎 ~250)→fsync 단독 아님. ③ merchant 10→1000 무효(250 cliff)→행 락 아님. ④ 250rps CPU 스냅샷: **payment_db(2vCPU)만 포화**(컨테이너 CPU **94.98%**·iowait **25.8%**·softirq **19.4%**·"waiting for handler commit" **6/9** 스레드), 대조군(risk_db 50%idle·risk파드 226m·payment파드 1.3/3코어) 전부 여유. ⑤ vCPU 2→4 개입확증: 무릎 **~15%만↑**(sub-proportional)→**CPU+iowait+커밋 혼합** 바운드, 단일 노브 비례해소 불가. (개입 카비앗: 4vCPU 런 InnoDB 버퍼풀 콜드 워밍업 지터.)
- [ ] **Step 3: Load 브릿지** — Load에선 fsync/pool이 벽(DB CPU 60% idle)이라 했는데, 격리 k3s는 payment_db CPU 95%(혼합) — **같은 DB 벽이라도 리그가 다르면 병목 구성이 다르다.** `[[13.동기 홉인 줄 알았다]]`·`[[14.이력 3커밋을 1로]]`·`[[15.147을 220으로]]`·`[[InnoDB 내구성 — 커밋은 왜 비싼가]]` 링크.
- [ ] **Step 4: 검증** — 결과문서 §③ 개입확증 수치 일치, "추론(fsync)"과 "직접측정(replica-무관 천장)" 구분 유지.

---

### Task 9: 07.운영 함정 (k8s/AWS 운영 현실)

**Files:** Create `<VAULT>/Posts/Scale/07.운영 함정.md`

- [ ] **Step 1: `[!summary]`** — "38 vCPU가 떴는데 리사이즈는 거부당했다 — 쿼터 32의 버스트 착시."
- [ ] **Step 2: 본문 beats** — ① AWS On-Demand Standard vCPU 쿼터 **32**(ap-northeast-2, L-1216C47A, 기본5서 증설). 함대 38vCPU가 terraform apply 버스트 계량랙으로 통과했으나 이후 단발 start/리사이즈는 정확 재검으로 **VcpuLimitExceeded**. stopped 미집계→idle 노드 중지로 임시확보(pay-2·3 중지=8vCPU). ② DB stop/start(리사이즈)로 mysql 컨테이너 재생성→앱 파드 Hikari가 죽은 커넥션 물어 요청 즉시 실패(210 fail 99.9%)→**파드 rollout restart 필요**. ③ (선택) 오케스트레이션 함정: 동시 seed PREFIX(`date +%s`) 충돌, 콜드 버퍼풀 지터.
- [ ] **Step 3: 링크/검증** — 메모리 `loadtest-aws-run` 근거, 수치 정확.

---

### Task 10: 08.매니페스트 행단위 해부 (마무리·레퍼런스)

**Files:** Create `<VAULT>/Posts/Scale/08.매니페스트 행단위 해부.md`

- [ ] **Step 1: `[!summary]`** — "payment.yaml 한 줄씩 — 이 줄이 없으면 무엇이 터지나."
- [ ] **Step 2: 본문** — `infra/k8s/apps/payment.yaml` 인용 후 줄별 해설: `nodeSelector{pool:payment}`(전용풀)·`tolerations(role=payment:NoSchedule)`·`podAntiAffinity(required, 노드당1)`·`lifecycle.preStop(sleep8, →04 레이스)`·`resources(requests==limits=Guaranteed QoS, →05 격리)`·`strategy(maxSurge0/maxUnavailable1, →04 데드락)`·`readiness/liveness probe`·`envFrom(ConfigMap/Secret)`·`terminationGracePeriodSeconds`. 각 줄 "왜/없으면 뭐가 터지나" + 해당 편 역참조.
- [ ] **Step 3: 앵커노트 상호링크** — `[[쿠버네티스 정리]]`, 시리즈 마무리 한 문단.
- [ ] **Step 4: 검증** — 실제 payment.yaml과 일치(값·필드).

---

### Task 11: 인덱스 등재 + Load 브릿지

**Files:** Modify `<VAULT>/index.md`; (선택) `<VAULT>/Posts/Load/22.*.md`, `13~15.*.md`

- [ ] **Step 1: index.md에 Scale 블록 추가** — Load 블록 형식 참고, 2막 9편 목록 + 한 줄 소개(수평 스케일아웃).
- [ ] **Step 2: (선택) Load→Scale 전방 링크** — Load 22·13~15 하단에 "이 DB 벽을 수평에서 다시 만난다 → `[[06.4단 진단 끝에 payment_db]]`" 한 줄(본문 개작 아님).
- [ ] **Step 3: 검증** — 전 편 위키링크 해소(오타 0), 1막/2막 목록 순서 일치, index 렌더 확인.

---

## Self-Review

- **커버리지:** spec의 9편 + 앵커노트 + 인덱스 모두 태스크로 매핑됨(Task 1~11).
- **플레이스홀더:** 각 편 태스크에 인용할 실제 수치 명시(TBD 없음).
- **일관성:** `[!info]` 블록 템플릿·파일명·막 구성이 전 태스크 동일. Task 1(앵커노트) 선행으로 링크 깨짐 방지.
- **정직성 가드:** 모든 수치 "결과문서 일치" 검증 스텝 포함, 추론/측정 구분 명시.
