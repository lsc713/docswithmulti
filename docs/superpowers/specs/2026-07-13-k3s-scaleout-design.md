# k3s 수평 스케일아웃 실증 — 설계 스펙

> 작성일: 2026-07-13
> 관련: [`docs/load-test/capacity-planning.md`](../../load-test/capacity-planning.md)(190rps 무릎·DB fsync 병목) · [`docs/architecture.md`] · 블로그 `Posts/Load/22`(open/closed·게이트웨이 캐비앗) · CLAUDE.md(불변식)

**Goal:** 취소 MSA를 멀티노드 k3s 클러스터에 올려, **수평 스케일아웃의 정합성·가용성·배포 행동을 실증**하고 "앱 replica는 DB 천장(≈190rps)을 못 올린다"를 측정으로 못박는다. 기존 `infra/load-test/` 리그는 손대지 않고 별도 자립 루트에서 진행한다.

**Architecture:** 멀티노드 k3s(server 1 + agent 3). 클러스터 안: 앱 4서비스(Deployment) + Kafka 3-broker(Strimzi/KRaft) + Redis + Traefik 인그레스. 클러스터 밖 고정: MySQL ×3(병목 통제변수). 외부 k6가 Traefik으로 부하.

**Tech Stack:** k3s · Strimzi(Kafka operator, KRaft) · Traefik(k3s 내장 인그레스) · Redis · Redisson(분산락) · Spring Boot 3.x(Actuator health probes·graceful shutdown) · Terraform(자립 루트, AWS Graviton/arm64) · k6.

## Global Constraints (스펙 전역 — 모든 태스크가 준수)

- 기존 `infra/load-test/`는 **변경 금지**(과거 실측 재현성 보존). 신규는 자립 terraform 루트 `infra/k3s-scaleout/`.
- **DB는 클러스터 밖 고정**(mysql×3, 외부 EC2) — 병목 통제변수. DB를 k8s에 넣지 않는다.
- 앱 이미지는 **기존 Docker Hub(`camelia9999`) 그대로** 재사용(CI 불변, 재빌드 없음). 전 스택 **arm64**(Graviton).
- CLAUDE.md 불변식 준수: 시크릿 하드코딩 금지(k8s Secret) · 모듈 간 DB 직접접근 금지 · domain 레이어 Spring/JPA 금지 · 테스트 없이 완료 금지.
- **앱 코드 변경은 최소** — merchant-limit 락 하드닝 + (프로브·graceful shutdown) 설정뿐. 그 외는 인프라/매니페스트.
- replica 수는 **처리량이 아니라 HA·락데모·버스트**가 정한다(스루풋은 DB 바운드로 불변).

---

## 결정 로그 (브레인스토밍 확정)

| 결정 | 선택 | 근거 |
|---|---|---|
| 오케스트레이션 | **k3s** | 경량 k8s, 리그에서 실 운영, operator/ingress 내장 |
| 클러스터 경계 | 앱 + Kafka + Redis **in-cluster**, **DB만 외부** | stateful 인프라 k8s 경험 + 병목 DB는 통제변수로 고정 |
| 노드 구성 | **멀티노드** server1 + agent3 | 브로커·payment replica를 노드당 1개 anti-affinity → 진짜 스케줄링·HA 실증 |
| Kafka 배포 | **Strimzi 오퍼레이터**(KRaft) | 프로덕션 표준, operator 패턴 학습, 완결성 |
| terraform | **자립 루트**(자체 VPC·SG·DB) | 기존 리그와 상태 공유 0, 독립 apply/destroy |

---

## §1. 클러스터 토폴로지 & 사이징

노드 9대(자립 VPC, 단일 AZ):

| 노드 | 타입 | 역할 |
|---|---|---|
| k3s-server | c7g.large (2/4GB) | control-plane, taint(`node-role...control-plane:NoSchedule`) |
| k3s-agent ×3 | m7g.xlarge (4/16GB) | 워크로드: Kafka 브로커 1/노드 + 앱 파드 + Redis |
| mysql-payment | m7g.large | 외부 DB(payment_db) |
| mysql-risk | m7g.large | 외부 DB(risk_db) |
| cold-db | c7g.large | 외부 DB(merchant_db + order_db) |
| k6 | c7g.xlarge | 외부 부하생성기 |
| obs | t4g.medium (온디맨드) | Prometheus + Grafana(클러스터 밖) |

- agent 3 = Kafka 브로커 3개를 노드당 1개(anti-affinity)로 흩고 payment ×3도 노드당 1개로 흩기 위한 최소.
- agent 16GB = Kafka 브로커(JVM) + 앱 파드 여럿을 얹어도 **앱 노드가 병목이 안 되게**(그래야 DB-천장 실험이 깨끗).
- Spot 회수 이력(2026-07-13 SLO 런) 고려: **agent·obs는 on-demand**(회수 시 클러스터 붕괴/측정오염 방지). server·k6·mysql은 spot 허용(단, `use_spot` 토글로 전부 on-demand 전환 가능).

## §2. 요청 분산 & 리버스 프록시

- **외부 진입** = **Traefik**(k3s 내장, 별도 nginx 불필요). k3s ServiceLB(klipper)가 Traefik `LoadBalancer` Service에 노드 IP 바인딩(80/443). k6 → 노드 IP → Traefik `Ingress` → `payment` Service → payment 파드 3개에 kube-proxy L4 분산.
- **내부 홉** = 인그레스 미경유. risk→merchant-limit, payment→risk는 **클러스터 DNS(ClusterIP Service)**로 파드 분산.
- **order** = 인그레스 없음. Kafka **컨슈머 그룹**이 파티션을 order 파드에 분배(그게 분산). 락 불필요.
- 부수 측정: payment 앞 Traefik 홉으로 **게이트웨이 오프셋 실측**(블로그 22편 "내부 서비스타임" 캐비앗 정량화).

## §3. 워크로드 & replica ("몇 대")

| 워크로드 | 수 | 근거 |
|---|---|---|
| payment Deployment | **×3** | anti-affinity 노드당 1 → 노드 죽어도 2 생존(HA) · 스케줄러 락 경합 데모 · Service 분산에도 190 불변 증명 |
| risk Deployment | **×2** | 취소 핫홉, HA. 스케줄러 없음 → 순수 stateless |
| merchant-limit Deployment | **×2** | HA. OutboxPublisherScheduler 락 하드닝 대상(2개라야 이중발행 재현) |
| order Deployment | **×2** | Kafka 컨슈머 → replica ≤ 파티션수 |
| Kafka (Strimzi) | **×3** | KRaft, 노드당 1 anti-affinity, PVC(local-path) |
| Redis | **×1** | 분산락 + daily_limit 캐시. 단일(fail-safe), 프로덕션 HA는 out of scope |

- **KafkaTopic(Strimzi CR)**: `payment.cancelled`(파티션 3, 키 cancelRequestId) · `payment.cancelled.DLQ` · `payment.cancelled.retry`(order 재시도 컨슈머) · `merchant.limit.updated`(파티션 3, 키 merchantId). 파티션 3 = order/risk replica 병렬성 확보.
- **설정 이전**: compose env → **ConfigMap**(외부 DB URL=mysql 사설IP · Kafka bootstrap=`<cluster>-kafka-bootstrap:9092` · Redis Service host) + **Secret**(DB user/pass). 하드코딩 금지 준수.

## §4. 멀티인스턴스 정합성

감사 결과: `@Scheduled` 5개 중 payment 4개는 Redisson RLock(정석), merchant-limit 1개만 수제 SETNX(버그). k8s는 `@Scheduled` 파드도 N 복제 → 락 없으면 N-발화.

- **merchant-limit `OutboxPublisherScheduler` 하드닝(유일한 앱 코드 변경)**: 수제 `setIfAbsent + 고정TTL + 무조건 delete`(① 남의 락 삭제 ② TTL 갱신 없음)를 **Redisson RLock으로 정렬**(소유권 확인 unlock + 워치독 리스 갱신). merchant-limit-service에 RedissonClient 설정 추가(payment/risk와 동일 패턴). **TDD**: Testcontainers Redis로 (a) 동시 2-스레드에서 1개만 진입 (b) 소유자만 unlock (c) 작업>TTL에도 리스 유지.
- **payment 스케줄러**: 이미 Redisson. 코드 변경 없음 — 실험으로 "×3에서 tick당 1개만" 검증.
- **Redis 단일 = fail-safe**: Redis 다운 시 tryLock 실패/예외 → 스케줄러 tick **스킵**(안 돎, 이중발화 아님). 실험에서 확인.
- **프로브**: readiness=`/actuator/health/readiness`(DB풀·Kafka 준비 전 트래픽 차단) · liveness=`/actuator/health/liveness`(행 파드 재시작). `management.endpoint.health.probes.enabled`.
- **graceful shutdown**: `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=25s` + Deployment `terminationGracePeriodSeconds=30`. 롤링/드레인 시 in-flight 취소 drain. 잘려도 pending/processing-recovery 스케줄러가 안전망(기존 설계).
- **Kafka 컨슈머 리밸런싱**: order ×2 롤링 시 재분배 → 재처리 가능하나 `processed_cancel_event` UK로 멱등 → 안전.
- **파드 간 멱등**: 따닥이 다른 payment 파드에 착지해도 `cancel_request(payment_id, request_hash)` UK가 DB에서 차단(앱 상태 아님).

## §5. 배포 방식

- **k3s 부트스트랩**(terraform user_data): server `curl -sfL https://get.k3s.io | sh -s - --node-taint ...control-plane:NoSchedule`; agent는 server node-token으로 join(token은 SSM Parameter/SSM으로 전달).
- **매니페스트**: 신규 `infra/k8s/`(terraform과 분리, 클러스터에 apply). Kustomize base + raw manifest(인스펙터블). 적용 순서:
  1. Strimzi operator 설치 → `Kafka`(3-broker KRaft, PVC local-path) + `KafkaTopic` CR
  2. `redis` Deployment+Service · ConfigMap · Secret
  3. 앱 Deployment ×4 + Service + `payment` Ingress(Traefik)
- **적용 메커니즘**: 기존 SSM 패턴 계승 — server 노드에서 `kubectl apply`를 SSM `AWS-RunShellScript`로 구동(kubeconfig 터널 불필요). 매니페스트는 repo clone 또는 base64 전송.
- **롤아웃**: Deployment RollingUpdate(maxSurge=1, maxUnavailable=0) → 부하 중 `kubectl rollout restart deploy/payment`로 무중단 배포 실증.

## §6. 검증 실험 (measure-first · 성공기준)

| # | 실험 | 방법 | 성공기준 |
|---|---|---|---|
| 1 | 스케줄러 락 안전성 | payment ×3, recovery 유발 · 락 on/off 플래그 | tick당 1개만 실행(로그/메트릭) · off면 N-발화 재현 · merchant-limit 하드닝 후 이중발행=0 |
| 2 | 파드 간 멱등 | 따닥을 Traefik으로(같은 request_hash) | 취소 1건(COMPLETED), 이중취소 0, UK 충돌 처리 |
| 3 | N-replica 천장 불변 | `k6/slo-arrival.js` 재사용, payment ×1 vs ×3, Traefik 경유 | 무릎 ≈190 그대로(DB바운드 증명) + Traefik 홉 오프셋(내부 p95 vs 직결) 정량화 |
| 4 | 가용성(HA) | 부하 중 payment 파드 얹힌 agent drain/terminate | Service 지속 · 5xx 블립 후 self-heal 회복시간 측정 |
| 5 | 무중단 롤링배포 | 부하 중 `rollout restart deploy/payment` | 5xx=0(graceful + readiness) |

## §7. 인프라·관측·테스트

- **terraform 자립 루트 `infra/k3s-scaleout/`**: 자체 VPC·subnet·SG·SSM IAM·AMI(load-test에서 복사·적응). instances: k3s-server 1 + agent 3 + mysql×3 + k6 + obs. `use_spot` 토글(agent/obs 기본 on-demand).
- **SG**: 클러스터 내부 6443(API)·8472/udp(flannel VXLAN)·10250(kubelet)·30000-32767(NodePort) 상호 허용 · 파드→외부 mysql 3306 · k6→노드 80/443(Traefik).
- **관측**: obs 클러스터 밖 유지. node-exporter DaemonSet · kube-state-metrics · 앱 `/actuator/prometheus`를 Prometheus **k8s SD**로 스크레이프. k6 remote-write 불변.
- **테스트**: 앱 코드 변경(merchant-limit 락)만 TDD(Testcontainers Redis, §4). 매니페스트는 `kubectl apply` + `rollout status` + §6 실험으로 검증. graceful/probe는 실험 4·5로 검증.

## 단계 구성 (writing-plans에서 태스크로 분해)

- **Phase A — 클러스터 골격**: terraform 자립 루트 apply → k3s up → Strimzi + Kafka + Redis + 앱 배포 → 전 파드 Ready · health green · 취소 e2e 1건 성공.
- **Phase B — 정합성 코드/설정**: merchant-limit 락 하드닝(TDD) · 프로브 · graceful shutdown · anti-affinity.
- **Phase C — 검증 실험**: §6 실험 5개(AWS 런·과금) → 결과 문서화(capacity-planning/블로그 후속) → destroy.

## Out of Scope (YAGNI)

- HPA/오토스케일(고정 replica; 트리거 설계는 문서 언급만) · 서비스 메시(Istio/Linkerd) · AWS ALB/NLB·cloud-controller(k3s klipper 사용) · 멀티 AZ(단일 AZ, anti-affinity는 노드 레벨) · Redis HA(Sentinel/Cluster) · DB in-cluster · 매니페스트 CI/CD(수동 apply) · Vault(k8s Secret로 충분, 프로덕션급 아님).

## 가정 · 리스크

- **arm64**: 전 스택 Graviton. Strimzi operator·Kafka 이미지가 arm64 멀티아치인지 **Phase A 초입에 검증**(미지원 시 Bitnami Helm 또는 노드 아키텍처 재고).
- 기존 Docker Hub 이미지(plain Spring Boot jar)는 k8s에서 무변경 동작(env→ConfigMap 매핑만).
- 단일 AZ = anti-affinity는 노드 분산이지 AZ 분산 아님(프로덕션은 topologySpreadConstraints로 AZ 확장; 본 실증 범위 밖).
- Kafka-on-k8s(Strimzi) 셋업이 Phase A의 최대 리스크(리스너·어드버타이징·스토리지) → arm64 검증 + 최소 브로커 기동을 A의 초기 게이트로.
