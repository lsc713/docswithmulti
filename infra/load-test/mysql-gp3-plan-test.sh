#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
plan=$(mktemp)
trap 'rm -f "$plan"' EXIT

terraform -chdir="$ROOT" plan -refresh=false -input=false -out="$plan" \
  -var='project=product-detail-loadtest' \
  -var='load_test_profile=product-scaleout' \
  -var='use_spot=false' \
  -var='mysql_gp3_iops=6000' \
  -var='mysql_gp3_throughput=250' >/dev/null

terraform -chdir="$ROOT" show -json "$plan" | jq -e '
  .resource_changes[]
  | select(.address == "aws_instance.node[\"mysql-product\"]")
  | .change.after.root_block_device[0]
  | .volume_type == "gp3" and .iops == 6000 and .throughput == 250
' >/dev/null
