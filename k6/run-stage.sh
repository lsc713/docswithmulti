#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# k6 스테이지 러너 — TARGET/STAGE/Prometheus remote-write 를 엮어 실행
#
#   TARGET   local(기본) | aws
#   STAGE    smoke | baseline | ramp | stress | soak   (stages.js)
#   SCRIPT   k6/stages.js(기본) | k6/hot-merchant.js | k6/idempotency-test.js ...
#   PROM     Prometheus remote-write URL (지정 시 -o experimental-prometheus-rw)
#
# 예:
#   STAGE=baseline ./k6/run-stage.sh
#   TARGET=aws STAGE=ramp PROM=http://10.0.1.50:9090/api/v1/write ./k6/run-stage.sh
#   SCRIPT=k6/hot-merchant.js VUS=30 ./k6/run-stage.sh
# ─────────────────────────────────────────────────────────────
set -euo pipefail

TARGET="${TARGET:-local}"
STAGE="${STAGE:-baseline}"
SCRIPT="${SCRIPT:-k6/stages.js}"
PROM="${PROM:-}"
WEB="${WEB:-0}"          # 1 → k6 web dashboard 라이브 UI(:5665). port-forward 로 확인.
REPORT="${REPORT:-}"     # 경로 지정 시 실행 후 HTML 리포트 export

command -v k6 >/dev/null || { echo "k6 가 설치돼 있지 않습니다 (https://k6.io/docs/get-started/installation/)"; exit 1; }
[ -f k6/seed/paymentKeys.json ] || echo "⚠ k6/seed/paymentKeys.json 없음 — 먼저 ./k6/seed/seed.sh 실행 권장"

ARGS=(run --env "TARGET=${TARGET}" --env "STAGE=${STAGE}")
if [ -n "$PROM" ]; then
  export K6_PROMETHEUS_RW_SERVER_URL="$PROM"
  ARGS+=(-o experimental-prometheus-rw)
fi
# k6 web dashboard: 실행 중 실시간 웹 UI(:5665) + 종료 후 HTML 리포트
if [ "$WEB" = "1" ] || [ -n "$REPORT" ]; then
  export K6_WEB_DASHBOARD=true
  [ -n "$REPORT" ] && export K6_WEB_DASHBOARD_EXPORT="$REPORT"
fi

echo "[run] TARGET=${TARGET} STAGE=${STAGE} SCRIPT=${SCRIPT} prom=${PROM:-off} web=${WEB} report=${REPORT:-off}"
exec k6 "${ARGS[@]}" "$SCRIPT"
