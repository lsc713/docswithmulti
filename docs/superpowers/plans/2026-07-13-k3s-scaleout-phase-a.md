# k3s 스케일아웃 Phase A — 클러스터 골격 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 자립 terraform 루트로 멀티노드 k3s 클러스터를 띄우고, 그 위에 Strimzi Kafka(3-broker)·Redis·앱 4서비스를 배포해 **전 파드 Ready + 취소 e2e 1건 성공**까지 도달한다.

**Architecture:** 신규 `infra/k3s-scaleout/`(자체 VPC·SG·IAM, 기존 load-test 복사·적응) + k3s user_data. 클러스터 안: 앱 Deployment×4 + Kafka(Strimzi/KRaft) + Redis + Traefik. 밖: MySQL×3 외부 고정. 매니페스트는 `infra/k8s/`.

**Tech Stack:** Terraform(AWS Graviton/arm64) · k3s · Strimzi(KRaft) · Traefik(k3s 내장) · Redis · 기존 Docker Hub 이미지(`camelia9999/cancel-loadtest`).

## Global Constraints

- 기존 `infra/load-test/`는 **변경 금지**. 신규는 `infra/k3s-scaleout/`(terraform) + `infra/k8s/`(매니페스트).
- **DB는 클러스터 밖**(mysql×3 외부 EC2). DB를 k8s에 넣지 않는다.
- 앱 이미지는 **Docker Hub `camelia9999/cancel-loadtest:<svc>-latest` 그대로**. 전 스택 **arm64**.
- 시크릿 하드코딩 금지 → k8s Secret. 앱 코드 변경 없음(Phase A는 배포만).
- **Phase A 범위**: 클러스터 골격 + 배포 + e2e 스모크. 락 하드닝·프로브 튜닝·검증실험은 Phase B/C(별도 플랜).
- Task 2부터 **AWS 과금 시작** → 완료/중단 시 `terraform destroy` 필수.

## 스코프 노트

이 플랜은 스펙(`docs/superpowers/specs/2026-07-13-k3s-scaleout-design.md`)의 **Phase A만** 다룬다. Phase B(merchant-limit 락 하드닝 TDD·프로브·graceful shutdown)와 Phase C(검증 실험 5개)는 A 검증 후 각자 플랜으로 작성한다 — 특히 **Strimzi arm64 지원(Task 3)**이 A의 최대 리스크라, 그 결과가 B/C 세부를 좌우한다.

## File Structure

```
infra/k3s-scaleout/          ← 신규 terraform 자립 루트
  versions.tf                  (load-test에서 verbatim 복사)
  variables.tf                 (복사 + k3s_token 변수 추가)
  network.tf                   (load-test에서 verbatim 복사)
  security.tf                  (load-test에서 verbatim 복사 — self_all이 전 포트 허용, k3s 포트 규칙 불필요)
  outputs.tf                   (복사 + kubeconfig 안내 출력 추가)
  instances.tf                 (신규 — k3s 노드 + user_data)
  terraform.tfvars             (use_spot·k3s_token, gitignore)
  .gitignore                   (복사)
infra/k8s/                   ← 신규 매니페스트 (클러스터에 apply)
  kafka/strimzi-kafka.yaml     (Kafka CR + KafkaTopic ×4)
  redis/redis.yaml             (Deployment + Service)
  apps/config.yaml             (ConfigMap + Secret)
  apps/payment.yaml            (Deployment×3 + Service + Ingress)
  apps/risk.yaml               (Deployment×2 + Service)
  apps/merchant-limit.yaml     (Deployment×2 + Service)
  apps/order.yaml              (Deployment×2 + Service)
  deploy.sh                    (server 노드에서 순서대로 kubectl apply)
```

---

### Task 1: 자립 terraform 루트 + k3s 부트스트랩 (apply 없이 validate/plan)

**Files:**
- Create: `infra/k3s-scaleout/versions.tf`, `variables.tf`, `network.tf`, `security.tf`, `outputs.tf`, `instances.tf`, `terraform.tfvars`, `.gitignore`

**Interfaces:**
- Produces: 사설 IP 고정 배치 — server `10.0.1.10`, agent `10.0.1.11/12/13`, mysql-payment `10.0.1.30`, mysql-risk `10.0.1.31`, cold-db `10.0.1.32`, k6 `10.0.1.20`, obs `10.0.1.50`. k3s 토큰 = `var.k3s_token`.

- [ ] **Step 1: 제너릭 tf 파일 verbatim 복사** (내용 동일, 디렉토리만 다름)

```bash
mkdir -p infra/k3s-scaleout infra/k8s/{kafka,redis,apps}
cp infra/load-test/versions.tf infra/load-test/network.tf infra/load-test/security.tf infra/load-test/.gitignore infra/k3s-scaleout/
```
`security.tf`의 `self_all` 규칙(`ip_protocol = "-1"`)이 같은 SG 인스턴스 간 **전 포트**를 이미 허용하므로 k3s 포트(6443·8472·10250·NodePort) 추가 규칙은 **불필요**. 그대로 둔다.

- [ ] **Step 2: `variables.tf` — load-test 복사 후 k3s_token 변수 추가**

```bash
cp infra/load-test/variables.tf infra/k3s-scaleout/variables.tf
```
그리고 파일 끝에 아래 블록 추가:

```hcl
variable "k3s_token" {
  description = "k3s server/agent 공유 조인 토큰 (terraform.tfvars에 설정, 커밋 금지)"
  type        = string
  sensitive   = true
}
```

- [ ] **Step 3: `outputs.tf` — 복사 후 kubeconfig 안내 출력 추가**

```bash
cp infra/load-test/outputs.tf infra/k3s-scaleout/outputs.tf
```
파일 끝에 추가:

```hcl
output "kubeconfig_hint" {
  description = "server 노드에서 kubectl 쓰는 법"
  value       = "aws ssm start-session --target <server-id> 후: sudo k3s kubectl get nodes"
}
```

- [ ] **Step 4: `instances.tf` 작성 (신규 — k3s 노드 + user_data)**

`infra/k3s-scaleout/instances.tf`:

```hcl
# ─────────────────────────────────────────────────────────────
# k3s 스케일아웃 리그 노드 (9대)
#   k3s-server 1 + agent 3 = 클러스터. mysql×3 외부 고정. k6·obs 외부.
#   SG self_all이 전 포트 허용 → k3s 포트 규칙 불필요.
# ─────────────────────────────────────────────────────────────
data "aws_ssm_parameter" "al2023_arm" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  server_ip = "10.0.1.10"
  instances = {
    k3s-server    = { type = "c7g.large", ip = local.server_ip, disk = 40, role = "server", spot = false }
    k3s-agent-1   = { type = "m7g.xlarge", ip = "10.0.1.11", disk = 60, role = "agent", spot = false }
    k3s-agent-2   = { type = "m7g.xlarge", ip = "10.0.1.12", disk = 60, role = "agent", spot = false }
    k3s-agent-3   = { type = "m7g.xlarge", ip = "10.0.1.13", disk = 60, role = "agent", spot = false }
    mysql-payment = { type = "m7g.large", ip = "10.0.1.30", disk = 100, role = "db" }
    mysql-risk    = { type = "m7g.large", ip = "10.0.1.31", disk = 100, role = "db" }
    cold-db       = { type = "c7g.large", ip = "10.0.1.32", disk = 100, role = "db" }
    k6            = { type = "c7g.xlarge", ip = "10.0.1.20", disk = 30, role = "k6" }
    obs           = { type = "t4g.medium", ip = "10.0.1.50", disk = 30, role = "obs", spot = false }
  }
}

# role별 user_data: server=k3s server(taint), agent=join, 그 외=docker(기존 compose 재사용)
locals {
  ud_server = <<-EOF
    #!/bin/bash
    set -e
    curl -sfL https://get.k3s.io | K3S_TOKEN='${var.k3s_token}' sh -s - server \
      --node-taint node-role.kubernetes.io/control-plane=true:NoSchedule \
      --tls-san ${local.server_ip} --write-kubeconfig-mode 644
  EOF
  ud_agent = <<-EOF
    #!/bin/bash
    set -e
    # server API가 뜰 때까지 대기 후 join (agent 자체 재시도도 있으나 명시적 대기)
    until curl -sk https://${local.server_ip}:6443/ping >/dev/null 2>&1; do sleep 5; done
    curl -sfL https://get.k3s.io | K3S_URL='https://${local.server_ip}:6443' K3S_TOKEN='${var.k3s_token}' sh -s - agent
  EOF
  ud_docker = <<-EOF
    #!/bin/bash
    set -e
    dnf install -y docker git
    systemctl enable --now docker
    usermod -aG docker ec2-user
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  EOF
}

resource "aws_instance" "node" {
  for_each = local.instances

  ami                         = data.aws_ssm_parameter.al2023_arm.value
  instance_type               = each.value.type
  subnet_id                   = aws_subnet.private.id
  private_ip                  = each.value.ip
  vpc_security_group_ids      = [aws_security_group.internal.id]
  iam_instance_profile        = aws_iam_instance_profile.ssm.name
  associate_public_ip_address = false

  user_data = each.value.role == "server" ? local.ud_server : (
    each.value.role == "agent" ? local.ud_agent : local.ud_docker
  )

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.disk
  }

  dynamic "instance_market_options" {
    for_each = (var.use_spot && lookup(each.value, "spot", true)) ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        spot_instance_type             = "one-time"
        instance_interruption_behavior = "terminate"
      }
    }
  }

  tags = {
    Name = "${var.project}-${each.key}"
    Role = each.key
  }
}
```

- [ ] **Step 5: `terraform.tfvars` 작성 (커밋 안 됨 — .gitignore로 무시)**

```bash
cat > infra/k3s-scaleout/terraform.tfvars <<'EOF'
region    = "ap-northeast-2"
az        = "ap-northeast-2a"
project   = "cancel-scaleout"
use_spot  = false
k3s_token = "REPLACE_WITH_RANDOM_48CHARS"
EOF
```
`k3s_token`은 `openssl rand -hex 24` 값으로 교체. `project="cancel-scaleout"`로 기존 리그와 태그 분리.

- [ ] **Step 6: init + validate + plan (apply 안 함 — 과금 없음)**

Run:
```bash
cd infra/k3s-scaleout && terraform init && terraform validate && terraform plan -no-color | grep -E 'Plan:|will be created'
```
Expected: `terraform validate` → `Success`. `plan` → `Plan: 9 to add, 0 to change, 0 to destroy` (인스턴스 9 + VPC/subnet/SG/IAM 등).

- [ ] **Step 7: Commit**

```bash
git add infra/k3s-scaleout/*.tf infra/k3s-scaleout/.gitignore
git commit -m "feat(k3s): 자립 terraform 루트 + k3s 부트스트랩 (validate/plan green)"
```

---

### Task 2: apply → k3s 클러스터 형성 검증 [과금 시작]

**Files:** (변경 없음 — 실행/검증 태스크)

**Interfaces:**
- Consumes: Task 1의 terraform 루트.
- Produces: running 클러스터. server에서 `sudo k3s kubectl` 사용 가능.

- [ ] **Step 1: apply**

Run:
```bash
cd infra/k3s-scaleout && AWS_REGION=ap-northeast-2 terraform apply -auto-approve
```
Expected: `Apply complete! Resources: N added`. outputs에 private_ips·ssm_connect 표시.

- [ ] **Step 2: SSM 등록 대기 (9노드 Online)**

Run:
```bash
export AWS_REGION=ap-northeast-2
until [ "$(aws ssm describe-instance-information --query "length(InstanceInformationList[?PingStatus=='Online'])" --output text)" -ge 9 ]; do echo waiting; sleep 8; done; echo READY
```
Expected: `READY`.

- [ ] **Step 3: k3s 노드 4개 Ready 검증**

server 인스턴스 ID를 얻어 SSM으로 `k3s kubectl get nodes` 실행:
```bash
SID=$(aws ssm describe-instance-information --query "InstanceInformationList[?contains(InstanceId, '')].InstanceId" --output text) # 또는 terraform output instance_ids 에서 k3s-server
cid=$(aws ssm send-command --targets "Key=tag:Role,Values=k3s-server" \
  --document-name AWS-RunShellScript \
  --parameters '{"commands":["for i in $(seq 1 40); do sudo k3s kubectl get nodes 2>/dev/null | grep -c Ready && break; sleep 5; done; sudo k3s kubectl get nodes -o wide"]}' \
  --timeout-seconds 300 --query Command.CommandId --output text)
sleep 20
aws ssm list-command-invocations --command-id "$cid" --details --query 'CommandInvocations[0].CommandPlugins[0].Output' --output text
```
Expected: 4개 노드(`k3s-server` + agent ×3) 모두 `Ready`. (부팅+k3s 설치+join에 3~5분 소요될 수 있어 재시도.)

- [ ] **Step 4: Commit** (실행 로그를 런북에 남길 뿐, 코드 변경 없음 — 스킵 가능. 다음 태스크로.)

---

### Task 3: Strimzi operator + Kafka 3-broker (★ arm64 게이트)

**Files:**
- Create: `infra/k8s/kafka/strimzi-kafka.yaml`

**Interfaces:**
- Produces: Kafka bootstrap Service `cancel-kafka-kafka-bootstrap.kafka.svc:9092` · 토픽 `payment.cancelled`(3p)·`payment.cancelled.DLQ`·`payment.cancelled.retry`·`merchant.limit.updated`(3p).

- [ ] **Step 1: Strimzi operator 설치 + arm64 게이트 검증**

server 노드에서 SSM으로:
```bash
sudo k3s kubectl create namespace kafka
sudo k3s kubectl apply -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
# operator 파드가 arm64에서 뜨는지 — 이 프로젝트 최대 리스크의 게이트
for i in $(seq 1 30); do sudo k3s kubectl -n kafka rollout status deploy/strimzi-cluster-operator --timeout=10s && break; sleep 10; done
sudo k3s kubectl -n kafka get pods
```
Expected: `strimzi-cluster-operator` 파드 `Running`/`1/1`.
**게이트 실패 시**(`CrashLoopBackOff`/`exec format error` = arm64 미지원): 중단하고 스펙 리스크 절로 복귀 — Bitnami Helm 차트 또는 x86 노드 재고. **이 실패는 human escalation 대상.**

- [ ] **Step 2: `strimzi-kafka.yaml` 작성 (KRaft, 3-broker, anti-affinity, local-path PVC)**

`infra/k8s/kafka/strimzi-kafka.yaml`:

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaNodePool
metadata:
  name: broker
  namespace: kafka
  labels:
    strimzi.io/cluster: cancel-kafka
spec:
  replicas: 3
  roles: [controller, broker]
  storage:
    type: persistent-claim
    size: 10Gi
    class: local-path
    deleteClaim: true
  template:
    pod:
      affinity:
        podAntiAffinity:            # 브로커 노드당 1개로 분산
          requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchLabels:
                  strimzi.io/pool-name: broker
              topologyKey: kubernetes.io/hostname
---
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: cancel-kafka
  namespace: kafka
  annotations:
    strimzi.io/node-pools: enabled
    strimzi.io/kraft: enabled
spec:
  kafka:
    version: 3.8.0
    replicas: 3
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      offsets.topic.replication.factor: 3
      transaction.state.log.replication.factor: 3
      transaction.state.log.min.isr: 2
      default.replication.factor: 3
      min.insync.replicas: 2
  entityOperator:
    topicOperator: {}
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: payment.cancelled
  namespace: kafka
  labels: { strimzi.io/cluster: cancel-kafka }
spec: { partitions: 3, replicas: 3 }
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: payment.cancelled.retry
  namespace: kafka
  labels: { strimzi.io/cluster: cancel-kafka }
spec: { partitions: 3, replicas: 3 }
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: payment.cancelled.dlq
  namespace: kafka
  labels: { strimzi.io/cluster: cancel-kafka }
spec: { partitions: 3, replicas: 3 }
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: merchant.limit.updated
  namespace: kafka
  labels: { strimzi.io/cluster: cancel-kafka }
spec: { partitions: 3, replicas: 3 }
```
> 주의: 실제 토픽 이름은 앱 `application.yml`의 `kafka.topic.*` 값과 **정확히 일치**해야 함. 배포 전 `grep -rn 'kafka.topic' */src/main/resources` 로 대조하고 위 metadata.name을 실제값으로 맞춘다(예: DLQ가 `payment.cancelled.DLQ` 대문자면 그대로).

- [ ] **Step 3: apply + 브로커 3개 Ready 검증**

매니페스트를 server로 전송 후:
```bash
sudo k3s kubectl apply -f strimzi-kafka.yaml
for i in $(seq 1 40); do sudo k3s kubectl -n kafka wait kafka/cancel-kafka --for=condition=Ready --timeout=20s && break; sleep 15; done
sudo k3s kubectl -n kafka get pods -o wide
sudo k3s kubectl -n kafka get kafkatopic
```
Expected: `kafka/cancel-kafka` condition `Ready=True`, 브로커 파드 3개 `Running`(서로 다른 노드), 토픽 4개 존재.

- [ ] **Step 4: Commit**

```bash
git add infra/k8s/kafka/strimzi-kafka.yaml
git commit -m "feat(k3s): Strimzi Kafka 3-broker(KRaft) + KafkaTopic (arm64 게이트 통과)"
```

---

### Task 4: Redis + ConfigMap + Secret

**Files:**
- Create: `infra/k8s/redis/redis.yaml`, `infra/k8s/apps/config.yaml`

**Interfaces:**
- Produces: Service `redis:6379` · ConfigMap `app-config`(URL/호스트) · Secret `db-cred`(user/pass). 앱은 이걸 envFrom으로 주입.

- [ ] **Step 1: `redis.yaml` 작성**

`infra/k8s/redis/redis.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: redis, namespace: default }
spec:
  replicas: 1
  selector: { matchLabels: { app: redis } }
  template:
    metadata: { labels: { app: redis } }
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports: [{ containerPort: 6379 }]
          readinessProbe:
            exec: { command: ["redis-cli", "ping"] }
            initialDelaySeconds: 3
---
apiVersion: v1
kind: Service
metadata: { name: redis, namespace: default }
spec:
  selector: { app: redis }
  ports: [{ port: 6379, targetPort: 6379 }]
```

- [ ] **Step 2: `config.yaml` 작성 (ConfigMap + Secret — 외부 DB IP·클러스터 Service 배선)**

`infra/k8s/apps/config.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata: { name: app-config, namespace: default }
data:
  SPRING_DATA_REDIS_HOST: "redis"
  SPRING_DATA_REDIS_PORT: "6379"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "cancel-kafka-kafka-bootstrap.kafka.svc:9092"
  EXTERNAL_RISK_MANAGEMENT_URL: "http://risk:8083"
  EXTERNAL_MERCHANT_LIMIT_BASE_URL: "http://merchant-limit:8082"
  CANCEL_PUBLISH_MODE: "INLINE"
  # 외부 MySQL(고정 사설IP) — DB만 클러스터 밖
  PAYMENT_DB_URL: "jdbc:mysql://10.0.1.30:3306/payment_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
  RISK_DB_URL: "jdbc:mysql://10.0.1.31:3306/risk_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
  MERCHANT_DB_URL: "jdbc:mysql://10.0.1.32:3306/merchant_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
  ORDER_DB_URL: "jdbc:mysql://10.0.1.32:3306/order_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
---
apiVersion: v1
kind: Secret
metadata: { name: db-cred, namespace: default }
type: Opaque
stringData:
  PAYMENT_DB_USER: "payment"
  PAYMENT_DB_PASS: "payment"
  RISK_DB_USER: "risk"
  RISK_DB_PASS: "risk"
  MERCHANT_DB_USER: "merchant"
  MERCHANT_DB_PASS: "merchant"
  ORDER_DB_USER: "order"
  ORDER_DB_PASS: "order"
```
> DB 유저/비번은 각 mysql compose(`infra/load-test/deploy/mysql-*.compose.yml`, `cold-db.compose.yml`)의 실제값과 대조해 맞춘다. 외부 mysql은 이 리그가 별도 provision하므로(Task 6 seed 전 기동 확인) 동일 이미지·계정 재사용.

- [ ] **Step 3: apply + 검증**

```bash
sudo k3s kubectl apply -f redis.yaml -f config.yaml
sudo k3s kubectl rollout status deploy/redis --timeout=60s
sudo k3s kubectl get cm app-config; sudo k3s kubectl get secret db-cred
```
Expected: redis 파드 Ready, ConfigMap/Secret 존재.

- [ ] **Step 4: Commit**

```bash
git add infra/k8s/redis/redis.yaml infra/k8s/apps/config.yaml
git commit -m "feat(k3s): Redis + app ConfigMap/Secret (외부 DB·클러스터 Service 배선)"
```

---

### Task 5: 앱 Deployment×4 + Service + Ingress

**Files:**
- Create: `infra/k8s/apps/payment.yaml`, `risk.yaml`, `merchant-limit.yaml`, `order.yaml`

**Interfaces:**
- Consumes: `app-config`·`db-cred`(Task 4), Kafka(Task 3), 외부 mysql(Task 6에서 seed).
- Produces: Service `payment:8080`·`risk:8083`·`merchant-limit:8082`·`order:8081` + payment Ingress.

- [ ] **Step 1: `payment.yaml` 작성 (대표 — ×3, anti-affinity, envFrom, 프로브, Service, Ingress)**

`infra/k8s/apps/payment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: payment, namespace: default }
spec:
  replicas: 3
  selector: { matchLabels: { app: payment } }
  strategy:
    rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
  template:
    metadata: { labels: { app: payment } }
    spec:
      affinity:
        podAntiAffinity:                    # replica를 노드에 분산(HA)
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector: { matchLabels: { app: payment } }
                topologyKey: kubernetes.io/hostname
      containers:
        - name: payment
          image: camelia9999/cancel-loadtest:payment-latest
          imagePullPolicy: Always
          ports: [{ containerPort: 8080 }]
          envFrom:
            - configMapRef: { name: app-config }
          env:
            - { name: SPRING_DATASOURCE_URL, valueFrom: { configMapKeyRef: { name: app-config, key: PAYMENT_DB_URL } } }
            - { name: SPRING_DATASOURCE_USERNAME, valueFrom: { secretKeyRef: { name: db-cred, key: PAYMENT_DB_USER } } }
            - { name: SPRING_DATASOURCE_PASSWORD, valueFrom: { secretKeyRef: { name: db-cred, key: PAYMENT_DB_PASS } } }
            - { name: JAVA_TOOL_OPTIONS, value: "-Xmx2g -XX:+UseG1GC" }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 40
            periodSeconds: 10
          resources:
            requests: { cpu: "500m", memory: "1Gi" }
---
apiVersion: v1
kind: Service
metadata: { name: payment, namespace: default }
spec:
  selector: { app: payment }
  ports: [{ port: 8080, targetPort: 8080 }]
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: payment
  namespace: default
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend: { service: { name: payment, port: { number: 8080 } } }
```

- [ ] **Step 2: `risk.yaml`·`merchant-limit.yaml`·`order.yaml` 작성 (payment와 동일 구조, 아래 값만 상이)**

각 파일은 Step 1의 payment.yaml에서 **Deployment + Service** 부분만(Ingress 없음) 복제하고 아래 표대로 값을 치환한다:

| 항목 | risk.yaml | merchant-limit.yaml | order.yaml |
|---|---|---|---|
| name / app label | `risk` | `merchant-limit` | `order` |
| replicas | 2 | 2 | 2 |
| image | `...:risk-latest` | `...:merchant-limit-latest` | `...:order-latest` |
| containerPort / Service port | 8083 | 8082 | 8081 |
| SPRING_DATASOURCE_URL key | `RISK_DB_URL` | `MERCHANT_DB_URL` | `ORDER_DB_URL` |
| USERNAME/PASSWORD secret key | `RISK_DB_USER`/`_PASS` | `MERCHANT_DB_USER`/`_PASS` | `ORDER_DB_USER`/`_PASS` |
| anti-affinity | 없음(생략) | 없음 | 없음 |
| 프로브 port | 8083 | 8082 | 8081 |

anti-affinity 블록은 payment에만 둔다(HA 실증 대상). risk/merchant-limit/order는 `affinity` 블록 없이 나머지 동일.

- [ ] **Step 3: `deploy.sh` 작성 (순서대로 apply)**

`infra/k8s/deploy.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
K="sudo k3s kubectl"
$K apply -f kafka/strimzi-kafka.yaml
$K -n kafka wait kafka/cancel-kafka --for=condition=Ready --timeout=600s
$K apply -f redis/redis.yaml -f apps/config.yaml
$K apply -f apps/payment.yaml -f apps/risk.yaml -f apps/merchant-limit.yaml -f apps/order.yaml
$K rollout status deploy/payment deploy/risk deploy/merchant-limit deploy/order --timeout=300s
$K get pods -o wide
```

- [ ] **Step 4: apply + 전 파드 Ready + health 검증**

server로 매니페스트+deploy.sh 전송 후:
```bash
$K apply -f apps/payment.yaml -f apps/risk.yaml -f apps/merchant-limit.yaml -f apps/order.yaml
$K rollout status deploy/payment --timeout=300s
$K get pods -l app=payment -o wide      # 3개, 서로 다른 노드
$K exec deploy/payment -- wget -qO- localhost:8080/actuator/health
```
Expected: payment 3·risk 2·merchant-limit 2·order 2 파드 `Running`/Ready, payment 파드 3개가 **서로 다른 노드**, health `{"status":"UP"}`.
> 파드가 CrashLoop면 대개 외부 mysql 미기동/미seed(Task 6) 또는 DB URL/계정 불일치 → `$K logs deploy/payment` 로 `Communications link failure` 확인.

- [ ] **Step 5: Commit**

```bash
git add infra/k8s/apps/*.yaml infra/k8s/deploy.sh
git commit -m "feat(k3s): 앱 Deployment×4 + Service + payment Ingress (anti-affinity·프로브·envFrom)"
```

---

### Task 6: e2e 스모크 — Traefik 경유 취소 1건 + Kafka→order 소비

**Files:** (변경 없음 — 검증 태스크. 필요 시 `infra/k8s/smoke.sh`)

**Interfaces:**
- Consumes: 배포된 클러스터 + 외부 mysql. 취소엔 merchant 1 + payment 1 시드 필요.

- [ ] **Step 1: 외부 mysql 기동 확인 + 최소 시드**

외부 mysql×3은 이 리그가 provision했으나 스키마/데이터는 비어 있음 → 앱이 Flyway로 스키마 생성(기동 시). merchant 1건은 API로, payment 1건은 SQL로 시드:
```bash
# merchant 생성 (Traefik → payment 아님; merchant-limit는 내부 Service라 클러스터 안에서 호출)
NODE=10.0.1.11   # 아무 agent 노드 IP (Traefik이 ServiceLB로 노드 80 바인딩)
# merchant-limit는 Ingress 미노출 → server에서 포트포워드 또는 임시 curl 파드로 생성
$K run curl --image=curlimages/curl --restart=Never --rm -it -- \
  sh -c "curl -s -XPOST http://merchant-limit:8082/v1/merchants -H 'Content-Type: application/json' -d '{\"merchantKey\":\"smoke\",\"name\":\"s\",\"cancelPeriodDays\":30,\"dailyLimit\":1000000000}'"
```
payment 1건은 기존 `k6/seed/seed.sh` 패턴으로 `MYSQL_HOST=10.0.1.30`에 1건 INSERT(SEED_COUNT=1). paymentKey/paymentItemId 확보.

- [ ] **Step 2: Traefik 경유 취소 호출**

```bash
NODE=10.0.1.11
curl -s -m5 -XPOST "http://${NODE}/v1/payments/<paymentKey>/cancel" \
  -H 'Content-Type: application/json' \
  -d '{"cancelItems":[{"paymentItemId":<id>}],"cancelReason":"k3s smoke"}'
```
Expected: HTTP 200, `{"status":"COMPLETED", ...}`. (Traefik → payment Service → 파드 중 하나.)

- [ ] **Step 3: Kafka→order 소비 정합성 확인**

```bash
$K exec deploy/order -- sh -c 'true'   # order 파드 존재 확인
# order_db(10.0.1.32:order_db)에서 취소 반영 확인 (processed_cancel_event 또는 orders 상태)
# server에서 mysql 클라이언트 파드로 조회
$K run mysqlc --image=mysql:8.0 --restart=Never --rm -it -- \
  mysql -h10.0.1.32 -uorder -porder order_db -e "SELECT COUNT(*) FROM processed_cancel_event;"
```
Expected: `processed_cancel_event` ≥ 1 (payment.cancelled 이벤트가 order 컨슈머에 소비돼 dedup 기록됨). → **발행→소비 e2e 경로가 k3s에서 성립.**

- [ ] **Step 4: Phase A 완료 — destroy 안내**

Phase B(락 하드닝)로 즉시 이어가지 않으면 과금 방지 위해:
```bash
cd infra/k3s-scaleout && terraform destroy -auto-approve
```
Phase A 산출물(terraform·매니페스트)은 커밋돼 있으므로 재기동은 `terraform apply` + `deploy.sh`로 재현 가능.

---

## Self-Review

**Spec coverage:**
- §1 토폴로지/사이징 → Task 1 instances.tf ✓
- §2 요청분산/Traefik → Task 5 Ingress + Task 6 Traefik 호출 ✓
- §3 워크로드/replica(3/2/2/2·Kafka3·Redis1) → Task 3·4·5 ✓
- §4 정합성 → **Phase A 범위 밖**(프로브만 Task 5에 포함; 락 하드닝·graceful shutdown은 Phase B) ✓ 의도적
- §5 배포(k3s 부트스트랩·Strimzi·SSM apply·롤아웃) → Task 1·2·3·5 ✓
- §6 검증실험 → **Phase C**(별도 플랜) — Phase A는 e2e 스모크(Task 6)까지만 ✓ 의도적
- §7 terraform 자립루트·SG·관측 → Task 1 ✓ (관측 Prometheus SD는 Phase C로 이연)

**Placeholder scan:** `<paymentKey>`·`<id>`·`REPLACE_WITH_RANDOM`은 런타임 값이라 의도적 플레이스홀더(생성 방법 명시함). 그 외 TBD/TODO 없음.

**Type consistency:** Service 이름(payment/risk/merchant-limit/order)·포트(8080/8083/8082/8081)·ConfigMap 키·토픽명이 Task 3~6에서 일관. 토픽명은 Task 3 Step 2에서 앱 application.yml과 대조하도록 명시.

**주의(구현 시 확인):** ① 외부 mysql 유저/DB명(payment/risk/merchant/order)이 실제 compose와 일치하는지 Task 4에서 대조. ② 토픽명 대소문자(DLQ) Task 3에서 대조. ③ Strimzi arm64 게이트(Task 3 Step 1)가 통과해야 이후 진행.

---

## Execution Handoff

Phase A 플랜 완료. Phase B(락 하드닝 TDD·graceful shutdown)·Phase C(검증 실험)는 A 검증 후 별도 플랜.
