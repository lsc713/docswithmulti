#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
queries=$(PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$queries" == *'sum by (workload) (rate(k6_http_reqs_total'* ]]
[[ "$queries" == *'k6_stock_mix_workload_duration_p95{workload=~"read|write"}'* ]]
[[ "$queries" == *'k6_stock_mix_workload_duration_p99{workload=~"read|write"}'* ]]
[[ "$queries" == *'k6_stock_mix_workload_failure_rate{workload=~"read|write"}'* ]]
[[ "$queries" != *'_bucket'* && "$queries" != *'k6_http_req_failed_'* && "$queries" != *'max\ by'* ]]
runner="$ROOT/k6/run-product-stock-mix-aws.sh"
pull_line=$(rg -n -m1 -F 'docker pull grafana/k6:0.54.0' "$runner" | cut -d: -f1)
start_line=$(rg -n -m1 -F 'date -u +%s > "/results/${RUN_KEY}.started-epoch"' "$runner" | cut -d: -f1)
stage_line=$(rg -n -m1 -F '/work/k6/stage-windows.sh "$started_epoch" "$ended_epoch" "$STAGE_SECONDS"' "$runner" | cut -d: -f1)
[[ -n "$pull_line" && -n "$start_line" && -n "$stage_line" ]]
(( pull_line < start_line && start_line < stage_line ))
plan=$(PRINT_STAGE_PLAN=1 STAGE_START_EPOCH=1000 STAGE_END_EPOCH=1720 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$plan" == $'1 1000 1180\n2 1180 1360\n3 1360 1540\n4 1540 1720' ]]
good=$(mktemp); empty=$(mktemp); missing_read=$(mktemp); missing_write=$(mktemp); empty_values=$(mktemp); failed=$(mktemp)
trap 'rm -f "$good" "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed"' EXIT
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$good"
printf '%s\n' '{"status":"success","data":{"result":[]}}' > "$empty"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$missing_read"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]}]}}' > "$missing_write"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[]},{"metric":{"workload":"write"},"values":[]}]}}' > "$empty_values"
printf '%s\n' '{"status":"error","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$failed"
VERIFY_WORKLOAD_FILE="$good" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
for invalid in "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed"; do
  if VERIFY_WORKLOAD_FILE="$invalid" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
    echo 'invalid workload query unexpectedly passed' >&2
    exit 1
  fi
done
