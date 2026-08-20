#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
queries=$(PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$queries" == *'sum by (workload) (rate(k6_http_reqs_total'* ]]
[[ "$queries" == *'max by (workload) (k6_http_req_duration_p95'* ]]
[[ "$queries" == *'max by (workload) (k6_http_req_duration_p99'* ]]
[[ "$queries" == *'max by (workload) (k6_http_req_failed_rate'* ]]
[[ "$queries" != *'_bucket'* && "$queries" != *'k6_http_req_failed_total'* ]]
plan=$(PRINT_STAGE_PLAN=1 STAGE_START_EPOCH=1000 STAGE_END_EPOCH=1720 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$plan" == $'1 1000 1180\n2 1180 1360\n3 1360 1540\n4 1540 1720' ]]
good=$(mktemp); empty=$(mktemp)
trap 'rm -f "$good" "$empty"' EXIT
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$good"
printf '%s\n' '{"status":"success","data":{"result":[]}}' > "$empty"
VERIFY_WORKLOAD_FILE="$good" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
if VERIFY_WORKLOAD_FILE="$empty" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'empty workload query unexpectedly passed' >&2
  exit 1
fi
