# Load-test White-box Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실측판(AWS)에 OTel agent→Grafana Tempo 분산 트레이싱과 datasource-proxy 기반 요청당 쿼리 수 메트릭을 추가하되, 평상시·CI 실행엔 영향 0 (전부 opt-in).

**Architecture:** 신규 `common-observability` 라이브러리 모듈이 datasource-proxy로 `DataSource`를 래핑하고 `OncePerRequestFilter`가 요청당 쿼리 수를 Micrometer `DistributionSummary`로 발행 → 기존 Prometheus/Grafana가 수집. 트레이싱은 OTel Java agent를 deploy 이미지에 굽고 load-test compose env로만 활성화 → OTLP로 obs 인스턴스의 신규 Tempo 컨테이너 직결 → Grafana Tempo 데이터소스.

**Tech Stack:** Java 21 · Spring Boot 4.0.5 · Micrometer · net.ttddyy:datasource-proxy 1.10 · OpenTelemetry Java agent 2.11.0 · Grafana Tempo · Docker Compose

## Global Constraints

- Java 21, Spring Boot 4.0.5. 루트 `build.gradle`의 `subprojects` 블록이 모든 서브프로젝트에 `org.springframework.boot` 플러그인을 적용한다 → 라이브러리 모듈은 반드시 `bootJar { enabled = false }`, `jar { enabled = true }`.
- 버전 핀: `net.ttddyy:datasource-proxy:1.10` (Spring 의존성 관리 밖 → 명시), OTel agent `2.11.0` (구현 시 최신 2.x 확인 후 확정).
- 공통 모듈 클래스는 서비스의 `@SpringBootApplication` 컴포넌트 스캔 범위(`com.example.<svc>`) **밖**이다 → 반드시 **Spring Boot 자동설정**(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)으로 로드. 컴포넌트 스캔에 의존 금지.
- 모든 계측은 **opt-in**: 쿼리카운트는 프로퍼티 `loadtest.query-count.enabled`(기본 false), 트레이싱은 compose env `OTEL_JAVAAGENT`(기본 미설정). 평상시 `bootRun`·CI에서 뜨지 않아야 한다.
- 프로퍼티↔env 매핑: `loadtest.query-count.enabled` ↔ 환경변수 **`LOADTEST_QUERYCOUNT_ENABLED`** (Spring relaxed binding: 점→언더스코어, 대시 제거, 대문자). compose에서 이 이름을 정확히 쓸 것.
- Micrometer `uri` 태그는 반드시 **라우트 패턴**(`/payments/{id}/cancel`) — 원시 path 금지(cardinality 폭발).
- 메트릭 이름 `db.queries.per_request` (Prometheus 노출 시 `db_queries_per_request`). 태그: `service`, `uri`.

---

### Task 1: `common-observability` 모듈 + 쿼리 카운트 리더

**Files:**
- Modify: `settings.gradle`
- Create: `common-observability/build.gradle`
- Create: `common-observability/src/main/java/com/example/common/observability/querycount/QueryCountReader.java`
- Create: `common-observability/src/main/java/com/example/common/observability/querycount/DataSourceProxyQueryCountReader.java`
- Test: `common-observability/src/test/java/com/example/common/observability/querycount/DataSourceProxyQueryCountReaderTest.java`

**Interfaces:**
- Produces: `QueryCountReader` — `long readAndReset()` (현재 스레드에 누적된 실행 쿼리 총수를 읽고 카운터를 0으로 clear). 구현체 `DataSourceProxyQueryCountReader`.

- [ ] **Step 1: `settings.gradle`에 모듈 추가**

`settings.gradle` 맨 아래 `include` 목록에 한 줄 추가:

```gradle
include 'common-observability'
```

- [ ] **Step 2: 모듈 `build.gradle` 작성**

Create `common-observability/build.gradle` — 라이브러리이므로 bootJar 비활성, datasource-proxy·H2 추가:

```gradle
// common-observability — 부하 실측용 관측 유틸(요청당 쿼리 수). 라이브러리 모듈.
// 루트 subprojects 블록이 spring-boot 플러그인/공통 의존을 이미 적용한다.

bootJar { enabled = false }
jar { enabled = true }

dependencies {
    // 요청당 쿼리 수 계측 (Spring 의존성 관리 밖 → 버전 명시)
    implementation 'net.ttddyy:datasource-proxy:1.10'

    // 카운팅 검증용 인메모리 DB
    testImplementation 'com.h2database:h2'
}
```

- [ ] **Step 3: `QueryCountReader` 인터페이스 작성**

Create `.../querycount/QueryCountReader.java`:

```java
package com.example.common.observability.querycount;

/**
 * 현재 스레드(=요청 스레드)에 누적된 실행 쿼리 수를 읽고 카운터를 리셋한다.
 * 요청-스레드 1:1 + Hikari 블로킹 모델 전제.
 */
public interface QueryCountReader {
    /** 누적 쿼리 총수를 반환하고 카운터를 0으로 clear. */
    long readAndReset();
}
```

- [ ] **Step 4: 실패하는 테스트 작성 (H2로 실제 카운트 검증)**

Create `.../querycount/DataSourceProxyQueryCountReaderTest.java`:

```java
package com.example.common.observability.querycount;

import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceProxyQueryCountReaderTest {

    @Test
    void countsExecutedQueriesAndResets() throws Exception {
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:qctest;DB_CLOSE_DELAY=-1");

        // 스키마 준비는 raw로 (카운트 대상 아님)
        try (Connection c = raw.getConnection()) {
            c.createStatement().execute("CREATE TABLE t(id INT)");
        }

        DataSource proxy = ProxyDataSourceBuilder.create(raw)
                .name("main")
                .listener(new DataSourceQueryCountListener())
                .build();

        QueryCountHolder.clear();
        try (Connection c = proxy.getConnection()) {
            for (int i = 0; i < 3; i++) {
                c.createStatement().executeQuery("SELECT 1");
            }
        }

        QueryCountReader reader = new DataSourceProxyQueryCountReader();
        assertThat(reader.readAndReset()).isEqualTo(3L);
        // 리셋 확인
        assertThat(reader.readAndReset()).isEqualTo(0L);
    }
}
```

- [ ] **Step 5: 테스트 실패 확인**

Run: `./gradlew :common-observability:test --tests "*DataSourceProxyQueryCountReaderTest"`
Expected: FAIL — `DataSourceProxyQueryCountReader` 클래스 없음 (compile error).

- [ ] **Step 6: 구현체 작성**

Create `.../querycount/DataSourceProxyQueryCountReader.java`:

```java
package com.example.common.observability.querycount;

import net.ttddyy.dsproxy.QueryCountHolder;

/**
 * datasource-proxy의 스레드 로컬 QueryCountHolder에서 현재 스레드 누적 쿼리 수를 읽는다.
 */
public class DataSourceProxyQueryCountReader implements QueryCountReader {

    @Override
    public long readAndReset() {
        long total = QueryCountHolder.getGrandTotal().getTotal();
        QueryCountHolder.clear();
        return total;
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :common-observability:test --tests "*DataSourceProxyQueryCountReaderTest"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add settings.gradle common-observability/build.gradle \
  common-observability/src/main/java/com/example/common/observability/querycount/QueryCountReader.java \
  common-observability/src/main/java/com/example/common/observability/querycount/DataSourceProxyQueryCountReader.java \
  common-observability/src/test/java/com/example/common/observability/querycount/DataSourceProxyQueryCountReaderTest.java
git commit -m "feat(obs): common-observability 모듈 + 요청당 쿼리 카운트 리더"
```

---

### Task 2: 요청당 쿼리 수 필터 (Micrometer 발행)

**Files:**
- Create: `common-observability/src/main/java/com/example/common/observability/querycount/QueryCountFilter.java`
- Test: `common-observability/src/test/java/com/example/common/observability/querycount/QueryCountFilterTest.java`

**Interfaces:**
- Consumes: `QueryCountReader.readAndReset()` (Task 1)
- Produces: `QueryCountFilter(QueryCountReader reader, MeterRegistry registry, String service)` — `OncePerRequestFilter`. 요청당 `DistributionSummary("db.queries.per_request")` 를 `service`/`uri` 태그로 기록.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `.../querycount/QueryCountFilterTest.java`:

```java
package com.example.common.observability.querycount;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCountFilterTest {

    /** readAndReset()가 [시작=0, 종료=N] 순으로 값을 내는 가짜 리더. */
    static class FakeReader implements QueryCountReader {
        private final long[] values;
        private int idx = 0;
        FakeReader(long... values) { this.values = values; }
        @Override public long readAndReset() { return values[idx++]; }
    }

    @Test
    void recordsQueryCountWithRoutePatternTag() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryCountFilter filter = new QueryCountFilter(new FakeReader(0, 7), registry, "payment");

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/payments/1/cancel");
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/payments/{id}/cancel");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {});

        DistributionSummary ds = registry.get("db.queries.per_request")
                .tag("service", "payment")
                .tag("uri", "/payments/{id}/cancel")
                .summary();
        assertThat(ds.count()).isEqualTo(1L);
        assertThat(ds.totalAmount()).isEqualTo(7.0);
    }

    @Test
    void unmappedRequestNotRecorded() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryCountFilter filter = new QueryCountFilter(new FakeReader(0, 3), registry, "payment");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/prometheus");
        // BEST_MATCHING_PATTERN_ATTRIBUTE 미설정 → 라우트 패턴 없음
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (rq, rs) -> {});

        assertThat(registry.find("db.queries.per_request").summary()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :common-observability:test --tests "*QueryCountFilterTest"`
Expected: FAIL — `QueryCountFilter` 클래스 없음.

- [ ] **Step 3: 필터 구현**

Create `.../querycount/QueryCountFilter.java`:

```java
package com.example.common.observability.querycount;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 요청 스레드의 누적 쿼리 수를 요청당 DistributionSummary로 기록한다.
 * 라우트 패턴이 없는 요청(정적/actuator/404)은 cardinality 보호를 위해 기록하지 않는다.
 */
public class QueryCountFilter extends OncePerRequestFilter {

    private final QueryCountReader reader;
    private final MeterRegistry registry;
    private final String service;

    public QueryCountFilter(QueryCountReader reader, MeterRegistry registry, String service) {
        this.reader = reader;
        this.registry = registry;
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        reader.readAndReset(); // 요청 시작 시 잔여 카운트 제거
        try {
            chain.doFilter(request, response);
        } finally {
            long count = reader.readAndReset();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null) {
                DistributionSummary.builder("db.queries.per_request")
                        .tag("service", service)
                        .tag("uri", pattern.toString())
                        .publishPercentileHistogram()
                        .register(registry)
                        .record(count);
            }
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :common-observability:test --tests "*QueryCountFilterTest"`
Expected: PASS (2개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add common-observability/src/main/java/com/example/common/observability/querycount/QueryCountFilter.java \
  common-observability/src/test/java/com/example/common/observability/querycount/QueryCountFilterTest.java
git commit -m "feat(obs): 요청당 쿼리 수 필터 → Micrometer DistributionSummary"
```

---

### Task 3: 자동설정 + opt-in 게이팅

**Files:**
- Create: `common-observability/src/main/java/com/example/common/observability/querycount/QueryCountAutoConfiguration.java`
- Create: `common-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `common-observability/src/test/java/com/example/common/observability/querycount/QueryCountAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `QueryCountReader` (Task 1), `QueryCountFilter` (Task 2)
- Produces: `QueryCountAutoConfiguration` — `loadtest.query-count.enabled=true`일 때만 `QueryCountReader`·`QueryCountFilter` 빈 + `DataSource` 래핑 BeanPostProcessor 등록.

- [ ] **Step 1: 실패하는 게이팅 테스트 작성**

Create `.../querycount/QueryCountAutoConfigurationTest.java`:

```java
package com.example.common.observability.querycount;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCountAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QueryCountAutoConfiguration.class))
            .withBean("meterRegistry", SimpleMeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void beansPresentWhenEnabled() {
        runner.withPropertyValues("loadtest.query-count.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(QueryCountFilter.class);
                    assertThat(ctx).hasSingleBean(QueryCountReader.class);
                });
    }

    @Test
    void beansAbsentByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(QueryCountFilter.class);
            assertThat(ctx).doesNotHaveBean(QueryCountReader.class);
        });
    }

    @Test
    void beansAbsentWhenDisabled() {
        runner.withPropertyValues("loadtest.query-count.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(QueryCountFilter.class));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :common-observability:test --tests "*QueryCountAutoConfigurationTest"`
Expected: FAIL — `QueryCountAutoConfiguration` 클래스 없음.

- [ ] **Step 3: 자동설정 작성**

Create `.../querycount/QueryCountAutoConfiguration.java`:

```java
package com.example.common.observability.querycount;

import io.micrometer.core.instrument.MeterRegistry;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * loadtest.query-count.enabled=true 일 때만 활성. 평상시/CI 영향 0.
 * DataSource를 datasource-proxy로 래핑하고, 요청당 쿼리 수 필터를 등록한다.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "loadtest.query-count", name = "enabled", havingValue = "true")
public class QueryCountAutoConfiguration {

    @Bean
    public QueryCountReader queryCountReader() {
        return new DataSourceProxyQueryCountReader();
    }

    @Bean
    public QueryCountFilter queryCountFilter(QueryCountReader reader,
                                             MeterRegistry registry,
                                             @Value("${spring.application.name:unknown}") String service) {
        return new QueryCountFilter(reader, registry, service);
    }

    /** DataSource 빈을 ProxyDataSource로 래핑 (static: BPP는 조기 등록돼야 함). */
    @Bean
    public static BeanPostProcessor queryCountDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds)
                            .name("main")
                            .listener(new DataSourceQueryCountListener())
                            .build();
                }
                return bean;
            }
        };
    }
}
```

- [ ] **Step 4: 자동설정 등록 파일 작성**

Create `common-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (단일 줄, 개행 포함):

```
com.example.common.observability.querycount.QueryCountAutoConfiguration
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :common-observability:test`
Expected: PASS (Task 1~3 전체)

- [ ] **Step 6: 커밋**

```bash
git add common-observability/src/main/java/com/example/common/observability/querycount/QueryCountAutoConfiguration.java \
  common-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  common-observability/src/test/java/com/example/common/observability/querycount/QueryCountAutoConfigurationTest.java
git commit -m "feat(obs): 쿼리카운트 자동설정 + opt-in 게이팅(loadtest.query-count.enabled)"
```

---

### Task 4: 4개 서비스에 모듈 배선

**Files:**
- Modify: `payment-service/build.gradle`
- Modify: `risk-management-service/build.gradle`
- Modify: `merchant-limit-service/build.gradle`
- Modify: `order-service/build.gradle`

**Interfaces:**
- Consumes: `common-observability` 모듈 (Task 1~3). 자동설정이라 코드 배선 불필요, 의존만 추가.

- [ ] **Step 1: 각 서비스 build.gradle에 모듈 의존 추가**

네 파일 각각의 `dependencies { }` 블록에 다음 한 줄 추가 (블록이 없으면 파일 하단에 생성). 루트 subprojects 의존에 **더해지는** 것이므로 다른 의존은 건드리지 않는다:

`payment-service/build.gradle`:
```gradle
dependencies {
    implementation project(':common-observability')
}
```

`risk-management-service/build.gradle`:
```gradle
dependencies {
    implementation project(':common-observability')
}
```

`merchant-limit-service/build.gradle`:
```gradle
dependencies {
    implementation project(':common-observability')
}
```

`order-service/build.gradle`:
```gradle
dependencies {
    implementation project(':common-observability')
}
```

> 주의: 서비스 build.gradle에 이미 `dependencies { }` 블록이 있으면 그 안에 `implementation project(':common-observability')` 한 줄만 추가한다(새 블록 중복 생성 금지). 없으면 위처럼 새 블록을 추가한다.

- [ ] **Step 2: 컴파일/조립 검증 (플래그 off → 영향 0)**

Run: `./gradlew :payment-service:compileJava :risk-management-service:compileJava :merchant-limit-service:compileJava :order-service:compileJava`
Expected: BUILD SUCCESSFUL

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL (전체 조립). 전체 테스트 스위트(Testcontainers 필요)는 CI에서 확인.

- [ ] **Step 3: 자동설정이 기본 비활성인지 재확인**

플래그를 안 켰으므로 서비스 부팅 시 `QueryCountFilter`가 생성되지 않아야 한다. Task 3의 `beansAbsentByDefault` 테스트가 이미 이를 보장한다(모듈 레벨). 서비스 레벨 추가 검증 불필요.

- [ ] **Step 4: 커밋**

```bash
git add payment-service/build.gradle risk-management-service/build.gradle \
  merchant-limit-service/build.gradle order-service/build.gradle
git commit -m "feat(obs): 4개 서비스에 common-observability 의존 배선(기본 비활성)"
```

---

### Task 5: OTel Java agent — 이미지 + compose 활성화

**Files:**
- Modify: `infra/load-test/deploy/Dockerfile`
- Modify: `infra/load-test/deploy/payment.compose.yml`
- Modify: `infra/load-test/deploy/risk.compose.yml`
- Modify: `infra/load-test/deploy/cold-svc.compose.yml`

**Interfaces:**
- Produces: agent jar가 이미지 `/otel/opentelemetry-javaagent.jar`에 존재. compose env `OTEL_JAVAAGENT` 설정 시에만 활성. 트레이스는 `http://10.0.1.50:4317`(obs)로.

- [ ] **Step 1: Dockerfile 런타임 스테이지에 agent jar 굽기**

`infra/load-test/deploy/Dockerfile`의 마지막 `ENTRYPOINT` **바로 앞**에 다음을 삽입 (ENTRYPOINT는 그대로 두어 평상시 미활성):

```dockerfile
# OTel Java agent — 이미지에 굽되 -javaagent 는 미설정. 활성화는 compose env(OTEL_JAVAAGENT).
ARG OTEL_AGENT_VERSION=2.11.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
```

최종 런타임 스테이지 형태 확인:
```dockerfile
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/build/libs/*.jar /app/app.jar
ARG OTEL_AGENT_VERSION=2.11.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: `payment.compose.yml` env 교체**

`payment-service`의 `environment:` 에서 `JAVA_TOOL_OPTIONS` 줄을 아래로 교체하고 OTel·쿼리카운트 env를 추가한다. `${OTEL_JAVAAGENT:-}`가 비어 있으면 agent 미활성:

```yaml
      JAVA_TOOL_OPTIONS: "-Xmx4g -XX:+UseG1GC ${OTEL_JAVAAGENT:-}"
      OTEL_SERVICE_NAME: "payment"
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"
      OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
      OTEL_TRACES_SAMPLER: "${OTEL_TRACES_SAMPLER:-parentbased_always_on}"
      OTEL_METRICS_EXPORTER: "none"
      OTEL_LOGS_EXPORTER: "none"
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
```

- [ ] **Step 3: `risk.compose.yml` env 교체**

`risk-management-service`의 `JAVA_TOOL_OPTIONS` 줄을 교체하고 아래를 추가 (기존 `RISK_LIMIT_*` 토글 env는 그대로 유지):

```yaml
      JAVA_TOOL_OPTIONS: "-Xmx4g -XX:+UseG1GC ${OTEL_JAVAAGENT:-}"
      OTEL_SERVICE_NAME: "risk"
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"
      OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
      OTEL_TRACES_SAMPLER: "${OTEL_TRACES_SAMPLER:-parentbased_always_on}"
      OTEL_METRICS_EXPORTER: "none"
      OTEL_LOGS_EXPORTER: "none"
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
```

- [ ] **Step 4: `cold-svc.compose.yml` env 교체 (2개 서비스)**

`merchant-limit-service`의 `JAVA_TOOL_OPTIONS` 줄 교체 + 추가:

```yaml
      JAVA_TOOL_OPTIONS: "-Xmx2g -XX:+UseG1GC ${OTEL_JAVAAGENT:-}"
      OTEL_SERVICE_NAME: "merchant-limit"
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"
      OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
      OTEL_TRACES_SAMPLER: "${OTEL_TRACES_SAMPLER:-parentbased_always_on}"
      OTEL_METRICS_EXPORTER: "none"
      OTEL_LOGS_EXPORTER: "none"
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
```

`order-service`의 `JAVA_TOOL_OPTIONS` 줄 교체 + 추가:

```yaml
      JAVA_TOOL_OPTIONS: "-Xmx2g -XX:+UseG1GC ${OTEL_JAVAAGENT:-}"
      OTEL_SERVICE_NAME: "order"
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"
      OTEL_EXPORTER_OTLP_PROTOCOL: "grpc"
      OTEL_TRACES_SAMPLER: "${OTEL_TRACES_SAMPLER:-parentbased_always_on}"
      OTEL_METRICS_EXPORTER: "none"
      OTEL_LOGS_EXPORTER: "none"
      LOADTEST_QUERYCOUNT_ENABLED: "${LOADTEST_QUERYCOUNT_ENABLED:-false}"
```

- [ ] **Step 5: compose 유효성 검증**

Run: `cd infra/load-test/deploy && IMAGE_NS=dummy docker compose -f payment.compose.yml config -q && IMAGE_NS=dummy docker compose -f risk.compose.yml config -q && IMAGE_NS=dummy docker compose -f cold-svc.compose.yml config -q`
Expected: 출력 없음(유효). 에러 없으면 통과.

Run: `grep -c "opentelemetry-javaagent.jar" infra/load-test/deploy/Dockerfile`
Expected: `1`

> Dockerfile 빌드 자체(agent 다운로드)는 네트워크·buildx가 필요하므로 CI(loadtest-images.yml)에서 검증. `docker compose config`로 env 보간만 로컬 검증.

- [ ] **Step 6: 커밋**

```bash
git add infra/load-test/deploy/Dockerfile infra/load-test/deploy/payment.compose.yml \
  infra/load-test/deploy/risk.compose.yml infra/load-test/deploy/cold-svc.compose.yml
git commit -m "feat(obs): OTel Java agent 이미지 굽기 + compose 트레이싱/쿼리카운트 토글"
```

---

### Task 6: Grafana Tempo (obs 스택)

**Files:**
- Modify: `infra/load-test/observability/docker-compose.yml`
- Create: `infra/load-test/observability/tempo/tempo.yml`
- Create: `infra/load-test/observability/grafana/provisioning/datasources/tempo.yml`

**Interfaces:**
- Consumes: 앱들이 보내는 OTLP/gRPC :4317 (Task 5)
- Produces: Tempo 컨테이너(수신 :4317, 쿼리 :3200), Grafana Tempo 데이터소스

- [ ] **Step 1: Tempo 설정 파일 작성**

Create `infra/load-test/observability/tempo/tempo.yml` — OTLP 수신 + 로컬 스토리지(실측 수명이라 보존 불필요):

```yaml
# Grafana Tempo — 실측용 최소 구성. OTLP 수신, 로컬 스토리지.
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: "0.0.0.0:4317"

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/traces
    wal:
      path: /var/tempo/wal
```

- [ ] **Step 2: obs docker-compose에 Tempo 서비스 추가**

`infra/load-test/observability/docker-compose.yml`의 `grafana` 서비스 **바로 뒤**(같은 들여쓰기)에 추가:

```yaml
  tempo:
    image: grafana/tempo:2.6.0
    container_name: tempo
    restart: unless-stopped
    command: ["-config.file=/etc/tempo/tempo.yml"]
    ports:
      - "4317:4317"   # OTLP gRPC (앱 → obs 호스트 10.0.1.50:4317)
      - "3200:3200"   # Tempo 쿼리 API (Grafana 데이터소스)
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
      - tempo-data:/var/tempo
```

그리고 파일 하단 `volumes:` 블록에 `tempo-data:` 추가:

```yaml
volumes:
  prom-data:
  grafana-data:
  tempo-data:
```

- [ ] **Step 3: Grafana Tempo 데이터소스 프로비저닝**

Create `infra/load-test/observability/grafana/provisioning/datasources/tempo.yml`:

```yaml
apiVersion: 1

datasources:
  - name: Tempo
    type: tempo
    uid: tempo
    access: proxy
    url: http://tempo:3200
    editable: true
```

- [ ] **Step 4: compose 유효성 검증**

Run: `cd infra/load-test/observability && docker compose config -q`
Expected: 출력 없음(유효).

Run: `docker compose config | grep -A1 "tempo:" | head` (tempo 서비스가 렌더되는지 육안 확인)
Expected: `tempo:` 서비스와 `grafana/tempo:2.6.0` 이미지 표시.

> Tempo 실제 기동·OTLP 수신은 obs 인스턴스 배포 후 런타임 스모크(Task 7 성공 기준). `config -q`는 구성 유효성만.

- [ ] **Step 5: 커밋**

```bash
git add infra/load-test/observability/docker-compose.yml \
  infra/load-test/observability/tempo/tempo.yml \
  infra/load-test/observability/grafana/provisioning/datasources/tempo.yml
git commit -m "feat(obs): Grafana Tempo(OTLP :4317) obs 스택에 추가 + 데이터소스"
```

---

### Task 7: 대시보드 패널 + 문서 + 런타임 스모크 체크리스트

**Files:**
- Create: `infra/load-test/observability/grafana/dashboards/query-count.json`
- Modify: `docs/load-test/measurement-journey.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `db_queries_per_request{service,uri}` 메트릭(Task 2~4), Tempo 데이터소스(Task 6)

- [ ] **Step 1: 요청당 쿼리 수 대시보드 패널 작성**

Create `infra/load-test/observability/grafana/dashboards/query-count.json` — `uri`별 p95 쿼리 수 시계열:

```json
{
  "title": "요청당 쿼리 수 (N+1 감시)",
  "uid": "query-count",
  "schemaVersion": 39,
  "time": { "from": "now-15m", "to": "now" },
  "panels": [
    {
      "type": "timeseries",
      "title": "p95 queries/request by uri",
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "gridPos": { "h": 9, "w": 24, "x": 0, "y": 0 },
      "targets": [
        {
          "expr": "histogram_quantile(0.95, sum by (le, uri, service) (rate(db_queries_per_request_bucket[1m])))",
          "legendFormat": "{{service}} {{uri}}"
        }
      ]
    }
  ]
}
```

> 이 파일은 기존 대시보드 provider(`grafana/provisioning/dashboards/provider.yml`)가 `grafana/dashboards` 경로를 이미 프로비저닝하므로 자동 로드된다.

- [ ] **Step 2: measurement-journey.md에 관측 항목 추가**

`docs/load-test/measurement-journey.md`의 관측/도구 섹션(§9 부근)에 항목 추가:

```markdown
### white-box 관측 (트레이스 + 요청당 쿼리 수)

- **분산 트레이싱**: OTel Java agent → Grafana Tempo(obs :4317). 활성화는 실측 compose env:
  `OTEL_JAVAAGENT="-javaagent:/otel/opentelemetry-javaagent.jar" docker compose ... up -d --force-recreate`
- **요청당 쿼리 수**: `LOADTEST_QUERYCOUNT_ENABLED=true` → Grafana "요청당 쿼리 수" 대시보드. `uri`별 p95로 N+1 감시.
- **왜곡 주의**: 트레이싱 100%(parentbased_always_on) + proxy 래핑은 오버헤드다. baseline과 비교 시 두 토글을 끈 채 먼저 재고, 벌어지면 `OTEL_TRACES_SAMPLER=traceidratio` + ratio env로 낮춘다.
```

- [ ] **Step 3: CLAUDE.md 실행 명령어 섹션에 한 줄 추가**

`CLAUDE.md`의 "실행 명령어" 블록 하단(부하 실측 줄 근처)에 추가:

```markdown
- white-box 관측: 실측 compose에서 `OTEL_JAVAAGENT`(트레이스)·`LOADTEST_QUERYCOUNT_ENABLED`(쿼리수) 토글. Tempo는 obs 스택에 포함.
```

- [ ] **Step 4: 검증 (JSON 유효성 + 문서 반영)**

Run: `python3 -m json.tool infra/load-test/observability/grafana/dashboards/query-count.json > /dev/null && echo OK`
Expected: `OK`

Run: `grep -c "LOADTEST_QUERYCOUNT_ENABLED" docs/load-test/measurement-journey.md CLAUDE.md`
Expected: 각 파일 ≥1.

- [ ] **Step 5: 커밋**

```bash
git add infra/load-test/observability/grafana/dashboards/query-count.json \
  docs/load-test/measurement-journey.md CLAUDE.md
git commit -m "docs(obs): 요청당 쿼리 수 대시보드 + 관측 운영 노트"
```

---

## 런타임 스모크 체크리스트 (다음 AWS 실측 시 — 이 계획의 성공 기준)

> 코드/구성은 위 태스크로 완결되지만, **실제 관측 동작**은 온디맨드 인프라를 띄운 뒤에만 검증 가능하다. 다음 실측 때 확인:

1. `terraform apply` → `ssm-deploy.sh`로 obs 스택 배포 → `port-forward.sh grafana` → Grafana에 **Tempo 데이터소스**와 "요청당 쿼리 수" 대시보드가 보인다.
2. 앱을 `OTEL_JAVAAGENT=... LOADTEST_QUERYCOUNT_ENABLED=true`로 `--force-recreate` 후 부하 → Grafana Explore(Tempo)에서 **payment→risk→merchant-limit 한 트레이스**를 열어 JDBC/HTTP span 확인.
3. "요청당 쿼리 수" 대시보드에 `uri`별 곡선이 실시간으로 그려진다.
4. (선택) merchant-limit netem 지연 재현 → 11편의 "커넥션 점유"가 트레이스 span 폭으로 **보인다**.
5. 토글을 끄고(agent·쿼리카운트 off) 부하 → 두 신호 모두 사라지고 rps가 계측 오버헤드만큼 회복되는지 확인(왜곡 크기 측정).

---

## Self-Review 결과

- **Spec 커버리지**: §4.1 트레이싱→Task 5·6, §4.2 쿼리수→Task 1·2·3·4, §6 opt-in 매트릭스→Task 3(게이팅 테스트)·Task 5(env 토글), §5 왜곡 주의→Task 7 문서·스모크. §8 성공 기준→런타임 스모크 체크리스트. 누락 없음.
- **Placeholder 스캔**: TBD/TODO 없음. 버전 핀(datasource-proxy 1.10, OTel 2.11.0, tempo 2.6.0)은 실제 값 + "구현 시 최신 확인" 명시.
- **타입 일관성**: `QueryCountReader.readAndReset()`·`QueryCountFilter(reader, registry, service)`·메트릭명 `db.queries.per_request`·태그 `service`/`uri`·프로퍼티 `loadtest.query-count.enabled`↔env `LOADTEST_QUERYCOUNT_ENABLED`가 전 태스크에서 일관.
