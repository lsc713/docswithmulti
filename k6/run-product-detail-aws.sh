#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IDS_FILE="$ROOT/k6/seed/productIds.json"
STAGE="${STAGE:-baseline}"
DISTRIBUTION="${DISTRIBUTION:-realistic}"
REPO_URL="${REPO_URL:-https://github.com/lsc713/docswithmulti.git}"
REGION="${AWS_REGION:-ap-northeast-2}"

case "$STAGE" in smoke|baseline|ramp|stress|soak) ;; *) echo "Invalid STAGE: $STAGE" >&2; exit 1 ;; esac
case "$DISTRIBUTION" in hot|uniform|realistic) ;; *) echo "Invalid DISTRIBUTION: $DISTRIBUTION" >&2; exit 1 ;; esac
[ -f "$IDS_FILE" ] || { echo "Missing $IDS_FILE; run k6/seed/product-detail-seed.sh first" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v aws >/dev/null || { echo "AWS CLI is required" >&2; exit 1; }
jq -e 'type == "array" and length > 0 and all(.[]; type == "number" and floor == . and . > 0)' "$IDS_FILE" >/dev/null \
  || { echo "Invalid product ID array: $IDS_FILE" >&2; exit 1; }

IID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Role,Values=k6" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId | [0]' --output text)
case "$IID" in ""|None) echo "No running Role=k6 instance" >&2; exit 1 ;; esac

IDS_B64=$(base64 < "$IDS_FILE" | tr -d '\n')
read -r -d '' REMOTE <<'REMOTE' || true
set -e
mkdir -p /opt/loadtest
if [ ! -d /opt/loadtest/repo/.git ]; then git clone --depth 1 "$REPO_URL" /opt/loadtest/repo; else git -C /opt/loadtest/repo pull --ff-only; fi
printf '%s' "$IDS_B64" | base64 -d > /opt/loadtest/repo/k6/seed/productIds.json
docker run --rm --network host -v /opt/loadtest/repo:/work -w /work \
  -e TARGET=aws -e STAGE="$STAGE" -e DISTRIBUTION="$DISTRIBUTION" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
  grafana/k6:0.54.0 run -o experimental-prometheus-rw k6/product-detail.js
REMOTE

PARAMS=$(jq -n --arg repo "$REPO_URL" --arg ids "$IDS_B64" --arg stage "$STAGE" \
  --arg distribution "$DISTRIBUTION" --arg script "$REMOTE" \
  '{commands: ["REPO_URL=\($repo | @sh)\nIDS_B64=\($ids | @sh)\nSTAGE=\($stage | @sh)\nDISTRIBUTION=\($distribution | @sh)\n" + $script]}')
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" \
  --document-name AWS-RunShellScript --comment "product detail load test" \
  --parameters "$PARAMS" --timeout-seconds 3600 \
  --query 'Command.CommandId' --output text)

result=0
while :; do
  if ! status=$(aws ssm list-command-invocations --region "$REGION" --command-id "$CID" \
    --query 'CommandInvocations[0].Status' --output text); then
    echo "Failed to poll SSM command $CID" >&2
    exit 1
  fi
  case "$status" in
    Success) break ;;
    Failed|Cancelled|TimedOut) echo "k6 command failed ($status)" >&2; result=1; break ;;
    Pending|InProgress|Delayed|Cancelling) sleep 5 ;;
    *) echo "Unexpected SSM command status: $status" >&2; exit 1 ;;
  esac
done
aws ssm list-command-invocations --region "$REGION" --command-id "$CID" --details \
  --query 'CommandInvocations[0].CommandPlugins[0].Output' --output text
exit "$result"
