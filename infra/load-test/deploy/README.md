# role별 배포 + 사설 IP 배선

`terraform apply`로 뜬 9대에 컨테이너를 올린다. 앱은 로컬 `application.yml`(localhost)을 **env로 오버라이드**해 고정 사설 IP를 바라본다. env 이름은 코드베이스 규약(`docker-compose.yml` 주석)과 동일.

## 사설 IP · 포트 맵

| role | 호스트 IP | 올리는 것 | 포트 |
|------|-----------|-----------|------|
| infra | 10.0.1.40 | Redis / Kafka(1) / ZK / kafka-ui | 6379 / 9092 / 8989 |
| mysql-payment | 10.0.1.30 | MySQL payment_db | 3306 |
| mysql-risk | 10.0.1.31 | MySQL risk_db | 3306 |
| cold-db | 10.0.1.32 | MySQL merchant_db / order_db | 3306 / **3307** |
| payment | 10.0.1.20 | payment-service | 8080 |
| risk | 10.0.1.21 | risk-management-service | 8083 |
| cold-svc | 10.0.1.22 | merchant-limit / order | 8082 / 8081 |
| obs | 10.0.1.50 | 관측 스택 | 3000 / 9090 |
| k6 | 10.0.1.10 | 부하생성기 | — |

## 배포 순서 (의존성 순)

각 호스트에 SSM 접속 후, 레포를 가져오고(앱 호스트만 빌드에 필요) 해당 compose 실행.

```bash
# 앱 호스트(payment/risk/cold-svc)는 소스가 필요 → git clone or scp
git clone <repo> && cd <repo>/infra/load-test/deploy
```

**1) 인프라 먼저** (10.0.1.40)
```bash
docker compose -f infra.compose.yml up -d
```

**2) DB** (10.0.1.30 / .31 / .32)
```bash
docker compose -f mysql-payment.compose.yml up -d   # 10.0.1.30
docker compose -f mysql-risk.compose.yml   up -d    # 10.0.1.31
docker compose -f cold-db.compose.yml      up -d    # 10.0.1.32
```

**3) 앱** (빌드 포함, `--build`)
```bash
docker compose -f cold-svc.compose.yml up -d --build   # 10.0.1.22 (merchant-limit + order)
docker compose -f risk.compose.yml     up -d --build   # 10.0.1.21
docker compose -f payment.compose.yml  up -d --build   # 10.0.1.20
```
> 앱은 Flyway로 스키마 생성 후 기동. DB/Kafka 준비 전이면 재시도한다(`restart: unless-stopped` + `initialization-fail-timeout: -1`).

**4) 관측** — `../observability/README.md` (node-exporter는 9대 전부, 스택은 obs)

## 헬스 체크

```bash
curl http://10.0.1.20:8080/actuator/health   # payment
curl http://10.0.1.21:8083/actuator/health   # risk
curl http://10.0.1.22:8082/actuator/health   # merchant-limit
curl http://10.0.1.22:8081/actuator/health   # order
```

## 주의

- **Kafka `advertised.listeners`=10.0.1.40:9092** (infra.compose.yml). 이게 사설 IP가 아니면 원격 앱·exporter가 못 붙는다.
- MySQL `MYSQL_ROOT_HOST=%` → mysqld-exporter 원격 root 접속용 (테스트 편의).
- 단일 브로커라 모든 topic RF=1. 3-broker 순서/복제 실측이 목적이면 별도 구성.
- 이미지 빌드는 repo 루트 `.dockerignore`로 컨텍스트를 줄인다(build/·.git 제외).
- 프로파일: payment는 `spring.profiles.active: local`이 기본. 필요 시 `SPRING_PROFILES_ACTIVE`로 오버라이드.
