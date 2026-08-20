#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
queries=$(PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$queries" == *'sum by (workload) (rate(k6_http_reqs_total'* ]]
[[ "$queries" == *'histogram_quantile(0.95'* ]]
[[ "$queries" == *'histogram_quantile(0.99'* ]]
[[ "$queries" == *'k6_http_req_failed_total'* ]]
