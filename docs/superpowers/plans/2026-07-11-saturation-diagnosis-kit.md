# 포화 진단 키트 (185 rps 벽 규명) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 다음 AWS 실측에서 185 rps 포화 시점의 자원별 U/S/E를 한 화면에서 읽어 병목 자원을 규명하는 관측 키트를 만든다.

**Architecture:** 이미 스크레이프 중인 신호(node CPU, HikariCP, mysqld, 5xx)를 재사용하고, 공백 두 개만 새로 계측한다 — ① Tomcat 스레드 지표(payment/risk compose env로 opt-in), ② payment→risk 홉 지연(`RestTemplateBuilder`로 `http.client.requests` 자동 계측). 이 신호들을 USE method로 재편한 신규 Grafana 대시보드 + 런 절차/판정 트리 문서로 묶는다.

**Tech Stack:** Spring Boot(Micrometer, RestTemplateBuilder) · Grafana provisioning(JSON) · Docker Compose · Prometheus PromQL

## Global Constraints

- **전부 opt-in, 평상시 실행·CI 영향 0.** application.yml(상시 적용)은 건드리지 않는다. 활성은 load-test compose env로만.
- 기존 `infra/load-test/observability/grafana/dashboards/cancel-loadtest-overview.json`은 **수정 금지**(평상 모니터링용). 진단 뷰는 신규 파일.
- 새 계측은 **Tomcat 스레드(①)와 홉 지연(②) 둘뿐.** 나머지 신호는 재사용만.
- Tomcat env 이름: `SERVER_TOMCAT_MBEANREGISTRY_ENABLED`, 기본값 `false`, 대상은 **payment + risk만**.
- Prometheus 데이터소스 uid는 `"prometheus"`. 대시보드 JSON은 provisioning provider가 `dashboards/` 폴더에서 자동 로드.
- risk 클라이언트 경로는 고정 문자열(경로 변수 없음)이라 `http.client.requests`의 `uri` 태그 cardinality 안전 → 명시적 `Timer` 만들지 않는다.
- 새 exporter 추가·자동 병목 판정·부하 자동 스윕 스크립트·코드 프로파일러는 범위 밖(YAGNI).

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `payment-service/.../infrastructure/config/HttpClientConfig.java` | payment의 RestTemplate 빈 정의 | `new RestTemplate()` → `RestTemplateBuilder.build()` |
| `payment-service/src/test/.../infrastructure/config/HttpClientConfigMetricsTest.java` | 홉 지연 계측 회귀 테스트 | 신규 |
| `infra/load-test/deploy/payment.compose.yml` | payment 컨테이너 실측 구성 | Tomcat env 추가 |
| `infra/load-test/deploy/risk.compose.yml` | risk 컨테이너 실측 구성 | Tomcat env 추가 |
| `infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json` | USE 진단 대시보드 | 신규 |
| `docs/load-test/saturation-diagnosis.md` | 런 절차 + USE 판정 트리 | 신규 |

---

## Task 1: payment→risk 홉 지연 계측 (RestTemplateBuilder)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/HttpClientConfig.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/config/HttpClientConfigMetricsTest.java`

**Interfaces:**
- Consumes: Spring Boot 자동 구성 `RestTemplateBuilder` 빈(actuator의 client-observation 커스터마이저 포함).
- Produces: `RestTemplate` 빈이 호출 시 Micrometer `http.client.requests` 타이머를 발행. 대시보드(Task 3)가 `http_client_requests_seconds_bucket{service="payment"}`로 소비.

- [ ] **Step 1: 실패 테스트 작성**

`payment-service/src/test/java/com/example/payment/infrastructure/config/HttpClientConfigMetricsTest.java` 생성:

```java
package com.example.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 홉 지연 계측 회귀 테스트. RestTemplate 이 RestTemplateBuilder 로 생성돼야
 * Spring Boot 의 http.client.requests 자동 계측이 붙는다. new RestTemplate() 이면 미발행.
 * 인프라(DB/JPA/Kafka/Redis/Flyway) 자동구성은 제외해 컨테이너 없이 부팅한다.
 */
@SpringBootTest(
    classes = HttpClientConfigMetricsTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpClientConfigMetricsTest {

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate restTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void restTemplateEmitsHttpClientRequestsMetric() {
        restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);

        assertThat(meterRegistry.find("http.client.requests").timer())
            .as("RestTemplateBuilder 로 만든 RestTemplate 은 http.client.requests 를 발행해야 한다")
            .isNotNull();
        assertThat(meterRegistry.get("http.client.requests").timer().count())
            .isGreaterThanOrEqualTo(1L);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
    })
    @Import(HttpClientConfig.class)
    static class TestApp {

        @Bean
        PingController pingController() {
            return new PingController();
        }

        @RestController
        static class PingController {
            @GetMapping("/ping")
            String ping() {
                return "ok";
            }
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.infrastructure.config.HttpClientConfigMetricsTest'`
Expected: FAIL — `http.client.requests` 타이머가 null (현재 `new RestTemplate()` 은 계측 커스터마이저가 없어 미발행). AssertionError at `isNotNull()`.

> 컨텍스트 로드가 특정 자동구성(인프라 의존) 부재로 실패하면, 실패 메시지에 나온 해당 AutoConfiguration 클래스를 `exclude` 목록에 추가한다. 이는 환경 차이 보정이지 테스트 완화가 아니다.

- [ ] **Step 3: 최소 구현 — 빌더로 교체**

`HttpClientConfig.java` 전체를 아래로 교체:

```java
package com.example.payment.infrastructure.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    // RestTemplateBuilder 로 생성해야 Spring Boot 의 http.client.requests 자동 계측(관측 커스터마이저)이 붙는다.
    // new RestTemplate() 은 이 계측을 받지 못한다.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew :payment-service:test --tests 'com.example.payment.infrastructure.config.HttpClientConfigMetricsTest'`
Expected: PASS.

- [ ] **Step 5: payment 전체 테스트 회귀 확인**

Run: `./gradlew :payment-service:test`
Expected: PASS (기존 통합/단위 테스트 그린 유지 — 빈 타입은 그대로 `RestTemplate`, 주입 지점 영향 없음).

- [ ] **Step 6: 커밋**

```bash
git add payment-service/src/main/java/com/example/payment/infrastructure/config/HttpClientConfig.java \
        payment-service/src/test/java/com/example/payment/infrastructure/config/HttpClientConfigMetricsTest.java
git commit -m "feat(obs): payment RestTemplate을 빌더로 생성 — http.client.requests 홉 지연 계측"
```

---

## Task 2: Tomcat 스레드 지표 opt-in (compose env)

**Files:**
- Modify: `infra/load-test/deploy/payment.compose.yml`
- Modify: `infra/load-test/deploy/risk.compose.yml`

**Interfaces:**
- Produces: `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`일 때 `tomcat_threads_busy_threads` / `tomcat_threads_config_max_threads` 노출. 대시보드(Task 3)가 소비.

- [ ] **Step 1: payment.compose.yml 에 env 추가**

`LOADTEST_QUERYCOUNT_ENABLED` 줄 바로 아래에 추가(같은 `environment:` 블록, 동일 들여쓰기 6칸):

```yaml
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
      # Tomcat 스레드 지표(tomcat_threads_busy/config_max) opt-in — 포화 진단 런에서만 true
      SERVER_TOMCAT_MBEANREGISTRY_ENABLED: "${SERVER_TOMCAT_MBEANREGISTRY_ENABLED:-false}"
```

- [ ] **Step 2: risk.compose.yml 에 env 추가**

`LOADTEST_QUERYCOUNT_ENABLED` 줄 바로 아래(그 밑의 `RISK_LIMIT_*` 주석 블록 위)에 동일하게 추가:

```yaml
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
      # Tomcat 스레드 지표(tomcat_threads_busy/config_max) opt-in — 포화 진단 런에서만 true
      SERVER_TOMCAT_MBEANREGISTRY_ENABLED: "${SERVER_TOMCAT_MBEANREGISTRY_ENABLED:-false}"
```

- [ ] **Step 3: compose 병합 파싱 검증 (기본값 false)**

Run:
```bash
IMAGE_NS=x docker compose -f infra/load-test/deploy/payment.compose.yml config | grep -i mbeanregistry
IMAGE_NS=x docker compose -f infra/load-test/deploy/risk.compose.yml config | grep -i mbeanregistry
```
Expected: 각 명령이 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED: "false"` 를 출력(YAML 유효 + 기본값 적용).

- [ ] **Step 4: opt-in 오버라이드 검증 (true 주입)**

Run:
```bash
IMAGE_NS=x SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true docker compose -f infra/load-test/deploy/payment.compose.yml config | grep -i mbeanregistry
```
Expected: `SERVER_TOMCAT_MBEANREGISTRY_ENABLED: "true"` (env 오버라이드 동작 확인).

- [ ] **Step 5: 커밋**

```bash
git add infra/load-test/deploy/payment.compose.yml infra/load-test/deploy/risk.compose.yml
git commit -m "feat(obs): payment/risk compose에 Tomcat 스레드 지표 opt-in env 추가"
```

---

## Task 3: 포화 진단 대시보드 (신규 JSON)

**Files:**
- Create: `infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json`

**Interfaces:**
- Consumes: `http_server_requests_seconds_count`, `node_cpu_seconds_total`, `tomcat_threads_busy_threads`/`tomcat_threads_config_max_threads`(Task 2), `hikaricp_connections_*`, `http_client_requests_seconds_bucket`(Task 1), `mysql_global_status_*`. Prometheus 데이터소스 uid `"prometheus"`, 타겟 라벨 `service`/`host`/`db`.

- [ ] **Step 1: 대시보드 JSON 생성**

`infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json`:

```json
{
  "uid": "saturation-diagnosis",
  "title": "포화 진단 — USE (185 rps 벽)",
  "tags": ["load-test", "saturation", "use"],
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "5s",
  "time": { "from": "now-15m", "to": "now" },
  "templating": { "list": [] },
  "annotations": { "list": [] },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "기준 rps (payment/risk) — 평탄 지점이 포화",
      "description": "이 곡선이 평평해진 구간이 진단 대상. VU를 올려도 rps가 안 오르면 그 시점의 U/S/E를 읽는다.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
      "fieldConfig": { "defaults": { "unit": "reqps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (service) (rate(http_server_requests_seconds_count{service=~\"payment|risk\"}[1m]))",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "[U] payment/risk 호스트 CPU 사용률",
      "description": "1에 근접 = CPU 벽. node-exporter idle 기반.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "1 - avg by (host) (rate(node_cpu_seconds_total{mode=\"idle\",host=~\"payment|risk\"}[1m]))",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "[U] Tomcat 스레드 사용률 (busy/max)",
      "description": "opt-in: SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true 필요. 1 근접 = 스레드 벽.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "tomcat_threads_busy_threads / tomcat_threads_config_max_threads",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "[S] Tomcat busy vs max (스레드 포화)",
      "description": "busy 가 max 에 닿으면 요청이 스레드를 기다림 = 포화.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "tomcat_threads_busy_threads",
          "legendFormat": "{{service}} busy"
        },
        {
          "refId": "B",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "tomcat_threads_config_max_threads",
          "legendFormat": "{{service}} max"
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "[U] HikariCP 사용률 (active/max, 기본 max=10)",
      "description": "1 근접 = 풀 벽. 기본 풀 크기 10 미설정 상태 유의.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 16 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "hikaricp_connections_active / hikaricp_connections_max",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "[S] HikariCP pending (연결 대기 = 풀 포화)",
      "description": "pending > 0 지속 + active==max = 풀 병목 확정.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 16 },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "hikaricp_connections_pending",
          "legendFormat": "{{service}}"
        }
      ]
    },
    {
      "id": 7,
      "type": "timeseries",
      "title": "[홉] payment→risk 클라이언트 지연 p95 (uri별)",
      "description": "RestTemplateBuilder 계측. 아무 자원도 100%가 아닌데 이 지연이 rps를 규정하면 동기 홉 직렬화.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 24, "x": 0, "y": 24 },
      "fieldConfig": { "defaults": { "unit": "s" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "histogram_quantile(0.95, sum by (le, uri) (rate(http_client_requests_seconds_bucket{service=\"payment\"}[1m])))",
          "legendFormat": "{{uri}}"
        }
      ]
    },
    {
      "id": 8,
      "type": "timeseries",
      "title": "[S] MySQL threads_running (payment/risk)",
      "description": "급증 = DB 동시 실행 포화.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 32 },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "mysql_global_status_threads_running{db=~\"payment|risk\"}",
          "legendFormat": "{{db}}"
        }
      ]
    },
    {
      "id": 9,
      "type": "timeseries",
      "title": "[U] MySQL QPS (payment/risk)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 32 },
      "fieldConfig": { "defaults": { "unit": "short" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "rate(mysql_global_status_queries{db=~\"payment|risk\"}[1m])",
          "legendFormat": "{{db}}"
        }
      ]
    },
    {
      "id": 10,
      "type": "timeseries",
      "title": "[U] DB 호스트 CPU (mysql-payment/mysql-risk)",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 40 },
      "fieldConfig": { "defaults": { "unit": "percentunit", "min": 0, "max": 1 }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "1 - avg by (host) (rate(node_cpu_seconds_total{mode=\"idle\",host=~\"mysql-payment|mysql-risk\"}[1m]))",
          "legendFormat": "{{host}}"
        }
      ]
    },
    {
      "id": 11,
      "type": "timeseries",
      "title": "[E] 5xx 에러율 (payment/risk)",
      "description": "포화 구간에서 거부(에러)로 전환되는지 = 가용성 vs 지연 판별.",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 40 },
      "fieldConfig": { "defaults": { "unit": "reqps" }, "overrides": [] },
      "targets": [
        {
          "refId": "A",
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "expr": "sum by (service) (rate(http_server_requests_seconds_count{status=~\"5..\",service=~\"payment|risk\"}[1m]))",
          "legendFormat": "{{service}}"
        }
      ]
    }
  ]
}
```

- [ ] **Step 2: JSON 유효성 검증**

Run:
```bash
python3 -m json.tool infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json > /dev/null && echo "JSON OK"
```
Expected: `JSON OK` (파싱 성공, 문법 오류 없음).

- [ ] **Step 3: 필수 속성/패널 수 검증**

Run:
```bash
python3 -c "import json;d=json.load(open('infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json'));print('uid',d['uid']);print('panels',len(d['panels']));assert d['uid']=='saturation-diagnosis';assert len(d['panels'])==11;assert all(t['datasource']['uid']=='prometheus' for p in d['panels'] for t in p['targets'])"
```
Expected: `uid saturation-diagnosis` / `panels 11`, assert 통과(모든 타겟이 prometheus 데이터소스).

- [ ] **Step 4: overview 대시보드 불변 확인**

Run: `git status --porcelain infra/load-test/observability/grafana/dashboards/cancel-loadtest-overview.json`
Expected: 출력 없음(수정 안 됨).

- [ ] **Step 5: 커밋**

```bash
git add infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json
git commit -m "feat(obs): 포화 진단 USE 대시보드 신규 추가"
```

---

## Task 4: 런 절차 + USE 판정 트리 문서

**Files:**
- Create: `docs/load-test/saturation-diagnosis.md`

**Interfaces:**
- Consumes: Task 1~3의 산출물(홉 지연 지표, Tomcat env, 진단 대시보드)을 운영 절차로 묶음.

- [ ] **Step 1: 문서 작성**

`docs/load-test/saturation-diagnosis.md`:

```markdown
# 포화 진단 (185 rps 벽 규명) — 런 절차 + USE 판정 트리

3-config 스윕이 A/B/C 모두 VU400까지 ~185rps에서 포화함을 보였다(구성 독립 천장, 병목=payment→risk 동기 홉 추정). 이 문서는 그 185에서 **어느 자원이 먼저 포화하는지**를 관측으로 규명하는 절차다.

관련: `docs/load-test/measurement-journey.md`(3-config 실험), 대시보드 `saturation-diagnosis`(Grafana), 설계 `docs/superpowers/specs/2026-07-11-saturation-diagnosis-kit-design.md`.

## 사전 조건

- obs 스택 온디맨드 기동(spot=false) — `docs/load-test/measurement-journey.md` §7 배포 절차.
- payment/risk 를 진단 env 로 재기동:
  ```bash
  SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true \
    docker compose -f payment.compose.yml -f risk.compose.yml up -d --force-recreate
  ```
- **쿼리카운트는 OFF 로 둔다**(`LOADTEST_QUERYCOUNT_ENABLED=false`, 기본값). datasource-proxy 래핑이 `hikaricp_*` 메트릭 바인딩을 가릴 위험이 있고, 풀 지표가 진단의 핵심이기 때문. (이 상호작용은 스모크 때 1회 검증 대상.)

## 런 절차

1. Grafana `포화 진단 — USE` 대시보드를 연다(uid `saturation-diagnosis`).
2. k6 VU 를 단계적으로 올리며(예: 100→200→400) `기준 rps` 패널이 **평탄해지는 지점**을 찾는다(≈185 예상).
3. rps 가 평평한 정상상태 구간에서 아래 신호를 **동시에** 읽는다(캡처).

## USE 판정 트리

포화 구간에서 각 자원의 U(사용률)/S(포화)/E(에러)를 읽고, 아래 순서로 벽을 특정한다.

- **risk 호스트 CPU ≈ 100%** → risk 연산 벽. 대응: risk 서버 증설(락 제거로 수평 확장 이미 가능).
- **HikariCP pending > 0 & active == max(10)** → 풀 벽. 대응: 풀 크기 상향 또는 TX 내 HTTP 체류 단축(limit 해석 TX 밖으로).
- **Tomcat busy == max** → 스레드 벽. 대응: 스레드풀/커넥터 튜닝.
- **MySQL threads_running 급증 / row lock waits 상승** → DB 벽. 대응: 쿼리·인덱스·락 범위 점검.
- **어느 자원도 100%가 아닌데 `홉 지연` 이 rps 를 규정**(홉 p95 × 동시성 ≈ 관측 rps) → **동기 홉 직렬화**. 대응: 홉 비동기화 또는 홉 축소. 3-config 가 가리킨 가설의 확증.

## 교차 확인 (Tempo)

Prometheus 신호로 벽을 좁힌 뒤, Tempo 트레이스에서 payment→risk→merchant-limit span 폭으로 홉 지연을 육안 확인한다. merchant-limit 에 netem 을 걸면(measurement-journey §netem) 커넥션 점유가 span 폭 증가로 보이는지 검증할 수 있다.

## 한계

- 자동 판정은 하지 않는다 — 사람이 대시보드 + 위 트리로 읽는다.
- 실제 곡선은 AWS 실측 때만 나온다. 키트(대시보드/env/계측)는 코드로 완성돼 있다.
```

- [ ] **Step 2: 문서 유효성 확인**

Run:
```bash
test -f docs/load-test/saturation-diagnosis.md && grep -c "판정 트리\|쿼리카운트는 OFF\|saturation-diagnosis" docs/load-test/saturation-diagnosis.md
```
Expected: 파일 존재 + 매치 카운트 ≥ 3(핵심 섹션 포함 확인).

- [ ] **Step 3: 커밋**

```bash
git add docs/load-test/saturation-diagnosis.md
git commit -m "docs(load-test): 포화 진단 런 절차 + USE 판정 트리"
```

---

## 실측 스모크 추가 항목 (다음 AWS 런 참고, 이번 구현 범위 밖)

키트는 코드로 완성되지만 곡선은 실측 때 확인. 다음 온디맨드 런에서:
1. `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true` 로 재기동 후 `포화 진단` 대시보드의 Tomcat 패널에 곡선이 그려지는지(지표 노출 확인).
2. `http.client.requests` 홉 지연 패널에 payment 의 risk/PG uri 곡선이 나오는지.
3. **쿼리카운트 ON ↔ HikariCP 지표 상호작용 검증:** `LOADTEST_QUERYCOUNT_ENABLED=true` 로 켠 상태에서 `hikaricp_*` 패널이 여전히 나오는지 1회 확인(ProxyDataSource 래핑이 Hikari 지표 바인딩을 가리지 않는지).

---

## Self-Review

- **Spec coverage:** ① Tomcat env(Task 2) ✓ / ② 홉 지연 RestTemplateBuilder(Task 1) + Tempo(교차확인, Task 4 문서) ✓ / ③ 신규 대시보드(Task 3) ✓ / 절차+판정 트리+쿼리카운트 회피(Task 4) ✓ / opt-in·overview 불변·application.yml 불변(Global Constraints + Task 3 Step 4) ✓ / 검증 전략(각 Task의 실행 Step) ✓.
- **Placeholder scan:** 코드/JSON/문서 전문 포함, TBD 없음.
- **Type consistency:** 지표 이름 일관 — `tomcat_threads_busy_threads`/`tomcat_threads_config_max_threads`, `hikaricp_connections_active`/`_max`/`_pending`, `http_client_requests_seconds_bucket{service="payment"}`, env `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` 전 Task 동일 표기.
```
