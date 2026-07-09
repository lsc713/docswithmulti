# k6 부하 스크립트

`docs/load-test/measurement-journey.md` 의 2축(처리량/경합) × 스테이지(S0~S4)를 실행한다.

## 구성

| 파일 | 축/역할 |
|------|---------|
| `config.js` | `TARGET`(local/aws)별 BASE URL |
| `seed/seed.sh` | payment 대규모 시딩 (merchant HTTP + payment_db SQL bulk) → `seed/paymentKeys.json` |
| `stages.js` | **축 A 처리량** — S0~S4 스테이지 (`STAGE=` 선택) |
| `hot-merchant.js` | **축 B 경합** — 단일 merchant 집중 (`merchant_cancel_usage` row 경합) |
| `idempotency-test.js` | **축 B 경합** — 같은 payment 따닥 (request_hash UK + row lock) |
| `compensation-test.js` | 보상 경로 |
| `run-stage.sh` | TARGET/STAGE/Prometheus 러너 |
| `load-test.js` | (레거시) 고정 50 VU 단일 시나리오 — 소규모 HTTP 팩토리 기반 |

## 순서

```bash
# 1) 시딩 (풀 고갈 방지 — 스테이지 규모에 맞게 SEED_COUNT)
SEED_COUNT=5000 ./k6/seed/seed.sh                       # local
SEED_COUNT=100000 TARGET=aws MERCHANT_URL=http://10.0.1.22:8082 \
  MYSQL_HOST=10.0.1.30 MYSQL_PORT=3306 ./k6/seed/seed.sh  # aws

# 2) 축 A 스테이지 (S0→S1→S2→S3→S4 순서로)
STAGE=smoke    ./k6/run-stage.sh
STAGE=baseline ./k6/run-stage.sh
STAGE=ramp     ./k6/run-stage.sh
# aws + Grafana 관측
TARGET=aws STAGE=ramp PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh

# 3) 축 B 경합
SCRIPT=k6/hot-merchant.js VUS=30 DURATION=2m ./k6/run-stage.sh
SCRIPT=k6/idempotency-test.js ./k6/run-stage.sh
```

## 판단 기준 / 결과 기록

- Pass/Knee/Breaking 기준과 결과 템플릿은 `docs/load-test/measurement-journey.md` §5~§8.
- 스테이지 실행마다 §8 실행 로그에 결과 append.

## 전제

- 시딩은 payment_db 만 채운다(취소 경로 측정용). risk 는 최초 취소 시 merchant-limit HTTP 로 daily_limit 조회.
- order-service 다운스트림 동기화까지 보려면 order_db 시딩이 별도로 필요.
- `seed.sh` 는 `mysql` 클라이언트 + payment_db 접근 필요.
