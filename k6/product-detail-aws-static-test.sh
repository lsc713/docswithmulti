#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEPLOY="$ROOT/infra/load-test/deploy/ssm-deploy.sh"
RUNNER="$ROOT/k6/run-product-detail-aws.sh"
SCENARIO="$ROOT/k6/product-detail.js"

require() { rg -q "$2" "$1" || { echo "missing $2 in $1" >&2; exit 1; }; }

require "$DEPLOY" 'REPO_REF=.*:\?'
require "$RUNNER" 'REPO_REF=.*:\?'
require "$DEPLOY" 'describe-instance-information'
require "$DEPLOY" 'docker info'
require "$DEPLOY" 'SSM_POLL_ATTEMPTS'
require "$DEPLOY" 'checkout --detach'
require "$RUNNER" 'checkout --detach'
require "$RUNNER" 'K6_PROMETHEUS_RW_TREND_STATS=p\(50\),p\(95\),p\(99\)'
require "$RUNNER" 'K6_SUMMARY_TREND_STATS=med,p\(95\),p\(99\)'
require "$RUNNER" 'summary-export'
require "$RUNNER" 'K6_RESULT_BEGIN'
require "$RUNNER" 'length >= 10'
require "$SCENARIO" 'ids.length < 10'

if rg -q 'ssm_run .*\|\| (true|echo)' "$DEPLOY"; then
  echo "required SSM failure is ignored" >&2
  exit 1
fi
