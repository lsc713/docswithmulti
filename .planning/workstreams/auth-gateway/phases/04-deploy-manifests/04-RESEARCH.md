# Phase 4: 배포 매니페스트 (k3s) — Research

> 정찰(Explore 에이전트) + 사용자 결정으로 확정. 신규 리서치 스폰 없이 이 문서가 planner 입력.

## Summary

v2.0 인증 경계(user-service·api-gateway)를 기존 `infra/k8s/` 순수 YAML 컨벤션에 맞춰 배포 가능하게 하고, Phase 2/3에서 이관한 보안 게이트(payment ingress NetworkPolicy · JWT_SECRET Secret)를 매니페스트로 강제한다. 라이브 클러스터 없음 → 정적 검증만.

## 기존 배포 인프라 (정찰 실측)

- **형식**: 순수 YAML, `kubectl apply -f`. Helm/Kustomize 없음. 위치 `infra/k8s/`.
  - `apps/config.yaml` — ConfigMap `app-config`(redis/kafka/`*_DB_URL`/`EXTERNAL_*_URL`/`CANCEL_PUBLISH_MODE`) + Secret `db-cred`(DB user/pass, `type: Opaque` stringData)
  - `apps/{payment,order,risk,merchant-limit}.yaml` — Deployment+Service (payment는 Ingress도 보유)
  - `redis/redis.yaml`, `kafka/strimzi-kafka.yaml`(Strimzi operator), `deploy.sh`(순서 배포 드라이버)
- **규약**: NS `default`, label `app: <name>`, Deployment/Service name = `<name>`, Service port=targetPort=containerPort.
  - payment `app: payment` 8080 (replicas 3, 전용 노드풀, anti-affinity), order 8081(×2), merchant-limit 8082(×2), risk 8083(×2).
  - 서비스 간 통신 = k8s DNS (`http://risk:8083`). kafka는 `kafka` ns.
  - 헬스: `/actuator/health/readiness`·`/liveness`. payment `preStop sleep 8`.
  - **payment만 Ingress**(`networking.k8s.io/v1`, `/` Prefix → payment:8080) ← DEPLOY-02가 이관/제한할 대상.
- **이미지**: 각 서비스 루트 Dockerfile(멀티스테이지 `gradle:8-jdk21`→`:{module}:bootJar -x test`→`eclipse-temurin:21-jre-alpine`). k8s 이미지 = `camelia9999/cancel-loadtest:{svc}-latest`(risk는 `risk-management-latest`), `imagePullPolicy: Always`, Docker Hub public.
- **시크릿/설정**: ConfigMap `app-config`(`envFrom`) + Secret `db-cred`(`secretKeyRef`). `SPRING_PROFILES_ACTIVE` 미설정 → default(비-local) 프로파일 기동.
- **CNI**: k3s 기본 flannel + **내장 NetworkPolicy controller 활성**(`--disable-network-policy` 없음, `infra/k3s-scaleout/instances.tf` server user_data 확인) → NetworkPolicy 매니페스트가 실제 강제됨. Calico/Cilium 불필요.

## 신규 모듈 현황

- **user-service**: Dockerfile **있음**(EXPOSE 8085, `:user-service:bootJar`). user_db 필요(3315 로컬 → k8s는 외부 MySQL user_db).
- **api-gateway**: Dockerfile **없음 → 생성 필요**(user-service 패턴, EXPOSE 8000, `:api-gateway:bootJar`). 무상태(DB 없음).
- 포트: api-gateway 8000, user-service 8085.
- gateway downstream URI(`api-gateway/application.yml`: `gateway.downstream.payment-uri: http://localhost:8080`, `user-uri: http://localhost:8085`) → k8s에서 **서비스 DNS로 override 필요**: env `GATEWAY_DOWNSTREAM_PAYMENT_URI=http://payment:8080`, `GATEWAY_DOWNSTREAM_USER_URI=http://user-service:8085`.
- JWT: user-service·gateway 모두 비-local 프로파일에서 `jwt.secret: ${JWT_SECRET}`(기본값 없음) fail-fast. HS256 대칭키 → **동일 값 공유 필수**(불일치 시 전면 401).

## 사용자 확정 결정

- **user_db 위치**: 기존 외부 MySQL(10.0.1.30~32)에 user_db 스키마 추가 — payment_db 등과 동일 `*_DB_URL` 컨벤션. app-config에 `USER_DB_URL`(+ db-cred 재사용 또는 user 계정) 추가.
- **검증**: 정적만(라이브 클러스터 없음). kubectl `--dry-run=client` + (가능하면) kubeconform. Dockerfile은 docker build로 검증.
- **JWT_SECRET Secret**: 신규 Secret `jwt-secret`(경계 분리, 권장) — gateway·user-service에 `secretKeyRef`로 동일 값 주입. 매니페스트엔 placeholder/stringData 예시만, 실 값은 배포 시 주입(하드코딩 금지).

## 도구 가용성 (정적 검증)

- **kubectl 있음** → `kubectl apply --dry-run=client -f <file>`(오프라인 구조/스키마-lite 검증). 서버 스키마 필요한 full validate는 클러스터 없어 제한.
- **docker 있음** → `docker build -f api-gateway/Dockerfile`로 신규 Dockerfile end-to-end 검증(가장 강한 게이트).
- **kubeconform/kubeval/yq 없음** → 설치는 선택(예: kubeconform go install). 없으면 `kubectl --dry-run=client` + YAML 파싱으로 대체.

## Port / Create / Adjust 체크리스트

- **CREATE** `api-gateway/Dockerfile`(user-service 패턴, port 8000).
- **CREATE** `infra/k8s/apps/user-service.yaml`(Deployment+Service, 8085, envFrom app-config + JWT_SECRET secretKeyRef + USER_DB secretKeyRef, 헬스프로브).
- **CREATE** `infra/k8s/apps/api-gateway.yaml`(Deployment+Service, 8000, 무상태(DB env 없음), JWT_SECRET secretKeyRef, GATEWAY_DOWNSTREAM_* env, 헬스프로브) + **Ingress를 여기로 이관**(단일 진입점).
- **CREATE** `infra/k8s/apps/jwt-secret.yaml`(Secret `jwt-secret`, placeholder stringData + 주석으로 배포 시 주입 명시).
- **CREATE** `infra/k8s/networkpolicy/payment-ingress.yaml`(payment `podSelector app: payment`의 8080 ingress를 `app: api-gateway`에서만 허용). gateway→payment/user 이그레스 허용 고려.
- **ADJUST** `infra/k8s/apps/payment.yaml`(기존 `/` Ingress 제거/이관 — gateway가 진입점). payment Deployment/Service 로직 불변.
- **ADJUST** `infra/k8s/apps/config.yaml`(app-config에 `USER_DB_URL`·gateway downstream URI 추가). db-cred에 user_db 계정 필요 시 추가.
- **ADJUST** `infra/k8s/deploy.sh`(신규 매니페스트를 배포 순서에 추가: config/secret → user-service·api-gateway → networkpolicy).

## Validation Architecture

- **Dockerfile 게이트**: `docker build -t api-gateway:local -f api-gateway/Dockerfile .` exit 0 (신규 Dockerfile 실빌드).
- **매니페스트 구조 게이트(오프라인)**: 모든 신규/수정 `infra/k8s/**/*.yaml`을 **클러스터 비의존** 검증. 1순위 `kubeconform -strict -ignore-missing-schemas <f>`(스키마), 폴백 `python3 -c 'import yaml,sys;[list(yaml.safe_load_all(open(f))) for f in sys.argv[1:]]' <f>`(YAML 파싱) + grep 구조 단언(kind/apiVersion/podSelector/from/port). ⚠ `kubectl apply/create --dry-run=client`는 서버 API discovery가 필요해 도달 가능한 클러스터 없으면 exit 1 → 정적 게이트로 사용 금지.
- **정책 정합 게이트**: NetworkPolicy가 `podSelector app: payment` + ingress `from podSelector app: api-gateway` port 8080임을 grep/구조로 확인. Secret에 평문 실값 없음(placeholder만) grep 확인.
- **불변 게이트**: 취소 코어 Java 무변경(이 phase는 인프라 YAML + Dockerfile만). payment.yaml 변경은 Ingress 이관에 한정(Deployment spec 로직 불변).
- 라이브 apply·rollout·NetworkPolicy 실동작은 **범위 밖**(클러스터 없음) — 배포 시 사용자 수행.

## Open Questions (planning 시 확정)

1. db-cred 재사용 vs user_db 전용 계정 — 기존 db-cred가 단일 계정이면 재사용, 아니면 user 계정 추가.
2. NetworkPolicy 범위 — payment ingress 제한만(최소) vs default-deny + 화이트리스트(강). 이 phase는 **payment 제한(DEPLOY-02)** 이 필수, default-deny는 선택.
3. kubeconform 설치 여부 — 없으면 kubectl dry-run으로 충분.
