#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEED_FILE="$ROOT/k6/seed/productIds.json"
REPO_URL="${REPO_URL:-https://github.com/lsc713/docswithmulti.git}"
REPO_REF="${REPO_REF:?Exact Git SHA/ref required}"
REGION="${AWS_REGION:-ap-northeast-2}"
PROM_URL="${PROM_URL:-http://10.0.1.50:9090/api/v1/write}"
PRODUCT_URL="${PRODUCT_URL:-}"
RESULT_DIR="${RESULT_DIR:-$ROOT/k6/results}"
SSM_POLL_ATTEMPTS="${SSM_POLL_ATTEMPTS:-721}"
RUN_KEY="$(date -u +%Y%m%dT%H%M%SZ)-product-stock-mix-$$"
STAGE_SECONDS=180
MYSQL_THRESHOLD_RAMP="${MYSQL_THRESHOLD_RAMP:-false}"
MYSQL_THRESHOLD_LOW_RAMP="${MYSQL_THRESHOLD_LOW_RAMP:-false}"
STAGE_COUNT=4
[ "$MYSQL_THRESHOLD_RAMP" = true ] && STAGE_COUNT=6
[ "$MYSQL_THRESHOLD_LOW_RAMP" = true ] && STAGE_COUNT=5
K6_RPS_QUERY='sum by (workload) (rate(k6_http_reqs_total{workload=~"read|write"}[1m]))'
K6_P95_QUERY='k6_stock_mix_workload_duration_p95{workload=~"read|write"}'
K6_P99_QUERY='k6_stock_mix_workload_duration_p99{workload=~"read|write"}'
K6_ERROR_QUERY='k6_stock_mix_workload_failure_rate{workload=~"read|write"}'
# Custom workload metrics must return exactly one read and one write series. Any
# extra label can split a workload into subseries and invalidate the gauge value.
WORKLOAD_RESULT_JQ='.status == "success" and (.data.result | type == "array" and length == 2 and all(.[]; (.values | type == "array" and length > 0) and (.metric | type == "object" and (.workload == "read" or .workload == "write") and (keys | all(. == "__name__" or . == "workload")))) and ([.[] | .metric.workload] | sort == ["read", "write"]))'

if [ "${PRINT_STAGE_QUERIES:-}" = 1 ]; then
  printf '%s\n' "$K6_RPS_QUERY" "$K6_P95_QUERY" "$K6_P99_QUERY" "$K6_ERROR_QUERY"
  exit 0
fi
if [ "${PRINT_STAGE_PLAN:-}" = 1 ]; then
  "$ROOT/k6/stage-windows.sh" "${STAGE_START_EPOCH:?STAGE_START_EPOCH required}" "${STAGE_END_EPOCH:?STAGE_END_EPOCH required}" "$STAGE_SECONDS"
  exit 0
fi
if [ -n "${VERIFY_WORKLOAD_FILE:-}" ]; then
  jq -e "$WORKLOAD_RESULT_JQ" "$VERIFY_WORKLOAD_FILE" >/dev/null
  exit 0
fi

case "$PROM_URL" in */api/v1/write) PROM_QUERY_URL="${PROM_URL%/api/v1/write}/api/v1/query_range" ;; *) echo "PROM_URL must end in /api/v1/write" >&2; exit 1 ;; esac
[[ "$SSM_POLL_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || { echo "SSM_POLL_ATTEMPTS must be positive" >&2; exit 1; }
[ -f "$SEED_FILE" ] || { echo "Missing $SEED_FILE; run k6/seed/product-detail-seed.sh first" >&2; exit 1; }
for tool in jq aws base64 tar shasum; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }; done
jq -e 'type == "array" and length >= 10 and all(.[]; (.productId|numbers) and (.skuId|numbers) and .productId > 0 and .skuId > 0)' "$SEED_FILE" >/dev/null   || { echo "Seed must contain at least 10 positive productId/skuId pairs" >&2; exit 1; }

IID=$(aws ec2 describe-instances --region "$REGION" --filters "Name=tag:Role,Values=k6" "Name=instance-state-name,Values=running" --query 'Reservations[].Instances[].InstanceId | [0]' --output text)
case "$IID" in ""|None) echo "No running Role=k6 instance" >&2; exit 1 ;; esac

for ((attempt=1; attempt<=120; attempt++)); do
  status=$(aws ssm describe-instance-information --region "$REGION" --filters "Key=InstanceIds,Values=${IID}" --query 'InstanceInformationList[0].PingStatus' --output text)
  [ "$status" = Online ] && break
  [ "$attempt" -lt 120 ] || { echo "Role=k6 SSM did not become Online after 600s" >&2; exit 1; }
  sleep 5
done
BOOTSTRAP=$(jq -n '{commands: ["set -e\nattempt=1\nuntil command -v git >/dev/null && docker info >/dev/null 2>&1 && command -v curl >/dev/null; do\n  [ \"$attempt\" -lt 120 ] || exit 1\n  attempt=$((attempt + 1))\n  sleep 5\ndone"]}')
BOOTSTRAP_CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript --comment "wait for k6 bootstrap" --parameters "$BOOTSTRAP" --timeout-seconds 650 --query 'Command.CommandId' --output text)
for ((attempt=1; attempt<=130; attempt++)); do
  status=$(aws ssm list-command-invocations --region "$REGION" --command-id "$BOOTSTRAP_CID" --query 'CommandInvocations[0].Status' --output text)
  [ "$status" = Success ] && break
  case "$status" in Failed|Cancelled|TimedOut) echo "Role=k6 bootstrap check failed ($status)" >&2; exit 1 ;; esac
  [ "$attempt" -lt 130 ] || { echo "Role=k6 bootstrap did not finish after 650s" >&2; exit 1; }
  sleep 5
done

# ponytail: 1k representative pairs fit SSM command input; add S3 handoff only if a larger pool is measured necessary.
SEED_B64=$(jq -c 'if length <= 1000 then . else [range(0; length; (length / 1000 | floor)) as $i | .[$i]][:1000] end' "$SEED_FILE" | base64 | tr -d '\n')
read -r -d '' REMOTE <<'REMOTE' || true
set -euo pipefail
install -d -m 0777 /opt/loadtest/results
if [ ! -d /opt/loadtest/repo/.git ]; then git clone --no-checkout "$REPO_URL" /opt/loadtest/repo; fi
git -C /opt/loadtest/repo fetch --depth 1 origin "$REPO_REF"
expected_head=$(git -C /opt/loadtest/repo rev-parse 'FETCH_HEAD^{commit}')
git -C /opt/loadtest/repo checkout --detach --force "$expected_head"
[ "$(git -C /opt/loadtest/repo rev-parse HEAD)" = "$expected_head" ] || { echo "repo checkout mismatch" >&2; exit 1; }
printf '%s' "$SEED_B64" | base64 -d > /opt/loadtest/repo/k6/seed/productIds.json
summary="/opt/loadtest/results/${RUN_KEY}.summary.json"
console="/opt/loadtest/results/${RUN_KEY}.console.log"
timing="/opt/loadtest/results/${RUN_KEY}.timing.json"
observations="/opt/loadtest/results/${RUN_KEY}.observations"
stage_plan="/opt/loadtest/results/${RUN_KEY}.stage-plan"
bundle="/opt/loadtest/results/${RUN_KEY}.tgz"
started_epoch_file="/opt/loadtest/results/${RUN_KEY}.started-epoch"
started_utc_file="/opt/loadtest/results/${RUN_KEY}.started-utc"
rm -rf "$summary" "$console" "$timing" "$observations" "$stage_plan" "$bundle" "$started_epoch_file" "$started_utc_file"
mkdir -p "$observations"
docker pull grafana/k6:0.54.0 >"$console" 2>&1
set +e
docker run --rm --entrypoint sh --network host -v /opt/loadtest/repo:/work -w /work -v /opt/loadtest/results:/results -e RUN_KEY="$RUN_KEY" -e TARGET=aws -e MYSQL_THRESHOLD_RAMP="$MYSQL_THRESHOLD_RAMP" -e MYSQL_THRESHOLD_LOW_RAMP="$MYSQL_THRESHOLD_LOW_RAMP" -e PRODUCT_URL="$PRODUCT_URL" -e K6_PROMETHEUS_RW_SERVER_URL="$PROM_URL" -e 'K6_PROMETHEUS_RW_TREND_STATS=p(50),p(95),p(99)' -e 'K6_SUMMARY_TREND_STATS=med,p(95),p(99)' grafana/k6:0.54.0 -c 'date -u +%s > "/results/${RUN_KEY}.started-epoch"; date -u +%Y-%m-%dT%H:%M:%SZ > "/results/${RUN_KEY}.started-utc"; exec k6 run --summary-export "/results/${RUN_KEY}.summary.json" -o experimental-prometheus-rw k6/product-stock-mix.js' >>"$console" 2>&1
k6_status=$?
set -e
started_epoch=$(cat "$started_epoch_file")
started_utc=$(cat "$started_utc_file")
ended_epoch=$(date -u +%s)
ended_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '{"runKey":"%s","startedUtc":"%s","endedUtc":"%s","stageSeconds":%s}\n' "$RUN_KEY" "$started_utc" "$ended_utc" "$STAGE_SECONDS" > "$timing"

query_interval() {
  name=$1 query=$2 start=$3 end=$4 file=$5
  if ! curl -fsS --get "$PROM_QUERY_URL" --data-urlencode "query=$query" --data-urlencode "start=$start" --data-urlencode "end=$end" --data-urlencode 'step=15' > "$file"; then
    printf '{"status":"error","error":"Prometheus query failed","query":"%s"}\n' "$name" > "$file"
    return 1
  fi
}
require_workloads() {
  file=$1
  jq -e "$WORKLOAD_RESULT_JQ" "$file" >/dev/null
}
required_k6_ok=1
stage_count=4
[ "$MYSQL_THRESHOLD_RAMP" = true ] && stage_count=6
[ "$MYSQL_THRESHOLD_LOW_RAMP" = true ] && stage_count=5
/opt/loadtest/repo/k6/stage-windows.sh "$started_epoch" "$ended_epoch" "$STAGE_SECONDS" "$stage_count" > "$stage_plan"
while read -r stage start end; do
  hosts='k6|product-a|product-b|product-c|product-d|mysql-product|redis-product'
  query_interval cpu "1 - avg by (host) (rate(node_cpu_seconds_total{mode=\"idle\",host=~\"$hosts\"}[1m]))" "$start" "$end" "$observations/stage-${stage}-cpu.json" || true
  query_interval memory "1 - avg by (host) (node_memory_MemAvailable_bytes{host=~\"$hosts\"} / node_memory_MemTotal_bytes{host=~\"$hosts\"})" "$start" "$end" "$observations/stage-${stage}-memory.json" || true
  query_interval mysql_threads 'mysql_global_status_threads_running{db="product"}' "$start" "$end" "$observations/stage-${stage}-mysql-threads.json" || true
  query_interval stock_cache 'product_stock_cache_total{host=~"product-a|product-b|product-c|product-d"}' "$start" "$end" "$observations/stage-${stage}-stock-cache.json" || true
  for metric in rps p95 p99 error_rate; do
    file="$observations/stage-${stage}-k6-${metric}.json"
    case "$metric" in
      rps) query=$K6_RPS_QUERY ;;
      p95) query=$K6_P95_QUERY ;;
      p99) query=$K6_P99_QUERY ;;
      error_rate) query=$K6_ERROR_QUERY ;;
    esac
    if ! query_interval "k6_${metric}" "$query" "$start" "$end" "$file" || ! require_workloads "$file"; then
      echo "Missing read/write workload values for stage ${stage} k6_${metric}" >&2
      required_k6_ok=0
    fi
  done
done < "$stage_plan"
[ "$required_k6_ok" = 1 ] || k6_status=1
if [ -s "$summary" ]; then
  tar -czf "$bundle" -C /opt/loadtest/results "${RUN_KEY}.summary.json" "${RUN_KEY}.console.log" "${RUN_KEY}.timing.json" "${RUN_KEY}.stage-plan" "${RUN_KEY}.observations"
  bytes=$(wc -c < "$bundle" | tr -d ' '); encoded=$(base64 "$bundle" | tr -d '\n')
  encoded_chars=$(printf '%s' "$encoded" | wc -c | tr -d ' ')
  checksum=$(sha256sum "$bundle" | awk '{print $1}')
  if [ "$encoded_chars" -le 22000 ]; then
    printf 'K6_RESULT_BEGIN %s %s\n%s\nK6_RESULT_END\n' "$checksum" "$bytes" "$encoded"
  else
    printf 'K6_RESULT_CHUNKED %s %s %s\n' "$checksum" "$bytes" "$encoded_chars"
  fi
else
  echo "k6 summary was not created; console retained at $console" >&2; k6_status=1
fi
exit "$k6_status"
REMOTE

PARAMS=$(jq -n --arg repo "$REPO_URL" --arg ref "$REPO_REF" --arg seed "$SEED_B64" --arg run "$RUN_KEY" --arg prom "$PROM_URL" --arg prom_query "$PROM_QUERY_URL" --arg product "$PRODUCT_URL" --arg threshold "$MYSQL_THRESHOLD_RAMP" --arg threshold_low "$MYSQL_THRESHOLD_LOW_RAMP" --arg stage_seconds "$STAGE_SECONDS" --arg rps "$K6_RPS_QUERY" --arg p95 "$K6_P95_QUERY" --arg p99 "$K6_P99_QUERY" --arg error "$K6_ERROR_QUERY" --arg workload_jq "$WORKLOAD_RESULT_JQ" --arg script "$REMOTE" '{commands: ["REPO_URL=\($repo | @sh)\nREPO_REF=\($ref | @sh)\nSEED_B64=\($seed | @sh)\nRUN_KEY=\($run | @sh)\nPROM_URL=\($prom | @sh)\nPROM_QUERY_URL=\($prom_query | @sh)\nPRODUCT_URL=\($product | @sh)\nMYSQL_THRESHOLD_RAMP=\($threshold | @sh)\nMYSQL_THRESHOLD_LOW_RAMP=\($threshold_low | @sh)\nSTAGE_SECONDS=\($stage_seconds | @sh)\nK6_RPS_QUERY=\($rps | @sh)\nK6_P95_QUERY=\($p95 | @sh)\nK6_P99_QUERY=\($p99 | @sh)\nK6_ERROR_QUERY=\($error | @sh)\nWORKLOAD_RESULT_JQ=\($workload_jq | @sh)\n" + $script]}')
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript --comment "product stock mixed load test" --parameters "$PARAMS" --timeout-seconds 3600 --query 'Command.CommandId' --output text)

wait_for_command() {
  local command_id=$1 attempt status
  for ((attempt=1; attempt<=SSM_POLL_ATTEMPTS; attempt++)); do
    status=$(aws ssm list-command-invocations --region "$REGION" --command-id "$command_id" --query 'CommandInvocations[0].Status' --output text)
    case "$status" in
      Success) return 0 ;;
      Failed|Cancelled|TimedOut) echo "SSM command $command_id failed ($status)" >&2; return 1 ;;
      None|Pending|InProgress|Delayed|Cancelling) ;;
      *) echo "Unexpected SSM command status: $status" >&2; return 1 ;;
    esac
    [ "$attempt" -lt "$SSM_POLL_ATTEMPTS" ] || { echo "SSM command $command_id did not finish after $((SSM_POLL_ATTEMPTS * 5))s" >&2; return 1; }
    sleep 5
  done
}

result=1
wait_for_command "$CID" && result=0
output=$(mktemp); bundle=$(mktemp); encoded=$(mktemp)
trap 'rm -f "$output" "$bundle" "$encoded"' EXIT
aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" --query StandardOutputContent --output text --region "$REGION" > "$output"
header=$(sed -n '/^K6_RESULT_BEGIN /{p;q;}' "$output")
if [ -n "$header" ] && grep -q '^K6_RESULT_END$' "$output"; then
  expected_checksum=$(printf '%s\n' "$header" | awk '{print $2}'); expected_bytes=$(printf '%s\n' "$header" | awk '{print $3}')
  sed -n '/^K6_RESULT_BEGIN /,/^K6_RESULT_END$/p' "$output" | sed '1d;$d' | tr -d '\n' > "$encoded"
elif header=$(sed -n '/^K6_RESULT_CHUNKED /{p;q;}' "$output"); [ -n "$header" ]; then
  expected_checksum=$(printf '%s\n' "$header" | awk '{print $2}'); expected_bytes=$(printf '%s\n' "$header" | awk '{print $3}'); encoded_bytes=$(printf '%s\n' "$header" | awk '{print $4}')
  [[ "$encoded_bytes" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid chunked result header" >&2; exit 1; }
  for ((offset=0; offset<encoded_bytes; offset+=16000)); do
    count=$((encoded_bytes - offset)); [ "$count" -le 16000 ] || count=16000
    CHUNK_PARAMS=$(jq -n --arg run "$RUN_KEY" --argjson offset "$offset" --argjson count "$count" '{commands: ["base64 /opt/loadtest/results/\($run).tgz | tr -d \"\\n\" | dd bs=1 skip=\($offset) count=\($count) status=none"]}')
    chunk_cid=$(aws ssm send-command --region "$REGION" --instance-ids "$IID" --document-name AWS-RunShellScript --comment "fetch product stock result chunk" --parameters "$CHUNK_PARAMS" --timeout-seconds 60 --query 'Command.CommandId' --output text)
    wait_for_command "$chunk_cid" || exit 1
    chunk=$(aws ssm get-command-invocation --command-id "$chunk_cid" --instance-id "$IID" --query StandardOutputContent --output text --region "$REGION" | tr -d '\n')
    [ "$(printf '%s' "$chunk" | wc -c | tr -d ' ')" -eq "$count" ] || { echo "Result chunk size mismatch at offset $offset" >&2; exit 1; }
    printf '%s' "$chunk" >> "$encoded"
  done
  [ "$(wc -c < "$encoded" | tr -d ' ')" -eq "$encoded_bytes" ] || { echo "Result encoded size mismatch" >&2; exit 1; }
else
  echo "Result artifact missing or truncated; inspect /opt/loadtest/results/${RUN_KEY}.* on $IID" >&2; exit 1
fi
base64 -d "$encoded" > "$bundle"
if [ -s "$bundle" ]; then
  actual_checksum=$(shasum -a 256 "$bundle" | awk '{print $1}'); actual_bytes=$(wc -c < "$bundle" | tr -d ' ')
  [ "$actual_checksum:$actual_bytes" = "$expected_checksum:$expected_bytes" ] || { echo "Result artifact checksum/size mismatch; remote copy is /opt/loadtest/results/${RUN_KEY}.tgz" >&2; exit 1; }
  mkdir -p "$RESULT_DIR"; tar -xzf "$bundle" -C "$RESULT_DIR"
  echo "Saved result artifacts to $RESULT_DIR/${RUN_KEY}.{summary.json,console.log,timing.json,observations}"
else
  echo "Decoded result artifact is empty; inspect /opt/loadtest/results/${RUN_KEY}.* on $IID" >&2; exit 1
fi
exit "$result"
