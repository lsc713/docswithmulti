# 포화 진단 키트 (185 rps 벽 규명) — 설계

- 날짜: 2026-07-11
- 상태: 설계 확정
- 관련: `docs/load-test/measurement-journey.md`(3-config 실험 §8), PR #45/#47(3-config), PR #48(white-box 관측), 메모리 `loadtest-aws-run`

## 배경 / 문제

3-config 스윕(A=Redis 캐시 / B=DB 스냅샷 / C=HTTP 매번)이 **셋 다 VU400까지 ~185rps에서 포화**함을 보였다. 즉 185는 **daily_limit 해석층과 무관한 구성 독립 천장**이고, 병목은 payment→risk **동기 홉** 어딘가의 아키텍처 한계로 추정된다. 하지만 이건 아직 **추론**이다 — 185에서 실제로 어느 자원(payment CPU / risk CPU / HikariCP 풀 / Tomcat 스레드 / DB / 홉 직렬화)이 먼저 포화하는지 **관측으로 규명한 적이 없다.**

Little's Law로 `185 rps × 330ms ≈ 동시 61`인데, HikariCP 기본 풀은 **10**(어느 서비스도 `maximum-pool-size` 미설정)이다. 풀 10으로 동시 61 rps가 나왔다는 건 (a) 풀이 병목이 아니거나(HTTP-in-TX가 커넥션을 전 구간 안 쥠), (b) 다른 층이 먼저 막힌 것 — 이 모순 자체가 측정으로 풀 첫 단서다.

## 목표

다음 AWS 온디맨드 실측에서 VU를 올려 rps가 평탄해진 정상상태를 잡고, **자원별 U/S/E(USE method)를 한 화면에서 동시에 읽어 185가 어느 벽인지 추론 없이 규명**한다. 키트는 지금(AWS 없이) 완성하고, 곡선은 실측 때 확인한다.

**비목표(YAGNI):** 자동 병목 판정/알림, 부하 자동 스윕 스크립트, 코드 프로파일러(async-profiler 등), 새 exporter 추가. 판정은 사람이 대시보드 + 판정 트리로 한다.

## 불변식 / 원칙

- **전부 opt-in** — 평상시 실행·CI에 영향 0. white-box 관측(PR #48)과 동일 원칙. application.yml(상시 적용) 대신 load-test compose env로만 활성.
- 기존 `cancel-loadtest-overview.json`(평상 모니터링)은 **건드리지 않는다.** 진단 뷰는 목적이 달라 신규 파일로 분리.
- 이미 스크레이프 중인 신호(node-exporter CPU, HikariCP, mysqld-exporter, 5xx)는 **재사용만** 한다 — 새 계측은 Tomcat 스레드(①)와 홉 지연(②) 둘뿐.

## 산출물

### ① Tomcat 스레드 지표 — compose env (코드 0)

payment·risk 서비스 compose 파일에 환경변수 추가:

```
SERVER_TOMCAT_MBEANREGISTRY_ENABLED: "${SERVER_TOMCAT_MBEANREGISTRY_ENABLED:-false}"
```

- Spring relaxed binding: `server.tomcat.mbeanregistry.enabled`. 이게 true여야 Micrometer `TomcatMetrics` 바인더가 스레드풀 MBean을 찾아 `tomcat_threads_busy_threads` / `tomcat_threads_config_max_threads`를 노출한다.
- 대상: **payment + risk만**(동기 홉의 두 당사자, 185 벽의 주 용의자). merchant-limit/order는 홉 경로 밖이라 진단엔 잉여.
- 기본값 `false` → 실측 때만 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`로 켠다. application.yml 불변.

### ② payment→risk 홉 지연 — Micrometer 타이머 + Tempo

**Prometheus 경로 (신규 계측):**
`payment-service/.../infrastructure/config/HttpClientConfig.java`의 `new RestTemplate()`을 주입된 `RestTemplateBuilder.build()`로 교체.

```java
@Bean
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}
```

- Spring Boot의 `http.client.requests` 자동 계측은 **`RestTemplateBuilder`로 만든** RestTemplate에만 붙는다. 현재 `new RestTemplate()`이라 클라이언트 지연 지표가 아예 안 나옴 — 이 한 줄이 risk·PG 홉 지연을 `http_client_requests_seconds_bucket{service="payment"}`로 노출.
- **Cardinality:** risk 클라이언트 경로는 고정 문자열 2개(`/internal/cancel-limit/validate-and-reserve`, `/compensate`) → `uri` 태그 유한(안전). PG 클라이언트는 원래 paymentKey를 문자열 연결로 URL에 삽입해 `uri` 태그가 요청마다 달라지는 cardinality 문제가 있었음 → `postForEntity(url, request, PgCancelResult.class, paymentKey)`의 URI 템플릿 변수 방식으로 수정해 Micrometer가 `/v1/payments/{paymentKey}/cancel` 태그를 기록하도록 제한함. 명시적 `Timer` 불필요.
- 이 변경은 프로덕션에도 `http.client.requests`를 상시 발생시키지만(서버 지표 `http.server.requests`처럼 저비용·표준), 동작 변화 없음. opt-in 대상이 아닌 **표준 관측 개선**으로 분류.

**Tempo 경로 (기존):** PR #48에서 만든 OTel 트레이스의 span 폭 = 홉 지연. 런타임 스모크 때 payment→risk→merchant-limit 트레이스로 육안 확인(별도 코드 없음).

### ③ "포화 진단" Grafana 대시보드 — 신규 파일

`infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json` (provisioning provider가 자동 로드). USE method로 자원별 U/S/E 재편. overview와 별개.

**패널 구성 (신호 지도):**

| 자원 | Utilization 쿼리 | Saturation 쿼리 | 신규? |
|---|---|---|---|
| 기준 rps | `sum by(service)(rate(http_server_requests_seconds_count{service=~"payment\|risk"}[1m]))` (payment·risk 양쪽 → 포화 고원 동시 확인) | — | 재사용 |
| payment/risk CPU | `1 - avg by(host)(rate(node_cpu_seconds_total{mode="idle",host=~"payment\|risk"}[1m]))` | — | 재사용 |
| Tomcat 스레드 | `tomcat_threads_busy_threads / tomcat_threads_config_max_threads` (service별) | `tomcat_threads_busy_threads == tomcat_threads_config_max_threads` | ① |
| HikariCP 풀 (기본 10) | `hikaricp_connections_active / hikaricp_connections_max` | `hikaricp_connections_pending` | 재사용 |
| DB (payment/risk) | `mysql_global_status_threads_running{db=~"payment\|risk"}`, `rate(mysql_global_status_queries{db=~"payment\|risk"}[1m])`, DB host CPU(`host=~"mysql-payment\|mysql-risk"`) | `rate(mysql_global_status_innodb_row_lock_waits[1m])` | 재사용 |
| 홉 지연 | `histogram_quantile(0.95, sum by(le,uri)(rate(http_client_requests_seconds_bucket{service="payment"}[1m])))` | — | ② |
| Errors | `sum by(service)(rate(http_server_requests_seconds_count{status=~"5..",service=~"payment\|risk"}[1m]))` | — | 재사용 |

패널 스타일/포맷은 기존 `cancel-loadtest-overview.json`을 따른다(같은 Prometheus 데이터소스 uid, timeseries 패널, `service`/`host`/`db` 타겟 라벨 그룹핑).

### 절차 문서 — `docs/load-test/saturation-diagnosis.md`

- **런 절차:** obs 온디맨드 기동(spot=false) → `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`로 payment/risk 재기동 → k6 VU 스윕으로 rps 평탄 지점(≈185) 확인 → 그 정상상태 구간에서 "포화 진단" 대시보드 신호 동시 캡처.
- **판정 트리(USE):**
  - `risk CPU ≈ 100%` → risk 연산 벽 → risk 서버 증설(락 제거로 수평 확장 이미 가능).
  - `Hikari pending > 0 & active == max(10)` → **풀 벽** → 풀 확장 or TX 내 HTTP 체류 단축(limit 해석 TX 밖으로).
  - `tomcat busy == max` → 스레드 벽 → 스레드풀/커넥터 튜닝.
  - `DB threads_running 급증 / lock waits` → DB 벽.
  - **아무 자원도 100%가 아닌데 홉 지연이 rps를 규정** → 동기 홉 직렬화 → 비동기화 or 홉 축소가 답(3-config가 가리킨 가설의 확증).
- **주의(중요):** 포화 진단 런은 **쿼리카운트 OFF**로 실행한다. white-box의 `ProxyDataSource` 래핑이 Spring의 `hikaricp_*` 메트릭 바인딩(HikariDataSource 언랩)을 가릴 위험이 있는데, 풀 지표가 진단 핵심이기 때문. 이 상호작용(ProxyDataSource ON ↔ Hikari 지표)은 런타임 스모크 때 **1회 검증** 항목으로 기록한다.

## 검증 전략

- **compose YAML:** `docker compose config`로 병합 파싱 성공 확인(env 문법).
- **HttpClientConfig 변경:** payment 기존 테스트 그린 유지 + `RestTemplate`이 빌더로 생성돼 `http.client.requests` 메터가 등록됨을 단위 검증(가능 범위).
- **대시보드 JSON:** provisioning 스키마 유효(파싱) + 각 PromQL 문법 유효.
- **실제 곡선:** AWS 실측 때 확인(키트 자체는 AWS 없이 완성). 런타임 스모크 체크리스트에 "포화 진단 3신호(CPU/풀/홉지연) 캡처 + 쿼리카운트↔Hikari 검증" 추가.

## 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `infra/load-test/deploy/payment.compose.yml` | `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` env 추가 |
| `infra/load-test/deploy/risk.compose.yml` | `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` env 추가 |
| `payment-service/.../infrastructure/config/HttpClientConfig.java` | `new RestTemplate()` → `RestTemplateBuilder.build()` |
| `infra/load-test/observability/grafana/dashboards/saturation-diagnosis.json` | 신규 진단 대시보드 |
| `docs/load-test/saturation-diagnosis.md` | 신규 런 절차 + USE 판정 트리 |

## 미해결/후속

- ProxyDataSource ↔ hikaricp 메트릭 바인딩 상호작용의 실제 여부는 스모크 검증 후 확정(현재는 회피 지침으로 처리).
- 판정 결과에 따른 실제 개선(풀 확장 / limit 해석 TX 밖 / 비동기화 / risk 증설)은 이 키트의 산출이 아니라 다음 사이클.
