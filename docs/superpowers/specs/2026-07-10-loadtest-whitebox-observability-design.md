# Load-test White-box Observability Design
## 분산 트레이싱(APM) + 요청당 쿼리 수

**Date**: 2026-07-10
**Branch**: feat/loadtest-whitebox-observability
**Scope**: infra/load-test (obs 스택 + deploy 이미지), 신규 `common-observability` 모듈, 4개 서비스 의존 배선

---

## 1. 배경 및 목표

실측 파이프라인은 지금까지 **경계(black-box) 지표**만 잰다 — rps · p95 · error%. "문제가 있나 / 얼마나 나쁜가"는 답하지만 **"어디서 / 왜"** 는 못 답한다. 8편은 SSM으로 `docker logs`를 grep해서, 11편은 netem으로 "느린 HTTP가 DB 커넥션을 점유한다"를 **추론**해서 그 공백을 사람이 수동으로 메웠다.

이 스펙은 그 공백을 **코드레벨(white-box) 관측**으로 메운다. 두 축이다.

- **분산 트레이싱(APM)** — payment→risk→merchant-limit 홉과 DB/HTTP/Kafka span을 한 트레이스로. 11편의 "추론"을 span 폭으로 **보이게**.
- **요청당 쿼리 수** — 엔드포인트별 쿼리 수를 지표로. black-box가 원리적으로 못 보는 N+1류를 곡선으로.

**주 목적은 "AWS 실측 중 관측"** 이다. 상시 운영 APM이 아니라 온디맨드 실측 인프라 수명 동안만 뜨면 되므로 self-host(obs 인스턴스)가 자연스럽다. 평상시 실행·CI에는 영향이 0이어야 한다(전부 opt-in).

---

## 2. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| 트레이스 백엔드/UI | **Grafana Tempo** | 기존 Grafana에 datasource 하나로 끝. 지표·로그와 한 화면, exemplar 확장 여지 |
| 앱 계측 | **OpenTelemetry Java agent** | `-javaagent` 한 줄, 코드 변경 0. Spring MVC·JDBC·HTTP client·Kafka 자동 계측 |
| agent 배치 | deploy 이미지에 `ADD`로 **굽되**, `-javaagent`는 미설정 | 이미지 재배포 없이 실측 compose env로만 활성 → 평상시 영향 0 |
| 트레이스 경로 | agent → OTLP/gRPC :4317 → **Tempo 직결** | Tempo가 OTLP 수신. 쿼리 수는 앱 코드로 뽑으므로 **OTel Collector 불필요** |
| 요청당 쿼리 수 | **datasource-proxy + `OncePerRequestFilter` → Micrometer `DistributionSummary`** | 요청-스레드 1:1 + Hikari 블로킹 모델에 ThreadLocal 카운트가 정확 |
| 쿼리 수 지표 노출 | 기존 `/actuator/prometheus` → 기존 Prometheus → Grafana 패널 | 신규 수집 경로 없음 |
| 쿼리카운트 코드 배치 | **신규 `common-observability` 라이브러리 모듈**, 4개 서비스 의존 | DRY. 도메인/DB 접근 아님 → 모듈 독립 원칙 무충돌 |
| 활성화 방식 | 전부 플래그/env **opt-in** | 평상시·CI 영향 0. 실측 compose에서만 켬 |
| 샘플링 | 시작은 100%(`parentbased_always_on`), 왜곡 시 `traceidratio` 하향 | 실측 왜곡 방지 — 대시보드에 명시 |

### 범위 밖 (YAGNI)

- CI `assertQueryCount` 회귀 테스트 (지표 인프라만 깔고, assert 확장은 후속)
- 상시 운영 APM / SaaS(Datadog 등) / 샘플링 자동 튜닝
- metric↔trace **exemplar** 연동 (Tempo 붙인 뒤 여유 되면)
- OTel Collector · spanmetrics 커넥터 (쿼리 수를 앱 코드로 뽑으므로 불필요)
- product-service (미구현)

---

## 3. 아키텍처

```
┌─ 앱 4종 (payment/risk/merchant-limit/order) ──────────────┐
│  -javaagent:opentelemetry-javaagent.jar  (compose env로만 ON)│
│    ├─ 자동 계측: Spring MVC · JDBC(쿼리별 span) · HTTP · Kafka │
│    │     └──────── OTLP/gRPC :4317 ───────┐                  │
│    └─ common-observability                 │                  │
│         ProxyDataSource(Hikari 래핑)        │                  │
│         + OncePerRequestFilter              │                  │
│         → Micrometer db_queries_per_request │                  │
│             └─ /actuator/prometheus         │                  │
└─────────────────┬───────────────────────────┼──────────────────┘
                  │ (기존 scrape)               │ (신규 OTLP)
                  ▼                             ▼
            Prometheus  ─────────────────►  Tempo   (obs 인스턴스)
                  │                             │
                  └────────────┬────────────────┘
                               ▼
                          Grafana  (Prometheus + Tempo 데이터소스)
                               └─ SSM 포트포워딩 → 노트북 브라우저
```

- **트레이스**: agent → Tempo 직결. 중간 컴포넌트 없음.
- **쿼리 수**: 기존 Prometheus scrape 경로 재사용. 신규 수집 파이프라인 없음.
- 두 신호 모두 **obs 인스턴스의 Grafana 한 화면**에 모인다.

---

## 4. 컴포넌트별 설계

### 4.1 파트 A — 분산 트레이싱 (OTel agent → Tempo)

**이미지 (`infra/load-test/deploy/Dockerfile`)**
런타임 스테이지에 agent jar를 굽는다. `-javaagent`는 ENTRYPOINT에 **넣지 않는다.**

```dockerfile
# 런타임 스테이지
ARG OTEL_AGENT_VERSION=2.x   # 핀 고정
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
# ENTRYPOINT 는 기존대로 (agent 미활성)
```

**활성화 (load-test compose 파일: `payment.compose.yml` 등)**
각 앱 서비스 블록에 env 추가. 이 env가 있어야만 agent가 뜬다.

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-javaagent:/otel/opentelemetry-javaagent.jar"
  OTEL_SERVICE_NAME: "payment"                     # 서비스별
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"   # obs 인스턴스
  OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
  OTEL_TRACES_SAMPLER: "parentbased_always_on"     # 시작 100%
  OTEL_METRICS_EXPORTER: "none"                    # 지표는 기존 Micrometer/Prometheus로
  OTEL_LOGS_EXPORTER: "none"
```

- `OTEL_METRICS_EXPORTER=none`: 지표 채널은 기존 Micrometer→Prometheus를 그대로 쓰고, agent는 **트레이스만** 보낸다(경로 이원화·중복 방지).
- 서비스 4개에 동일 패턴, `OTEL_SERVICE_NAME`만 다름.

**Tempo (`infra/load-test/observability/docker-compose.yml`)**
컨테이너 1개 추가. OTLP receiver 활성, 로컬 스토리지(실측 수명이라 보존 불필요).

```yaml
tempo:
  image: grafana/tempo:latest
  command: ["-config.file=/etc/tempo/tempo.yml"]
  ports: ["4317:4317"]        # host network 기준 OTLP gRPC
  volumes:
    - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
```

- `tempo.yml`: OTLP receiver(:4317) + local storage backend. 신규 파일 `observability/tempo/tempo.yml`.
- Prometheus 설정과 동일하게 **고정 사설 IP / host network** 전제.

**Grafana datasource (`observability/grafana/provisioning/datasources/tempo.yml`)**
신규 파일.

```yaml
apiVersion: 1
datasources:
  - name: Tempo
    type: tempo
    uid: tempo
    access: proxy
    url: http://tempo:3200
```

**배포 (`infra/load-test/deploy/ssm-deploy.sh`)**
obs 스택 배포에 Tempo가 자동 포함(같은 `docker-compose.yml`). 포트포워딩 헬퍼에 Tempo/Grafana 안내만 유지(Grafana를 통해 보므로 Tempo 직접 포워딩은 선택).

### 4.2 파트 B — 요청당 쿼리 수 (신규 `common-observability` 모듈)

**신규 모듈 `common-observability`** (`settings.gradle`에 `include 'common-observability'`)

의존(모듈 자체 `build.gradle`):
```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'          // Filter
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'         // DataSource
    implementation 'io.micrometer:micrometer-core'
    implementation 'net.ttddyy:datasource-proxy:1.10'                          // 버전 확정은 구현 시
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

클래스 2개 (+ 프로퍼티):

1. **`QueryCountProxyConfig`** — `@Configuration`, `@ConditionalOnProperty("loadtest.query-count.enabled")`.
   `BeanPostProcessor`로 `DataSource` 빈을 `ProxyDataSourceBuilder`로 래핑, `QueryCountStrategy`(스레드 로컬 카운터) 등록.

2. **`QueryCountFilter extends OncePerRequestFilter`** — `@ConditionalOnProperty` 동일.
   - `doFilterInternal`: `QueryCountHolder.clear()` → `chain.doFilter` → `finally`에서 카운트 읽어 Micrometer `DistributionSummary("db.queries.per_request")` 에 기록. 태그: `service`(프로퍼티/`spring.application.name`), `uri`(`HandlerMapping` best-match 패턴, cardinality 폭발 방지) → `QueryCountHolder.clear()`.

각 서비스 배선:
- `settings.gradle` + 각 서비스 `build.gradle`에 `implementation project(':common-observability')`.
- 각 서비스 `application.yml`(또는 load-test compose env)에 `loadtest.query-count.enabled` 플래그. 기본 false, 실측 시 `LOADTEST_QUERYCOUNT_ENABLED=true`로 켬 (relaxed binding: 대시 제거).

**지표 노출**: `DistributionSummary`는 기존 `micrometer-registry-prometheus`가 `/actuator/prometheus`로 자동 노출 → 기존 Prometheus scrape가 수집. 신규 배선 없음.

**Grafana 패널**: `uri`별 `db_queries_per_request` p95/avg. 예) `histogram_quantile(0.95, sum by(le,uri)(rate(db_queries_per_request_bucket[1m])))`. N+1은 특정 uri에서 곡선이 튄다.

---

## 5. 공존 · 왜곡 주의

1. **agent JDBC 계측 ∥ datasource-proxy**: 층이 다르다(agent=드라이버, proxy=DataSource 래핑). **쿼리 실행은 한 번**, 관측만 둘이 본다. 기능 충돌 없음. 오버헤드(관측 2중)만 유의.
2. **측정 왜곡**: 트레이싱 100% 샘플링 + proxy 래핑은 그 자체가 오버헤드. 실측 수치가 계측 없는 baseline과 벌어지면 (a) 샘플링 `traceidratio` 하향, (b) 쿼리카운트 플래그 분리 측정. **대시보드에 "계측 ON" 배지**를 남겨 baseline과 혼동 방지.
3. **cardinality**: `uri` 태그는 반드시 라우트 패턴(`/payments/{id}/cancel`)으로. 원시 path 금지.

---

## 6. 활성화 매트릭스 (opt-in 검증)

| 실행 컨텍스트 | OTel agent | 쿼리카운트 | 비고 |
|---|---|---|---|
| 평상시 `./gradlew bootRun` | OFF (env 없음) | OFF (플래그 false) | 영향 0 |
| CI 테스트 | OFF | OFF | 영향 0 |
| **실측 compose** | ON (`JAVA_TOOL_OPTIONS`) | ON (`LOADTEST_QUERYCOUNT_ENABLED=true`) | obs로 관측 |

---

## 7. 변경 파일 요약

**신규**
- `common-observability/build.gradle`
- `common-observability/src/main/java/.../QueryCountProxyConfig.java`
- `common-observability/src/main/java/.../QueryCountFilter.java`
- `infra/load-test/observability/tempo/tempo.yml`
- `infra/load-test/observability/grafana/provisioning/datasources/tempo.yml`

**수정**
- `settings.gradle` — `common-observability` include
- `payment/risk/merchant-limit/order-service/build.gradle` — 모듈 의존
- `infra/load-test/deploy/Dockerfile` — agent jar `ADD`
- `infra/load-test/deploy/{payment,risk,...}.compose.yml` — OTel env + 쿼리카운트 플래그
- `infra/load-test/observability/docker-compose.yml` — tempo 서비스
- (선택) Grafana 대시보드 JSON — 쿼리/트레이스 패널
- (선택) `docs/load-test/measurement-journey.md`, `CLAUDE.md` 관측 항목

---

## 8. 성공 기준

1. 실측 compose로 부하를 걸면 Grafana Tempo에서 payment→risk→merchant-limit **분산 트레이스 한 건**을 열 수 있고, 그 안에 JDBC/HTTP span이 보인다.
2. Grafana에 `uri`별 요청당 쿼리 수 패널이 실시간으로 그려진다.
3. 평상시 `bootRun`·CI 테스트에서 agent/쿼리카운트가 **뜨지 않는다**(env/플래그 없음).
4. 11편에서 netem으로 *추론*한 "느린 HTTP의 커넥션 점유"가, 이제 트레이스 span 폭으로 **직접 보인다**(선택 검증: merchant-limit netem 재현).
