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
printf '%s\n' \
  $'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc' \
  $'test\t3\t1000\t1200\t200\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-lag.tsv"
printf '%s\n' \
  $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' \
  $'2026-08-24T23:59:59Z\tYes\tNo\t1\tsource:1-3\tsource:1' \
  $'2026-08-25T00:00:00Z\tYes\tYes\t0\tsource:1-3\tsource:1-3' > "$artifacts/test.replica-status.tsv"
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'lag\tsql_thread\t5\t2026-08-24T23:50:00Z\t2026-08-24T23:50:05Z' \
  $'lag\tsql_thread\t30\t2026-08-24T23:52:00Z\t2026-08-24T23:52:30Z' \
  $'lag\tsql_thread\t60\t2026-08-24T23:55:00Z\t2026-08-24T23:56:00Z' \
  $'lag\tsource_final\t3\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-faults.tsv"
printf '%s\n' \
  $'pause_seconds\treplica_visible_qty\tprimary_reserve_http_status\tconvergence_qty\tfinal_restored_qty' \
  $'30\t100\t409\t0\t100' > "$artifacts/test.replica-stale.tsv"

RUN_KEY=test REPLICA_EXPERIMENT=lag VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'lag\tsql_thread\t5\t2026-08-24T23:50:00Z\t2026-08-24T23:50:05Z' \
  $'lag\tsql_thread\t30\t2026-08-24T23:52:00Z\t2026-08-24T23:52:30Z' \
  $'lag\tsql_thread\t60\t2026-08-24T23:55:00Z\t2026-08-24T23:56:00Z' \
  $'lag\tsource_final\t4\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-faults.tsv"
if RUN_KEY=test REPLICA_EXPERIMENT=lag VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'mismatched final marker unexpectedly passed' >&2
  exit 1
fi
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'lag\tsql_thread\t5\t2026-08-24T23:50:00Z\t2026-08-24T23:50:05Z' \
  $'lag\tsql_thread\t30\t2026-08-24T23:52:00Z\t2026-08-24T23:52:30Z' \
  $'lag\tsql_thread\t60\t2026-08-24T23:55:00Z\t2026-08-24T23:56:00Z' \
  $'lag\tsource_final\t3\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-faults.tsv"
printf '%s\n' \
  $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' \
  $'2026-08-24T23:59:59Z\tYes\tNo\t1\tsource:1-3\tsource:1' \
  $'2026-08-25T00:00:00Z\tYes\tNo\t1\tsource:1-3\tsource:1' > "$artifacts/test.replica-status.tsv"
if RUN_KEY=test REPLICA_EXPERIMENT=lag VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'unrecovered replica threads unexpectedly passed' >&2
  exit 1
fi
printf '%s\n' \
  $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' \
  $'2026-08-24T23:59:59Z\tYes\tNo\t1\tsource:1-3\tsource:1' \
  $'2026-08-25T00:00:00Z\tYes\tYes\t0\tsource:1-3\tsource:1-3' > "$artifacts/test.replica-status.tsv"
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'lag\tsql_thread\t5\t2026-08-24T23:50:00Z\t2026-08-24T23:50:05Z' \
  $'lag\tsql_thread\t30\t2026-08-24T23:52:00Z\t2026-08-24T23:52:30Z' \
  $'lag\tsql_thread\t61\t2026-08-24T23:55:00Z\t2026-08-24T23:56:01Z' \
  $'lag\tsource_final\t3\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-faults.tsv"
if RUN_KEY=test REPLICA_EXPERIMENT=lag VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid lag fault schedule unexpectedly passed' >&2
  exit 1
fi
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'lag\tsql_thread\t5\t2026-08-24T23:50:00Z\t2026-08-24T23:50:05Z' \
  $'lag\tsql_thread\t30\t2026-08-24T23:52:00Z\t2026-08-24T23:52:30Z' \
  $'lag\tsql_thread\t60\t2026-08-24T23:55:00Z\t2026-08-24T23:56:00Z' \
  $'lag\tsource_final\t3\t2026-08-25T00:00:00Z\t2026-08-25T00:00:00Z' > "$artifacts/test.replica-faults.tsv"
printf '%s\n' \
  $'pause_seconds\treplica_visible_qty\tprimary_reserve_http_status\tconvergence_qty\tfinal_restored_qty' \
  $'30\t99\t409\t0\t100' > "$artifacts/test.replica-stale.tsv"
if RUN_KEY=test REPLICA_EXPERIMENT=lag VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid stale-read proof unexpectedly passed' >&2
  exit 1
fi

printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"target":"primary","outcome":"fallback"},"values":[[1,"0"],[2,"0"]]}]}}' > "$artifacts/test.observations/stage-1-datasource-route.json"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"target":"primary","outcome":"fallback"},"values":[[3,"0"],[4,"2"]]}]}}' > "$artifacts/test.observations/stage-2-datasource-route.json"
printf '%s\n' '{"metrics":{"stock_server_error_rate":{"values":{"rate":0}}}}' > "$artifacts/test.summary.json"
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'outage\tcontainer\t60\t2026-08-24T23:50:00Z\t2026-08-24T23:51:00Z' > "$artifacts/test.replica-faults.tsv"
RUN_KEY=test REPLICA_EXPERIMENT=outage VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'outage\tcontainer\t61\t2026-08-24T23:50:00Z\t2026-08-24T23:51:01Z' > "$artifacts/test.replica-faults.tsv"
if RUN_KEY=test REPLICA_EXPERIMENT=outage VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'invalid outage fault duration unexpectedly passed' >&2
  exit 1
fi
printf '%s\n' \
  $'mode\tfault\tduration_seconds\tstarted_utc\tended_utc' \
  $'outage\tcontainer\t60\t2026-08-24T23:50:00Z\t2026-08-24T23:51:00Z' > "$artifacts/test.replica-faults.tsv"
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"target":"primary","outcome":"fallback"},"values":[[3,"2"],[4,"0"]]}]}}' > "$artifacts/test.observations/stage-2-datasource-route.json"
if RUN_KEY=test REPLICA_EXPERIMENT=outage VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'falling fallback counter unexpectedly passed' >&2
  exit 1
fi
printf '%s\n' '{"status":"success","data":{"result":[{"metric":{"target":"primary","outcome":"fallback"},"values":[[3,"0"],[4,"2"]]}]}}' > "$artifacts/test.observations/stage-2-datasource-route.json"
printf '%s\n' '{"metrics":{"stock_server_error_rate":{"values":{"rate":0.01}}}}' > "$artifacts/test.summary.json"
if RUN_KEY=test REPLICA_EXPERIMENT=outage VALIDATE_REPLICA_ARTIFACT_DIR="$artifacts" \
  REPO_REF=test PROM_URL=invalid "$ROOT/k6/run-product-stock-mix-aws.sh"; then
  echo 'outage server errors unexpectedly passed' >&2
  exit 1
fi

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
  'ssm list-command-invocations') printf 'Success\n' ;;
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
        seed=$(printf '%s\n' "$command" | sed -n "s/^SEED_B64='\([^']*\)'$/\1/p")
        printf '%s' "$seed" | base64 -D > "$FAKE_SEED_CAPTURE"
        printf 'k6-command\n'
        ;;
      *) printf 'unexpected send-command comment: %s\n' "$comment" >&2; exit 1 ;;
    esac
    ;;
  'ssm get-command-invocation')
    command_id=
    while [ "$#" -gt 0 ]; do
      case "$1" in --command-id) command_id=$2; shift 2 ;; *) shift ;; esac
    done
    case "$command_id" in
      k6-command) bundle=$FAKE_MAIN_BUNDLE ;;
      probe-command) bundle=$FAKE_PROBE_BUNDLE ;;
      *) echo "unexpected invocation: $command_id" >&2; exit 1 ;;
    esac
    checksum=$(shasum -a 256 "$bundle" | awk '{print $1}')
    bytes=$(wc -c < "$bundle" | tr -d ' ')
    printf 'K6_RESULT_BEGIN %s %s\n' "$checksum" "$bytes"
    base64 < "$bundle" | tr -d '\n'
    printf '\nK6_RESULT_END\n'
    ;;
  *) echo "unexpected aws call: $service $operation" >&2; exit 1 ;;
esac
FAKE
chmod +x "$orchestration/bin/aws"

run_fake_aws() {
  local run=$1 mode=$2 results="$orchestration/results-$1"
  : > "$orchestration/aws.log"
  mkdir -p "$results"
  PATH="$orchestration/bin:$PATH" FAKE_AWS_LOG="$orchestration/aws.log" \
    FAKE_SEED_CAPTURE="$orchestration/captured-seed.json" \
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
