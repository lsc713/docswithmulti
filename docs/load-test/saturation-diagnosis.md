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

## 3-뷰 대시보드 (Inbound·App·Infra)

`system-views` 대시보드는 부하(Inbound)↔처리(App)↔자원(Infra)을 한 화면에서 본다.
- **App·Infra 행**은 상시 스크레이프라 배포 즉시 그려진다(CPU/Memory/Network/Disk, TPS/지연/성공률).
- **Inbound 행(k6)** 을 채우려면 k6를 remote-write로 실행한다:
  ```bash
  PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh
  ```
- 첫 런 스모크: Prometheus `/api/v1/label/__name__/values` 로 `k6_*` 실제 지표명과 node_network `device` 명을 확인하고, 다르면 `system-views.json` 패널 expr을 조정한다.

## 교차 확인 (Tempo)

Prometheus 신호로 벽을 좁힌 뒤, Tempo 트레이스에서 payment→risk→merchant-limit span 폭으로 홉 지연을 육안 확인한다. merchant-limit 에 netem 을 걸면(measurement-journey §netem) 커넥션 점유가 span 폭 증가로 보이는지 검증할 수 있다.

## 한계

- 자동 판정은 하지 않는다 — 사람이 대시보드 + 위 트리로 읽는다.
- 실제 곡선은 AWS 실측 때만 나온다. 키트(대시보드/env/계측)는 코드로 완성돼 있다.
