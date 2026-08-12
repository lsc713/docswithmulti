#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IDS_FILE="$ROOT/k6/seed/productIds.json"
STAGE="${STAGE:-baseline}"
DISTRIBUTION="${DISTRIBUTION:-realistic}"
REPO_URL="${REPO_URL:-https://github.com/lsc713/docswithmulti.git}"
REPO_REF="${REPO_REF:?Exact Git SHA/ref required}"
REGION="${AWS_REGION:-ap-northeast-2}"
SSM_READY_ATTEMPTS="${SSM_READY_ATTEMPTS:-120}"
SSM_BOOTSTRAP_ATTEMPTS="${SSM_BOOTSTRAP_ATTEMPTS:-130}"
SSM_POLL_ATTEMPTS="${SSM_POLL_ATTEMPTS:-721}"
RESULT_DIR="${RESULT_DIR:-$ROOT/k6/results}"
RUN_KEY="$(date -u +%Y%m%dT%H%M%SZ)-${STAGE}-${DISTRIBUTION}-$$"

case "$STAGE" in smoke|baseline|ramp|stress|soak) ;; *) echo "Invalid STAGE: $STAGE" >&2; exit 1 ;; esac
case "$DISTRIBUTION" in hot|uniform|realistic) ;; *) echo "Invalid DISTRIBUTION: $DISTRIBUTION" >&2; exit 1 ;; esac
case "$SSM_READY_ATTEMPTS:$SSM_BOOTSTRAP_ATTEMPTS:$SSM_POLL_ATTEMPTS" in
  *[!0-9:]*|0:*|*:0:*|*:0) echo "SSM wait attempts must be positive integers" >&2; exit 1 ;;
esac
[ -f "$IDS_FILE" ] || { echo "Missing $IDS_FILE; run k6/seed/product-detail-seed.sh first" >&2; exit 1; }
for tool in jq aws base64 tar shasum; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
jq -e 'type == "array" and length >= 10 and all(.[]; type == "number" and floor == . and . > 0)' "$IDS_FILE" >/dev/null \
  || { echo "Product ID array must contain at least 10 positive integers: $IDS_FILE" >&2; exit 1; }

IID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Role,Values=k6" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].InstanceId | [0]' --output text)
case "$IID" in ""|None) echo "No running Role=k6 instance" >&2; exit 1 ;; esac

wait_for_ssm() {
  local attempt status
  for ((attempt=1; attempt<=SSM_READY_ATTEMPTS; attempt++)); do
    status=$(aws ssm describe-instance-information --region "$REGION" \
      --filters "Key=InstanceIds,Values=${IID}" \
      --query 'InstanceInformationList[0].PingStatus' --output text)
    case "$status" in
      Online) return ;;
      None|Inactive|ConnectionLost) ;;
      *) echo "Unexpected Role=k6 SSM status: $status" >&2; return 1 ;;
    esac
    [ "$attempt" -lt "$SSM_READY_ATTEMPTS" ] || {
      echo "Role=k6 SSM did not become Online after $((SSM_READY_ATTEMPTS * 5))s" >&2
      return 1
    }
    sleep 5
  done
}

wait_for_ssm
BOOTSTRAP_PARAMS=$(jq -n '{commands: ["set -e\nattempt=1\nuntil command -v git >/dev/null && docker info >/dev/null 2>&1; do\n  [ \"$attempt\" -lt 120 ] || { echo \"Role=k6 bootstrap did not finish after 600s\" >&2; exit 1; }\n  attempt=$((attempt + 1))\n  sleep 5\ndone"]}')
BOOTSTRAP_CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" \
  --document-name AWS-RunShellScript --comment "wait for k6 bootstrap" \
  --parameters "$BOOTSTRAP_PARAMS" --timeout-seconds 650 \
  --query 'Command.CommandId' --output text)
bootstrap_ok=0
for ((attempt=1; attempt<=SSM_BOOTSTRAP_ATTEMPTS; attempt++)); do
  status=$(aws ssm list-command-invocations --region "$REGION" --command-id "$BOOTSTRAP_CID" \
    --query 'CommandInvocations[0].Status' --output text)
  case "$status" in
    Success) bootstrap_ok=1; break ;;
    Failed|Cancelled|TimedOut) echo "Role=k6 bootstrap check failed ($status)" >&2; break ;;
    None|Pending|InProgress|Delayed|Cancelling) ;;
    *) echo "Unexpected Role=k6 bootstrap command status: $status" >&2; exit 1 ;;
  esac
  [ "$attempt" -lt "$SSM_BOOTSTRAP_ATTEMPTS" ] || {
    echo "Role=k6 bootstrap command did not finish after $((SSM_BOOTSTRAP_ATTEMPTS * 5))s" >&2
    break
  }
  sleep 5
done
[ "$bootstrap_ok" = 1 ] || exit 1

IDS_B64=$(base64 < "$IDS_FILE" | tr -d '\n')
read -r -d '' REMOTE <<'REMOTE' || true
set -e
mkdir -p /opt/loadtest/results
if [ ! -d /opt/loadtest/repo/.git ]; then git clone --no-checkout "$REPO_URL" /opt/loadtest/repo; fi
git -C /opt/loadtest/repo fetch --depth 1 origin "$REPO_REF"
expected_head=$(git -C /opt/loadtest/repo rev-parse 'FETCH_HEAD^{commit}')
git -C /opt/loadtest/repo checkout --detach --force "$expected_head"
actual_head=$(git -C /opt/loadtest/repo rev-parse HEAD)
[ "$actual_head" = "$expected_head" ] || { echo "repo checkout mismatch: $actual_head != $expected_head" >&2; exit 1; }
printf '%s' "$IDS_B64" | base64 -d > /opt/loadtest/repo/k6/seed/productIds.json
summary="/opt/loadtest/results/${RUN_KEY}.summary.json"
console="/opt/loadtest/results/${RUN_KEY}.console.log"
bundle="/opt/loadtest/results/${RUN_KEY}.tgz"
rm -f "$summary" "$console" "$bundle"
set +e
docker run --rm --network host -v /opt/loadtest/repo:/work -w /work \
  -v /opt/loadtest/results:/results \
  -e TARGET=aws -e STAGE="$STAGE" -e DISTRIBUTION="$DISTRIBUTION" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
  -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99)' \
  -e 'K6_SUMMARY_TREND_STATS=med,p(95),p(99)' \
  grafana/k6:0.54.0 run --summary-export "/results/${RUN_KEY}.summary.json" \
    -o experimental-prometheus-rw k6/product-detail.js >"$console" 2>&1
k6_status=$?
set -e
if [ -s "$summary" ]; then
  tar -czf "$bundle" -C /opt/loadtest/results "${RUN_KEY}.summary.json" "${RUN_KEY}.console.log"
  bytes=$(wc -c < "$bundle" | tr -d ' ')
  encoded=$(base64 "$bundle" | tr -d '\n')
  encoded_chars=${#encoded}
  # ponytail: leave 2,000 characters below SSM's 24,000-character stdout cap; use S3 if bundles outgrow this.
  [ "$encoded_chars" -le 22000 ] || { echo "result artifact exceeds SSM output limit; retained at $bundle" >&2; exit 1; }
  checksum=$(sha256sum "$bundle" | awk '{print $1}')
  printf 'K6_RESULT_BEGIN %s %s\n' "$checksum" "$bytes"
  printf '%s' "$encoded"
  printf '\nK6_RESULT_END\n'
else
  echo "k6 summary was not created; console retained at $console" >&2
  k6_status=1
fi
exit "$k6_status"
REMOTE

PARAMS=$(jq -n --arg repo "$REPO_URL" --arg ids "$IDS_B64" --arg stage "$STAGE" \
  --arg distribution "$DISTRIBUTION" --arg ref "$REPO_REF" --arg run "$RUN_KEY" --arg script "$REMOTE" \
  '{commands: ["REPO_URL=\($repo | @sh)\nREPO_REF=\($ref | @sh)\nIDS_B64=\($ids | @sh)\nSTAGE=\($stage | @sh)\nDISTRIBUTION=\($distribution | @sh)\nRUN_KEY=\($run | @sh)\n" + $script]}')
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" \
  --document-name AWS-RunShellScript --comment "product detail load test" \
  --parameters "$PARAMS" --timeout-seconds 3600 \
  --query 'Command.CommandId' --output text)

result=1
for ((attempt=1; attempt<=SSM_POLL_ATTEMPTS; attempt++)); do
  if ! status=$(aws ssm list-command-invocations --region "$REGION" --command-id "$CID" \
    --query 'CommandInvocations[0].Status' --output text); then
    echo "Failed to poll SSM command $CID" >&2
    exit 1
  fi
  case "$status" in
    Success) result=0; break ;;
    Failed|Cancelled|TimedOut) echo "k6 command failed ($status)" >&2; break ;;
    None|Pending|InProgress|Delayed|Cancelling) ;;
    *) echo "Unexpected SSM command status: $status" >&2; exit 1 ;;
  esac
  [ "$attempt" -lt "$SSM_POLL_ATTEMPTS" ] || {
    echo "SSM command $CID did not finish after $((SSM_POLL_ATTEMPTS * 5))s" >&2
    break
  }
  sleep 5
done
output=$(mktemp)
bundle=$(mktemp)
trap 'rm -f "$output" "$bundle"' EXIT
aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query StandardOutputContent --output text --region "$REGION" > "$output"

header=$(sed -n '/^K6_RESULT_BEGIN /{p;q;}' "$output")
if [ -n "$header" ] && grep -q '^K6_RESULT_END$' "$output"; then
  expected_checksum=$(printf '%s\n' "$header" | awk '{print $2}')
  expected_bytes=$(printf '%s\n' "$header" | awk '{print $3}')
  sed -n '/^K6_RESULT_BEGIN /,/^K6_RESULT_END$/p' "$output" | sed '1d;$d' | tr -d '\n' | base64 -d > "$bundle"
  actual_checksum=$(shasum -a 256 "$bundle" | awk '{print $1}')
  actual_bytes=$(wc -c < "$bundle" | tr -d ' ')
  if [ "$actual_checksum:$actual_bytes" != "$expected_checksum:$expected_bytes" ]; then
    echo "Result artifact checksum/size mismatch; remote copy is /opt/loadtest/results/${RUN_KEY}.tgz" >&2
    exit 1
  fi
  mkdir -p "$RESULT_DIR"
  tar -xzf "$bundle" -C "$RESULT_DIR"
  echo "Saved result artifacts to $RESULT_DIR/${RUN_KEY}.{summary.json,console.log}"
else
  echo "Result artifact missing or truncated; inspect /opt/loadtest/results/${RUN_KEY}.* on $IID" >&2
  exit 1
fi
exit "$result"
