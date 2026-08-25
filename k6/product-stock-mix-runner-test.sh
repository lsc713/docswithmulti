#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
queries=$(PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$queries" == *'sum by (workload) (rate(k6_http_reqs_total{run="'* ]]
[[ "$queries" == *'k6_stock_mix_workload_duration_p95{run="'* ]]
[[ "$queries" == *'k6_stock_mix_workload_duration_p99{run="'* ]]
[[ "$queries" == *'k6_stock_mix_workload_failure_rate{run="'* ]]
[[ "$queries" == *'product_detail_cache_total'* ]] || { echo 'detail cache query missing' >&2; exit 1; }
[[ "$queries" == *'product_stock_cache_total'* ]] || { echo 'stock cache query missing' >&2; exit 1; }
[[ "$queries" == *'product_datasource_route_total'* ]] || { echo 'route metric query missing' >&2; exit 1; }
[[ "$queries" != *'_bucket'* && "$queries" != *'k6_http_req_failed_'* && "$queries" != *'max\ by'* ]]
if STOCK_MIX_DISTRIBUTION=invalid PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid stock distribution unexpectedly passed' >&2
  exit 1
fi
if STOCK_ITEMS_PER_RESERVATION=0 PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid reservation item count unexpectedly passed' >&2
  exit 1
fi
if REPLICA_EXPERIMENT=invalid PRINT_STAGE_QUERIES=1 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid replica experiment unexpectedly passed' >&2
  exit 1
fi
runner="$ROOT/k6/run-product-stock-mix-aws.sh"
rg -q -F 'mysql-product-replica' "$runner"
product_compose="$ROOT/infra/load-test/deploy/product.compose.yml"
rg -q -F 'PRODUCT_DATASOURCE_REPLICA_ENABLED:' "$product_compose"
rg -q -F 'PRODUCT_DATASOURCE_REPLICA_URL:' "$product_compose"
rg -q -F 'PRODUCT_DATASOURCE_REPLICA_USERNAME:' "$product_compose"
rg -q -F 'PRODUCT_DATASOURCE_REPLICA_PASSWORD:' "$product_compose"
rg -q -F 'SPRING_DATASOURCE_URL: "jdbc:mysql://10.0.1.33:3306/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"' "$product_compose"
rg -q -F -- '-e STOCK_ITEMS_PER_RESERVATION="$ITEMS_PER_RESERVATION"' "$runner"
pull_line=$(rg -n -m1 -F 'docker pull grafana/k6:0.54.0' "$runner" | cut -d: -f1)
start_line=$(rg -n -m1 -F 'date -u +%s > "/results/${RUN_KEY}.started-epoch"' "$runner" | cut -d: -f1)
stage_line=$(rg -n -m1 -F '/opt/loadtest/repo/k6/stage-windows.sh "$started_epoch" "$ended_epoch" "$STAGE_SECONDS"' "$runner" | cut -d: -f1)
[[ -n "$pull_line" && -n "$start_line" && -n "$stage_line" ]]
(( pull_line < start_line && start_line < stage_line ))
plan=$(PRINT_STAGE_PLAN=1 STAGE_START_EPOCH=1000 STAGE_END_EPOCH=1720 REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh")
[[ "$plan" == $'1 1000 1180\n2 1180 1360\n3 1360 1540\n4 1540 1720' ]]
good=$(mktemp); empty=$(mktemp); missing_read=$(mktemp); missing_write=$(mktemp); empty_values=$(mktemp); failed=$(mktemp); operation_series=$(mktemp)
trap 'rm -f "$good" "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed" "$operation_series"' EXIT
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$good"
printf '%s\n' '{"status":"success","data":{"result":[]}}' > "$empty"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$missing_read"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]}]}}' > "$missing_write"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[]},{"metric":{"workload":"write"},"values":[]}]}}' > "$empty_values"
printf '%s\n' '{"status":"error","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]}]}}' > "$failed"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"1"]]},{"metric":{"workload":"write"},"values":[[1,"1"]]},{"metric":{"workload":"write","operation":"reserve"},"values":[[1,"1"]]},{"metric":{"workload":"write","operation":"release"},"values":[[1,"1"]]}]}}' > "$operation_series"
VERIFY_WORKLOAD_FILE="$good" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
for invalid in "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed" "$operation_series"; do
  if VERIFY_WORKLOAD_FILE="$invalid" REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
    echo 'invalid workload query unexpectedly passed' >&2
    exit 1
  fi
done

artifacts=$(mktemp -d)
trap 'rm -f "$good" "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed" "$operation_series"; rm -rf "$artifacts"' EXIT
mkdir -p "$artifacts/test.observations"
write_lag_artifacts() {
  printf '%s\n' \
    $'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc' \
    $'test\t1\t1000\t2000\t1000\t2026-08-25T00:01:01Z' \
    $'test\t2\t2000\t32000\t30000\t2026-08-25T00:02:32Z' \
    $'test\t3\t3000\t63000\t60000\t2026-08-25T00:04:03Z' > "$artifacts/test.replica-lag.tsv"
  printf '%s\n' $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' > "$artifacts/test.replica-status.tsv"
  for second in 1 2 3 4; do printf '2026-08-25T00:01:%02dZ\tYes\tNo\t%s\tsource:1-3\tsource:1\n' "$second" "$second"; done >> "$artifacts/test.replica-status.tsv"
  for second in $(seq 1 29); do printf '2026-08-25T00:02:%02dZ\tYes\tNo\t%s\tsource:1-3\tsource:1\n' "$second" "$second"; done >> "$artifacts/test.replica-status.tsv"
  for second in $(seq 1 59); do printf '2026-08-25T00:04:%02dZ\tYes\tNo\t%s\tsource:1-3\tsource:2\n' "$second" "$second"; done >> "$artifacts/test.replica-status.tsv"
  printf '2026-08-25T00:07:05Z\tYes\tYes\t0\tsource:1-3\tsource:1-3\n' >> "$artifacts/test.replica-status.tsv"
  printf '%s\n' \
    $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
    $'lag\tsql_thread\t5\t2026-08-25T00:01:00Z\t2026-08-25T00:01:05Z' \
    $'lag\tsql_thread\t30\t2026-08-25T00:02:00Z\t2026-08-25T00:02:30Z' \
    $'lag\tsql_thread\t60\t2026-08-25T00:04:00Z\t2026-08-25T00:05:00Z' \
    $'lag\tsource_final\t3\t2026-08-25T00:07:05Z\t2026-08-25T00:07:05Z' > "$artifacts/test.replica-faults.tsv"
  printf '%s\n' \
    $'pause_seconds\treplica_visible_qty\tprimary_reserve_http_status\tconvergence_qty\tfinal_restored_qty' \
    $'30\t100\t409\t0\t100' > "$artifacts/test.replica-stale.tsv"
}
validate_artifacts() {
  RUN_KEY=test REPLICA_EXPERIMENT=$1 VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
    REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
}
expect_invalid_artifacts() {
  local mode=$1 message=$2
  if validate_artifacts "$mode"; then echo "$message unexpectedly passed" >&2; exit 1; fi
}

write_lag_artifacts
validate_artifacts lag
sed -i.bak '1s/run_key/bad_key/' "$artifacts/test.replica-lag.tsv"
expect_invalid_artifacts lag 'malformed lag header'
write_lag_artifacts
printf 'bad\trow\n' >> "$artifacts/test.replica-status.tsv"
expect_invalid_artifacts lag 'malformed status width'
write_lag_artifacts
sed -i.bak 's/lag\tsql_thread\t30/lag\tsql_thread\tthirty/' "$artifacts/test.replica-faults.tsv"
expect_invalid_artifacts lag 'non-numeric fault duration'
write_lag_artifacts
printf '30\t100\t409\t0\t100\textra\n' >> "$artifacts/test.replica-stale.tsv"
expect_invalid_artifacts lag 'malformed stale width'
write_lag_artifacts
sed -i.bak 's/00:02:29Z\tYes\tNo/00:02:29Z\tNo\tNo/' "$artifacts/test.replica-status.tsv"
expect_invalid_artifacts lag 'I/O stopped during 30-second pause'
write_lag_artifacts
sed -i.bak 's/lag\tsource_final\t3/lag\tsource_final\t4/' "$artifacts/test.replica-faults.tsv"
expect_invalid_artifacts lag 'mismatched final marker'

write_lag_artifacts
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'outage\tcontainer\t60\t2026-08-25T00:01:00Z\t2026-08-25T00:02:00Z' > "$artifacts/test.replica-faults.tsv"
printf '%s\n' '{"status":"success","data":{"result":[' \
  '{"metric":{"host":"product-a","target":"primary","outcome":"fallback"},"values":[[1,"10"],[2,"12"]]},' \
  '{"metric":{"host":"product-b","target":"primary","outcome":"fallback"},"values":[[1,"4"],[2,"4"]]}]}}' | tr -d '\n' > "$artifacts/test.observations/stage-1-datasource-route.json"
printf '\n' >> "$artifacts/test.observations/stage-1-datasource-route.json"
printf '%s\n' '{"status":"success","data":{"result":[' \
  '{"metric":{"host":"product-a","target":"primary","outcome":"fallback"},"values":[[3,"1"],[4,"2"]]},' \
  '{"metric":{"host":"product-b","target":"primary","outcome":"fallback"},"values":[[3,"4"],[4,"4"]]},' \
  '{"metric":{"host":"product-c","target":"primary","outcome":"fallback"},"values":[[3,"5"],[4,"5"]]}]}}' | tr -d '\n' > "$artifacts/test.observations/stage-2-datasource-route.json"
printf '\n' >> "$artifacts/test.observations/stage-2-datasource-route.json"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"workload":"read"},"values":[[1,"0"],[2,"0"]]},{"metric":{"workload":"write"},"values":[[1,"0"],[2,"0"]]}]}}' > "$artifacts/test.observations/stage-1-k6-error_rate.json"
printf '%s\n' '{"metrics":{"stock_server_error_rate":{"values":{"rate":0}}}}' > "$artifacts/test.summary.json"
validate_artifacts outage
sed -i.bak 's/\[2,"0"\]/[2,"0.01"]/' "$artifacts/test.observations/stage-1-k6-error_rate.json"
expect_invalid_artifacts outage 'replica read failure'
sed -i.bak 's/\[2,"0.01"\]/[2,"0"]/' "$artifacts/test.observations/stage-1-k6-error_rate.json"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"host":"product-a","target":"primary","outcome":"fallback"},"values":[[1,"10"],[2,"10"]]},{"metric":{"host":"product-b","target":"primary","outcome":"fallback"},"values":[[1,"4"],[2,"4"]]}]}}' > "$artifacts/test.observations/stage-1-datasource-route.json"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"host":"product-a","target":"primary","outcome":"fallback"},"values":[[3,"10"],[4,"10"]]},{"metric":{"host":"product-c","target":"primary","outcome":"fallback"},"values":[[3,"5"],[4,"5"]]}]}}' > "$artifacts/test.observations/stage-2-datasource-route.json"
expect_invalid_artifacts outage 'flat multi-series fallback counters'

orchestration=$(mktemp -d)
trap 'rm -f "$good" "$empty" "$missing_read" "$missing_write" "$empty_values" "$failed" "$operation_series"; rm -rf "$artifacts" "$orchestration"' EXIT
mkdir -p "$orchestration/bin"
printf '%s\n' '[{"productId":1,"skuId":101},{"productId":2,"skuId":102},{"productId":3,"skuId":103},{"productId":4,"skuId":104},{"productId":5,"skuId":105},{"productId":6,"skuId":106},{"productId":7,"skuId":107},{"productId":8,"skuId":108},{"productId":9,"skuId":109},{"productId":10,"skuId":110}]' > "$orchestration/seed.json"

make_main_bundle() {
  local run=$1 dir="$orchestration/main-$1"
  mkdir -p "$dir/$run.observations"
  printf '%s\n' '{"metrics":{"stock_server_error_rate":{"values":{"rate":0}}}}' > "$dir/$run.summary.json"
  : > "$dir/$run.console.log"
  printf '%s\n' '{}' > "$dir/$run.timing.json"
  printf '%s\n' '1 1 2' > "$dir/$run.stage-plan"
  tar -czf "$orchestration/$run.main.tgz" -C "$dir" \
    "$run.summary.json" "$run.console.log" "$run.timing.json" "$run.stage-plan" "$run.observations"
}

make_probe_bundle() {
  local run=$1 dir="$orchestration/probe-$1"
  mkdir -p "$dir"
  printf '%s\n' \
    $'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc' \
    "$run"$'\t3\t1000\t1200\t200\t2026-08-25T00:00:00Z' > "$dir/$run.replica-lag.tsv"
  printf '%s\n' \
    $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' \
    $'2026-08-25T00:00:00Z\tYes\tYes\t0\tsource:1-3\tsource:1-3' > "$dir/$run.replica-status.tsv"
  printf '%s\n' \
    $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
    $'steady\tsource_final\t3\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$dir/$run.replica-faults.tsv"
  printf '%s\n' $'pause_seconds\treplica_visible_qty\tprimary_reserve_http_status\tconvergence_qty\tfinal_restored_qty' > "$dir/$run.replica-stale.tsv"
  tar -czf "$orchestration/$run.probe.tgz" -C "$dir" \
    "$run.replica-lag.tsv" "$run.replica-status.tsv" "$run.replica-faults.tsv" "$run.replica-stale.tsv"
}

cat > "$orchestration/bin/aws" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
service=$1 operation=$2
shift 2
case "$service $operation" in
  'ec2 describe-instances')
    case "$*" in
      *'Role,Values=mysql-product-replica'*) printf 'resolve replica\n' >> "$FAKE_AWS_LOG"; printf 'i-replica\n' ;;
      *) printf 'resolve k6\n' >> "$FAKE_AWS_LOG"; printf 'i-k6\n' ;;
    esac
    ;;
  'ssm describe-instance-information') printf 'Online\n' ;;
  'ssm list-command-invocations')
    command_id=
    while [ "$#" -gt 0 ]; do
      case "$1" in --command-id) command_id=$2; shift 2 ;; *) shift ;; esac
    done
    if [ "$command_id" = "${FAKE_FAILED_COMMAND_ID:-}" ]; then printf 'Failed\n'; else printf 'Success\n'; fi
    ;;
  'ssm send-command')
    comment= parameters=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --comment) comment=$2; shift 2 ;;
        --parameters) parameters=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    case "$comment" in
      'wait for k6 bootstrap') printf 'bootstrap\n' >> "$FAKE_AWS_LOG"; printf 'bootstrap-command\n' ;;
      'product replica '*' experiment') printf 'send probe\n' >> "$FAKE_AWS_LOG"; printf 'probe-command\n' ;;
      'product stock mixed load test')
        printf 'send k6\n' >> "$FAKE_AWS_LOG"
        command=$(printf '%s\n' "$parameters" | jq -r '.commands[0]')
        printf '%s\n' "$command" | grep -q 'sequence = -1' || {
          echo 'k6 command does not wait for probe readiness' >&2
          exit 1
        }
        printf '%s\n' "$command" | grep -q "VALUES ('\$RUN_KEY',0,UTC_TIMESTAMP(6))" || {
          echo 'k6 command does not publish workload start' >&2
          exit 1
        }
        seed=$(printf '%s\n' "$command" | sed -n "s/^SEED_B64='\([^']*\)'$/\1/p")
        printf '%s' "$seed" | base64 -D > "$FAKE_SEED_CAPTURE"
        printf 'k6-command\n'
        ;;
      'fetch product stock result chunk')
        command=$(printf '%s\n' "$parameters" | jq -r '.commands[0]')
        offset=$(printf '%s\n' "$command" | sed -n 's/.*skip=\([0-9][0-9]*\).*/\1/p')
        count=$(printf '%s\n' "$command" | sed -n 's/.*count=\([0-9][0-9]*\).*/\1/p')
        case "$command" in *replica.tgz*) bundle=$FAKE_PROBE_BUNDLE ;; *) bundle=$FAKE_MAIN_BUNDLE ;; esac
        printf '%s\n%s\n%s\n' "$bundle" "$offset" "$count" > "$FAKE_CHUNK_PARAMS"
        printf 'fetch chunk\n' >> "$FAKE_AWS_LOG"
        printf 'chunk-command\n'
        ;;
      *) printf 'unexpected send-command comment: %s\n' "$comment" >&2; exit 1 ;;
    esac
    ;;
  'ssm get-command-invocation')
    command_id=
    while [ "$#" -gt 0 ]; do
      case "$1" in --command-id) command_id=$2; shift 2 ;; *) shift ;; esac
    done
    if [ "$command_id" = chunk-command ]; then
      bundle=$(sed -n '1p' "$FAKE_CHUNK_PARAMS")
      offset=$(sed -n '2p' "$FAKE_CHUNK_PARAMS")
      count=$(sed -n '3p' "$FAKE_CHUNK_PARAMS")
      chunk=$(base64 < "$bundle" | tr -d '\n' | dd bs=1 skip="$offset" count="$count" 2>/dev/null)
      if [ "${FAKE_BAD_CHUNK:-0}" = 1 ]; then chunk=${chunk%?}; fi
      printf '%s\n' "$chunk"
      exit 0
    fi
    case "$command_id" in
      k6-command) bundle=$FAKE_MAIN_BUNDLE ;;
      probe-command) bundle=$FAKE_PROBE_BUNDLE ;;
      *) echo "unexpected invocation: $command_id" >&2; exit 1 ;;
    esac
    checksum=$(shasum -a 256 "$bundle" | awk '{print $1}')
    bytes=$(wc -c < "$bundle" | tr -d ' ')
    if [ "$command_id" = "${FAKE_CHUNKED_COMMAND_ID:-}" ]; then
      encoded_chars=$(base64 < "$bundle" | tr -d '\n' | wc -c | tr -d ' ')
      printf 'K6_RESULT_CHUNKED %s %s %s\n' "$checksum" "$bytes" "$encoded_chars"
      exit 0
    fi
    printf 'K6_RESULT_BEGIN %s %s\n' "$checksum" "$bytes"
    base64 < "$bundle" | tr -d '\n'
    printf '\nK6_RESULT_END\n'
    ;;
  *) echo "unexpected aws call: $service $operation" >&2; exit 1 ;;
esac
FAKE
chmod +x "$orchestration/bin/aws"
cat > "$orchestration/bin/mktemp" <<'FAKE'
#!/usr/bin/env bash
exec /usr/bin/mktemp "$FAKE_TMPDIR/fetch.XXXXXX"
FAKE
chmod +x "$orchestration/bin/mktemp"

run_fake_aws() {
  local run=$1 mode=$2 results="$orchestration/results-$1"
  : > "$orchestration/aws.log"
  mkdir -p "$results" "$orchestration/fetch-tmp"
  PATH="$orchestration/bin:$PATH" FAKE_AWS_LOG="$orchestration/aws.log" \
    FAKE_SEED_CAPTURE="$orchestration/captured-seed.json" \
    FAKE_CHUNK_PARAMS="$orchestration/chunk.params" FAKE_TMPDIR="$orchestration/fetch-tmp" \
    FAKE_MAIN_BUNDLE="$orchestration/$run.main.tgz" \
    FAKE_PROBE_BUNDLE="$orchestration/$run.probe.tgz" \
    SEED_FILE="$orchestration/seed.json" RESULT_DIR="$results" RUN_KEY="$run" \
    REPLICA_EXPERIMENT="$mode" REPO_REF=test PRODUCT_URL=http://product.example \
    PROM_URL=http://prom.example/api/v1/write SSM_POLL_ATTEMPTS=2 \
    "$ROOT/k6/run-product-stock-mix-aws.sh"
}

make_main_bundle baseline-test
make_probe_bundle baseline-test
run_fake_aws baseline-test baseline
! grep -q 'resolve replica' "$orchestration/aws.log"
jq -e 'length == 9 and .[0] == {"productId":2,"skuId":102}' "$orchestration/captured-seed.json" >/dev/null

make_main_bundle steady-test
make_probe_bundle steady-test
run_fake_aws steady-test steady
grep -q 'resolve replica' "$orchestration/aws.log"
[ "$(grep '^send ' "$orchestration/aws.log" | tr '\n' ' ')" = 'send probe send k6 ' ]
for suffix in replica-lag.tsv replica-status.tsv replica-faults.tsv replica-stale.tsv; do
  [ -f "$orchestration/results-steady-test/steady-test.$suffix" ]
  tar -tzf "$orchestration/results-steady-test/steady-test.tgz" | grep -qx "steady-test.$suffix"
done

make_main_bundle chunk-test
make_probe_bundle chunk-test
FAKE_CHUNKED_COMMAND_ID=probe-command run_fake_aws chunk-test steady
grep -q 'fetch chunk' "$orchestration/aws.log"
[ -f "$orchestration/results-chunk-test/chunk-test.replica-lag.tsv" ]

make_main_bundle failed-test
make_probe_bundle failed-test
if FAKE_FAILED_COMMAND_ID=probe-command run_fake_aws failed-test steady; then
  echo 'failed probe command did not fail the runner' >&2
  exit 1
fi

make_main_bundle bad-chunk-test
make_probe_bundle bad-chunk-test
if FAKE_CHUNKED_COMMAND_ID=probe-command FAKE_BAD_CHUNK=1 run_fake_aws bad-chunk-test steady; then
  echo 'short SSM chunk unexpectedly passed' >&2
  exit 1
fi
[ -z "$(find "$orchestration/fetch-tmp" -type f -print -quit)" ] || {
  echo 'fetch temporary files leaked after a chunk failure' >&2
  exit 1
}
