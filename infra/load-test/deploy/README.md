# role별 배포 + 사설 IP 배선

`terraform apply`로 뜬 11대에 컨테이너를 올린다. 앱은 로컬 `application.yml`(localhost)을 **env로 오버라이드**해 고정 사설 IP를 바라본다. env 이름은 코드베이스 규약(`docker-compose.yml` 주석)과 동일.

**배포 모델: 빌드/실행 분리 (Docker Hub pull)**
- 앱 이미지는 **CI(`.github/workflows/loadtest-images.yml`)가 Docker Hub(public)에 arm64로 빌드/push** — 코드 바뀔 때만.
- 서버 띄울 때(`terraform apply`)는 **호스트가 pull만** 한다. 호스트에 소스/Gradle 없음.
- 이미지: `<IMAGE_NS>/cancel-loadtest:<payment|risk-management|merchant-limit|order|product>-<IMAGE_TAG>` (`IMAGE_NS`=Docker Hub 사용자명).
- 인프라/DB(mysql·redis·kafka)는 공개 이미지라 그대로 pull.

## 사설 IP · 포트 맵

| role | 호스트 IP | 올리는 것 | 포트 |
|------|-----------|-----------|------|
| infra | 10.0.1.40 | Redis / Kafka(1) / ZK / kafka-ui | 6379 / 9092 / 8989 |
| mysql-payment | 10.0.1.30 | MySQL payment_db | 3306 |
| mysql-risk | 10.0.1.31 | MySQL risk_db | 3306 |
| cold-db | 10.0.1.32 | MySQL merchant_db / order_db | 3306 / **3307** |
| mysql-product | 10.0.1.33 | MySQL product_db | 3306 |
| mysql-product-replica | 10.0.1.34 | MySQL product_db 읽기 복제본 | 3306 |
| payment | 10.0.1.20 | payment-service | 8080 |
| risk | 10.0.1.21 | risk-management-service | 8083 |
| cold-svc | 10.0.1.22 | merchant-limit / order | 8082 / 8081 |
| product | 10.0.1.23 | product-service | 8084 |
| obs | 10.0.1.50 | 관측 스택 | 3000 / 9090 |
| k6 | 10.0.1.10 | 부하생성기 | — |

## 배포 — SSM 일괄 (권장)

인터랙티브 세션 없이 `ssm-deploy.sh`가 role 태그별로 인프라→DB→앱 순서로 `send-command`를 쏜다. 각 호스트는 compose yml만 clone 후 Docker Hub에서 pull.

```bash
# 로컬(aws cli 인증된 곳)에서 실행. IMAGE_NS = Docker Hub 사용자명.
IMAGE_NS=<dockerhub-user> IMAGE_TAG=<sha> REPO_REF=<same-sha> ./infra/load-test/deploy/ssm-deploy.sh
# 상품 GTID 읽기 복제본 실측: source → replica/configure/smoke → Redis → 앱 순으로 배포
LOAD_TEST_PROFILE=product-replica IMAGE_NS=<dockerhub-user> IMAGE_TAG=<sha> REPO_REF=<same-sha> ./infra/load-test/deploy/ssm-deploy.sh
# 특정 role만:  IMAGE_NS=<user> REPO_REF=<sha> ROLES="payment risk" ./ssm-deploy.sh
# 특정 revision: IMAGE_NS=<user> IMAGE_TAG=<sha> REPO_REF=<same-sha> ./ssm-deploy.sh
# 로그를 CloudWatch로: IMAGE_NS=<user> REPO_REF=<sha> LOG_CLOUDWATCH=1 ./ssm-deploy.sh
```
> 앱은 Flyway로 스키마 생성 후 기동. DB/Kafka 준비 전이면 재시도로 수렴(`restart: unless-stopped`). 기동까지 1~3분.

`product-replica` 프로파일에서만 source Compose에 GTID/ROW binlog와 복제 사용자 초기화를 덧붙인다. replica는 첫 entrypoint 초기화 동안 쓰기 가능하게 시작한 뒤 SSM이 `SET PERSIST`로 두 read-only 전역 변수를 켜고 확인한다. 승격/연쇄 복제는 범위 밖이므로 replica 자체 binlog는 켜지 않는다. `product_replicator/product_replicator`, `product_reader/product_reader`, MySQL root 비밀번호 `root`는 폐기 가능한 부하 테스트 전용 자격 증명이며 운영에 사용하지 않는다.

GTID auto-position은 기존 데이터를 복사하지 않는다. 따라서 source 호스트에 fresh/success marker 없이 `deploy_mysql-product-data` 볼륨이 이미 있으면 배포가 중단된다. fresh marker는 빈 source 볼륨 경로에서만 생기고, success marker는 replication smoke 성공 뒤에만 생긴다. replica-only 부분 배포도 source preflight가 먼저 통과해야 한다. replica 구성은 같은 source가 이미 설정돼 있으면 멈춘 thread만 다시 시작하고, 배포 중 `mysql-product-replica-smoke.sh`가 thread 상태, marker 복제, reader SELECT/쓰기 거부, marker 삭제 복제를 확인한다. 수동 재검증은 replica 호스트의 deploy 디렉터리에서 다음처럼 실행한다.

```bash
./mysql-product-replica-smoke.sh
```

**관측 자동 포함** — ssm-deploy가 node-exporter(전 호스트)+obs 스택까지 배포. 확인:
```bash
./infra/load-test/deploy/port-forward.sh grafana   # localhost:3000 대시보드
./infra/load-test/deploy/port-forward.sh kafka     # localhost:8989 consumer lag
```
자세히는 `../observability/README.md`.

### 수동 배포 (디버깅용)

호스트에 `aws ssm start-session`으로 접속해 개별 실행할 때:
```bash
git clone --no-checkout https://github.com/lsc713/docswithmulti.git docswithmulti
git -C docswithmulti fetch --depth 1 origin <sha>
git -C docswithmulti checkout --detach FETCH_HEAD
cd docswithmulti/infra/load-test/deploy
docker compose -f infra.compose.yml up -d                      # 10.0.1.40 (인프라 먼저)
docker compose -f mysql-payment.compose.yml up -d              # 10.0.1.30 (DB)
IMAGE_NS=<user> docker compose -f payment.compose.yml pull && \
IMAGE_NS=<user> docker compose -f payment.compose.yml up -d    # 10.0.1.20 (앱, pull만)
```

## 헬스 체크

```bash
curl http://10.0.1.20:8080/actuator/health   # payment
curl http://10.0.1.21:8083/actuator/health   # risk
curl http://10.0.1.22:8082/actuator/health   # merchant-limit
curl http://10.0.1.22:8081/actuator/health   # order
curl http://10.0.1.23:8084/actuator/health   # product
```

## 주의

- **Kafka `advertised.listeners`=10.0.1.40:9092** (infra.compose.yml). 이게 사설 IP가 아니면 원격 앱·exporter가 못 붙는다.
- MySQL `MYSQL_ROOT_HOST=%` → mysqld-exporter 원격 root 접속용 (테스트 편의).
- 단일 브로커라 모든 topic RF=1. 3-broker 순서/복제 실측이 목적이면 별도 구성.
- 이미지 빌드는 repo 루트 `.dockerignore`로 컨텍스트를 줄인다(build/·.git 제외). CI에서 굽고 호스트는 pull만.
- 프로파일: payment는 `spring.profiles.active: local`이 기본. 필요 시 `SPRING_PROFILES_ACTIVE`로 오버라이드.
- 이미지가 최신이 아니면 먼저 CI(`loadtest-images.yml`)를 돌려 Docker Hub를 갱신한 뒤 `ssm-deploy.sh` 실행.
