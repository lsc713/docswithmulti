# Scale 블로그 시리즈 설계 (k3s 수평 스케일아웃)

**작성:** 2026-07-13 · **상태:** 설계 확정, 실행 계획 대기

## 목표

Load 시리즈(수직 성능 해부, 한 토폴로지에서 ~220rps DB 벽 규명)의 후속으로, 결제 취소 MSA를 **k3s 멀티노드에 올려 수평 스케일아웃한 여정**을 다루는 블로그 시리즈 `Scale`을 만든다. 두 질문을 관통 축으로: **(1) 여러 대로 늘려도 안전한가(정합성·가용성·배포)**, **(2) 늘리면 정말 빨라지나(N-replica 천장 반전 → payment_db 규명)**. 새 측정 없이 이미 실측·정정된 문서를 근거로 쓴다.

## 시리즈 정체성

- **폴더:** `Posts/Scale` (Obsidian vault `다이어리/public/Posts/`, Load와 대칭 영문 단음절)
- **파일명:** `NN.반전단정형제목.md` (두 자리 넘버링, Load 관례 복제)
- **제목 톤:** 반전 단정형 (Load의 "데드락인 줄 알았다", "190은 용량이 아니었다" 계열)
- **한 줄 콘셉트:** "한 대를 쥐어짠 뒤(Load), 여러 대로 늘렸다 — 안전한가, 그리고 정말 빨라지나."
- **앵커 노트:** `Notes/쿠버네티스 정리.md` (개념 레퍼런스로 확장, 각 편에서 `[[쿠버네티스 정리]]` 링크)
- **구조:** 2막 · 9편(00~08), 번호 연속

## 편 구조 (2막 · 9편)

각 편: 서사 중심 + 그 맥락에 필요한 개념만(나머지는 앵커노트 링크). 상단 `[!summary] 핵심 한 줄`, 하단 `[!info] 시리즈`(1막/2막 두 줄) 블록.

### 1막 — 여러 대로 안전하게 (스케일아웃이 안 깨지나)

**00 수직 다음, 수평으로** (서문)
- Load 요약: 한 토폴로지에서 커밋수·fsync가 벽, ~220rps. 남은 질문 = "여러 대로 늘리면?"
- 두 축 선언(안전한가 / 빨라지나). k3s 선택 이유(경량 쿠버, 자립 리그).
- 링크: Load #22(수직 마무리) 역참조.

**01 자립 리그를 k3s로 깔다** (인프라·토폴로지)
- 노드: server1(c7g.large, taint) + agent(초기 3, 격리벤치 땐 pay×3 c7g.xlarge + work×3 m7g.xlarge) + 외부 DB 3대(.30/.31/.32) + k6 + obs.
- Strimzi로 Kafka 3-broker(KRaft, RF3/minISR2) 선언 배포. Traefik 인그레스 :80. flannel VXLAN 파드망 10.42.0.0/16. k3s datastore=SQLite(단일서버).
- 외부 DB는 클러스터 밖 사설 IP 직결(ConfigMap 주입) — 병목 통제변수로 고정.
- 개념 링크: `[[쿠버네티스 정리]]`(Service/Ingress/control plane/Strimzi).
- 근거: `docs/architecture/k3s-topology.html`, `infra/k3s-scaleout/instances.tf`, `infra/k8s/`.

**02 파드가 늘면 뭐가 깨지나** (멀티파드 정합성)
- N>1에서 새로 생기는 문제 3종과 기제:
  - 따닥 다른 파드 착지 → 이중취소: `request_hash=SHA-256(paymentKey+정렬 paymentItemIds)`, `cancel_request(payment_id, request_hash)` UK.
  - `@Scheduled` 폴러 N개 중복 발행: Redisson RLock(`tryLock(0, watchdog)`, 소유권 unlock) → exactly-once.
  - Kafka 재분배 재처리: `processed_cancel_event` UK dedup.
- 실측: exp① 5 outbox 이벤트 → Kafka 정확히 5(중복0). exp② 따닥 → cancel_request 1건 COMPLETED.
- 정직한 발견: exp② 동시 레이스 패자 500(DuplicateKey를 멱등 200으로 미변환) — 단일 인스턴스가 못 잡던 엣지.
- 개념 링크: `[[동시성 제어 4종 — 락 결정 트리]]`, `[[결제 취소 멱등성 설계]]`.
- 근거: 결과문서 §①②.

**03 노드가 죽어도** (HA)
- 100rps 지속 중 payment 파드 얹힌 agent `drain`(payment + kafka-broker-0 evict):
  - fail 0%, p95 블립 1139ms(정상 61ms), dropped 61 → 남은 2파드 흡수, 무중단.
  - self-heal: evict 파드 재스케줄 → payment 3/3 복귀. Kafka RF3/minISR2로 브로커1 상실 생존.
- 기제: payment anti-affinity(노드당1) + Deployment self-heal.
- 근거: 결과문서 §④.

**04 배포가 조용히 터진다** (무중단 롤링)
- surge 데드락 발견: 전용 노드 정확히 3개 + `maxSurge:1/maxUnavailable:0` + required anti-affinity → surge 파드 갈 노드 없어 Pending 영구정지(롤아웃 멈춤, 옛 파드 안 죽어 fail 0으로 착시). → `maxSurge:0/maxUnavailable:1` 교정.
- preStop A/B(150rps 부하 중 rollout restart):
  - OFF: fail 0.7%, max 30,018ms, dropped 50 (엔드포인트 제거 vs SIGTERM 레이스, gracePeriod 30s까지 매달림).
  - ON(`sleep 8`): fail 0%, max 1,218ms, dropped 0. p50/p95 동일 = preStop은 꼬리만 잡음.
- 개념 링크: `[[쿠버네티스 정리]]`(RollingUpdate).
- 근거: 결과문서 §⑤.

### 2막 — 늘리면 정말 빨라지나 (클라이맥스 = 반전)

**05 replica가 천장을 올린 줄 알았다** (반전 헤드라인)
- 공유리그 초기 결과: 210에서 ×1 221ms vs ×3 53ms → "replica가 천장↑(반증)" 결론.
- 의심: 그 차이가 오염 아닌가(payment 파드가 Kafka broker와 CPU 공유·limit 미설정).
- 전용 노드풀 + Guaranteed QoS로 격리 재측정 → **×1도 210을 57ms로 처리**, ×3도 57ms(동일). 무릎: ×1 ~220 · ×3 ~260(3배 아님).
- 반전: "replica가 천장 올림"은 **noisy-neighbor CPU 아티팩트**였고, 원 가설(앱 replica는 천장 못 올림)이 오히려 입증. 정정이 정정을 뒤집음.
- 근거: 결과문서 §③, `docs/architecture/k3s-topology-benchmark.html`.

**06 4단 진단 → payment_db** (근본원인)
- 진단 사다리(전부 payment 쪽 개입 → 무효 패턴):
  - ×1→×3 무효(무릎 ~220→~260, 3배 아님) → 앱 아님.
  - `innodb_flush_log_at_trx_commit` 1→2 무효(무릎 ~250 그대로) → fsync 단독 아님.
  - merchant 10→1000 무효(250 cliff 그대로) → 행 락 아님.
  - 250rps CPU 스냅샷: **payment_db(2vCPU)만 포화**(컨테이너 CPU 94.98%·iowait 25.8%·softirq 19.4%·"waiting for handler commit" 6/9 스레드). 대조군 risk_db 50%idle·risk파드 226m·payment파드 1.3/3코어 전부 여유.
  - vCPU 2→4 개입확증: 무릎 ~15%만↑(sub-proportional) → CPU+iowait+커밋 혼합 바운드. 단일 노브(vCPU/fsync/io2) 비례 해소 불가.
- **Load fsync와 재회(브릿지):** Load에선 fsync/pool이 벽(DB CPU 60% idle)이라 했는데, 격리 k3s에선 payment_db CPU 95%(혼합) — 같은 DB 벽이라도 리그가 다르면 병목 구성이 다르다.
- 개념 링크: `[[InnoDB 내구성 — 커밋은 왜 비싼가]]`. Load #13~15 역참조.
- 근거: 결과문서 §③(개입확증 포함).

**07 운영 함정** (k8s/AWS 운영 현실)
- AWS On-Demand Standard vCPU 쿼터=32(ap-northeast-2, L-1216C47A, 기본5에서 증설). 함정: 함대 38vCPU가 terraform apply 버스트 계량랙으로 통과했으나, 이후 단발 start/리사이즈는 정확 재검으로 거부(VcpuLimitExceeded). stopped 미집계 → idle 노드 중지로 임시확보.
- DB stop/start(리사이즈)로 mysql 컨테이너 재생성 → 앱 파드 Hikari가 죽은 커넥션 물어 요청 즉시 실패 → 파드 rollout restart 필요.
- (선택) 격리 벤치 오케스트레이션 함정: 동시 seed PREFIX 충돌, 콜드 InnoDB 버퍼풀 워밍업 지터.
- 근거: 세션 실측 로그 + 메모리 `loadtest-aws-run`.

**08 매니페스트 행단위 해부** (마무리·레퍼런스)
- `payment.yaml`을 한 줄씩: `nodeSelector{pool:payment}` / `tolerations(role=payment:NoSchedule)` / `podAntiAffinity(required, 노드당1)` / `lifecycle.preStop(sleep8)` / `resources(requests==limits=Guaranteed QoS)` / `strategy(maxSurge0/maxUnavailable1)` / `readiness·liveness probe` / `envFrom(ConfigMap/Secret)` / `terminationGracePeriodSeconds`.
- 각 줄: "왜 있나 + 없으면 뭐가 터지나"(surge 데드락·엔드포인트 레이스·noisy-neighbor가 다 이 줄들과 연결).
- 앵커노트로 마무리: `[[쿠버네티스 정리]]` 상호링크.
- 근거: `infra/k8s/apps/payment.yaml`.

## 앵커 노트 확장 (`Notes/쿠버네티스 정리.md`)

현재 헤딩: Pod·사이드카·Deployment·RollingUpdate·HPA·Service(얕음)·한눈에정리·더채울거리·관련노트. **추가할 것:**
1. **Service 4종 상세 + Ingress** — ClusterIP/NodePort/LoadBalancer/ExternalName 각 정의 + 우리 사용처 표(risk 등=ClusterIP, merchant-limit-np=NodePort:31574, Traefik=LoadBalancer via klipper-lb). Ingress=L7 라우팅 규칙(Traefik이 path `/`→payment).
2. **Control plane 컴포넌트** — kube-apiserver·scheduler·controller-manager·datastore(k3s=SQLite)·kubelet·kube-proxy·flannel(CNI)·CoreDNS·Traefik+klipper-lb. 각 한 줄 역할 + 우리 케이스.
3. **Strimzi** — Kubernetes Operator, CRD(Kafka·KafkaNodePool·KafkaTopic) 선언→reconcile. StatefulSet 수동관리 대체.
4. **매니페스트 주석** — Scale #08과 상호링크.

## 크로스링크 규약 (Load 패턴 복제)

- 각 편 상단 `[!summary] 핵심 한 줄`, 하단 `[!info] 시리즈`(1막 00~04 / 2막 05~08 두 줄 목록 + 앞뒤 편 링크).
- **Load↔Scale 브릿지:** #00→Load #22, #06→Load #13~15(fsync).
- **개념/락/커밋 노트 링크:** `[[쿠버네티스 정리]]` · `[[동시성 제어 4종 — 락 결정 트리]]` · `[[결제 취소 멱등성 설계]]` · `[[InnoDB 내구성 — 커밋은 왜 비싼가]]`.
- public `index.md`에 Scale 시리즈 블록 등재(Load 블록과 나란히).

## 근거 자료 (새 측정 없음)

- `docs/load-test/k3s-scaleout-results.md` §①~⑤ (개입확증 포함)
- `docs/architecture/k3s-scaleout.html` · `k3s-topology.html` · `k3s-topology-benchmark.html` · `k3s-flow.html`
- `infra/k3s-scaleout/instances.tf` · `infra/k8s/` (매니페스트)
- 메모리 `loadtest-aws-run` (vCPU 쿼터·운영 함정)

## 범위 밖 (Out of Scope)

- 새 AWS 측정/실험 (데이터 이미 있음, 리그 destroy됨).
- 코드 변경 (payment.yaml 전략 fix는 이미 PR #70/#71 머지됨).
- Load 시리즈 편 수정 (역참조 링크만 추가, 본문 개작 X).
- 다이어그램 신규 제작 (기존 architecture HTML 재사용/링크).

## 성공 기준

- 9편 초안 + 앵커노트 확장 완료, 전 편 `[!summary]`/`[!info]` 블록 + 크로스링크.
- 반전(05)이 클라이맥스로 서고, 06이 Load fsync와 정직하게 재회(리그별 병목 구성 차이 명시).
- 모든 수치가 결과문서와 일치(과장·미검증 주장 없음, Load 시리즈 정직성 기준 유지).
