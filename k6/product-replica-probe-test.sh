#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/source-mysql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
args="$*"
printf '%s\n' "$args" >> "$FAKE_SOURCE_LOG"
case "$args" in
  *'DROP TABLE IF EXISTS product_db.loadtest_replication_heartbeat'*)
    printf 'absent\n' > "$FAKE_SOURCE_SCHEMA"
    : > "$FAKE_SOURCE_ROWS"
    ;;
  *'CREATE TABLE product_db.loadtest_replication_heartbeat'*)
    if [ "$(cat "$FAKE_SOURCE_SCHEMA")" = absent ]; then printf 'composite\n' > "$FAKE_SOURCE_SCHEMA"; fi
    ;;
  *'DELETE FROM product_db.loadtest_replication_heartbeat'*)
    : > "$FAKE_SOURCE_ROWS"
    ;;
  *'INSERT INTO product_db.loadtest_replication_heartbeat'*)
    sequence=$(printf '%s\n' "$args" | sed -E "s/.*VALUES \('[^']*',(-?[0-9]+),.*/\1/")
    if [ "$(cat "$FAKE_SOURCE_SCHEMA")" = old ] && [ -s "$FAKE_SOURCE_ROWS" ]; then
      echo 'Duplicate entry for old PRIMARY KEY(run_key)' >&2
      exit 1
    fi
    grep -qx -- "$sequence" "$FAKE_SOURCE_ROWS" 2>/dev/null || printf '%s\n' "$sequence" >> "$FAKE_SOURCE_ROWS"
    if [ "$sequence" = 1 ] && grep -qx -- -1 "$FAKE_SOURCE_ROWS" && grep -qx -- 0 "$FAKE_SOURCE_ROWS"; then
      cp "$FAKE_SOURCE_ROWS" "$FAKE_COHABITING_ROWS"
    fi
    printf '%s\n' "$sequence" > "$FAKE_SOURCE_SEQUENCE"
    ;;
  *'SELECT COUNT(*)'*'sequence = 0'*)
    if ! grep -qx -- 0 "$FAKE_SOURCE_ROWS" 2>/dev/null; then
      if [ "$(cat "$FAKE_SOURCE_SCHEMA")" = old ] && [ -s "$FAKE_SOURCE_ROWS" ]; then printf '0\n'; exit 0; fi
      printf '0\n' >> "$FAKE_SOURCE_ROWS"
    fi
    printf '1\n'
    ;;
  *'SELECT COALESCE(MAX(sequence),0)'*)
    cat "$FAKE_SOURCE_SEQUENCE"
    ;;
esac
FAKE

cat > "$TMP/replica-mysql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'information_schema.statistics'*)
    case "$(cat "$FAKE_SOURCE_SCHEMA")" in
      composite) printf 'run_key,sequence\n' ;;
      old) printf 'run_key\n' ;;
      absent) printf '\n' ;;
    esac
    ;;
  *'SHOW REPLICA STATUS'*)
    cat <<'STATUS'
Replica_IO_Running: Yes
Replica_SQL_Running: Yes
Seconds_Behind_Source: 0
Retrieved_Gtid_Set: source:1-3
Executed_Gtid_Set: source:1-3
STATUS
    ;;
  *'SELECT COALESCE(MIN(sequence),0)'*)
    count=$(cat "$FAKE_REPLICA_COUNT")
    case "$count" in 0) value=0 ;; 1) value=1 ;; 2) value=2 ;; *) value=3 ;; esac
    printf '%s\n' "$((count + 1))" > "$FAKE_REPLICA_COUNT"
    printf '%s\n' "$value"
    ;;
  *'SELECT COALESCE(MAX(sequence),0)'*)
    printf '3\n'
    ;;
  *'START REPLICA SQL_THREAD'*)
    printf 'start-sql-thread\n' >> "$FAKE_CLEANUP_LOG"
    ;;
esac
FAKE

cat > "$TMP/monotonic-ms" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
count=$(cat "$FAKE_CLOCK_COUNT")
printf '%s\n' "$((count + 1))" > "$FAKE_CLOCK_COUNT"
printf '%s\n' "$((count * 1000))"
FAKE

cat > "$TMP/sleep" <<'FAKE'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$FAKE_SLEEP_LOG"
FAKE
cat > "$TMP/utc-now" <<'FAKE'
#!/usr/bin/env bash
printf '2026-08-25T00:00:00.123456Z\n'
FAKE
chmod +x "$TMP/source-mysql" "$TMP/replica-mysql" "$TMP/monotonic-ms" "$TMP/sleep" "$TMP/utc-now"

: > "$TMP/source.log"
: > "$TMP/cleanup.log"
: > "$TMP/sleep.log"
: > "$TMP/source-rows"
: > "$TMP/cohabiting-rows"
printf 'old\n' > "$TMP/source-schema"
printf '0\n' > "$TMP/source-sequence"
printf '0\n' > "$TMP/replica-count"
printf '0\n' > "$TMP/clock-count"

FAKE_SOURCE_LOG="$TMP/source.log" \
FAKE_SOURCE_SCHEMA="$TMP/source-schema" \
FAKE_SOURCE_ROWS="$TMP/source-rows" \
FAKE_COHABITING_ROWS="$TMP/cohabiting-rows" \
FAKE_SOURCE_SEQUENCE="$TMP/source-sequence" \
FAKE_REPLICA_COUNT="$TMP/replica-count" \
FAKE_CLEANUP_LOG="$TMP/cleanup.log" \
FAKE_CLOCK_COUNT="$TMP/clock-count" \
FAKE_SLEEP_LOG="$TMP/sleep.log" \
SOURCE_MYSQL="$TMP/source-mysql" \
REPLICA_MYSQL="$TMP/replica-mysql" \
MONOTONIC_MS="$TMP/monotonic-ms" \
SLEEP="$TMP/sleep" \
UTC_NOW="$TMP/utc-now" \
RESULT_DIR="$TMP/results" \
RUN_KEY=probe-test \
REPLICA_EXPERIMENT=steady \
PROBE_MAX_SEQUENCE=3 \
PROBE_DURATION_SECONDS=10 \
PROBE_RECOVERY_TIMEOUT_SECONDS=2 \
PROBE_START_TIMEOUT_SECONDS=2 \
  "$ROOT/k6/product-replica-probe.sh" || {
    echo 'old heartbeat schema was not upgraded before readiness' >&2
    exit 1
  }

lag="$TMP/results/probe-test.replica-lag.tsv"
status="$TMP/results/probe-test.replica-status.tsv"
faults="$TMP/results/probe-test.replica-faults.tsv"
stale="$TMP/results/probe-test.replica-stale.tsv"
[ "$(head -n 1 "$lag")" = $'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc' ]
[ "$(head -n 1 "$status")" = $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' ]
[ -f "$faults" ] && [ -f "$stale" ]
awk -F '\t' 'NR > 1 && $6 !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9][0-9][0-9][0-9][0-9]Z$/ {exit 1}' "$lag"
awk -F '\t' 'NR > 1 && $1 !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9][0-9][0-9][0-9][0-9]Z$/ {exit 1}' "$status"
awk -F '\t' 'NR > 1 && ($4 !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9][0-9][0-9][0-9][0-9]Z$/ || $5 !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9][.][0-9][0-9][0-9][0-9][0-9][0-9]Z$/) {exit 1}' "$faults"
awk -F '\t' 'NR > 1 { if ($5 < 0) exit 1; rows++ } END { exit rows == 0 }' "$lag"
[ "$(awk -F '\t' 'NR > 1 {printf "%s ", $2}' "$lag")" = '1 2 3 ' ] || {
  echo 'replica catch-up skipped an unseen marker' >&2
  exit 1
}
[ "$(awk -F '\t' 'NR > 1 {printf "%s:%s:%s:%s ", $2, $3, $4, $5}' "$lag")" = \
  '1:8000:11000:3000 2:10000:13000:3000 3:12000:15000:3000 ' ] || {
  echo 'marker lag timestamps were not measured after each replica observation' >&2
  exit 1
}
[ "$(tail -n 1 "$lag" | cut -f2)" = 3 ]
[ "$(sed -n -E "s/.*VALUES \('[^']*',([0-9]+),.*/\1/p" "$TMP/source.log" | tr '\n' ' ')" = '1 2 3 ' ]
[ "$(sort -n "$TMP/cohabiting-rows" | tr '\n' ' ')" = '-1 0 1 ' ] || {
  echo 'heartbeat upgrade did not allow readiness, start, and marker rows to coexist' >&2
  exit 1
}
grep -q 'DELETE FROM product_db.loadtest_replication_heartbeat' "$TMP/source.log"
grep -q 'start-sql-thread' "$TMP/cleanup.log"
[ -s "$TMP/sleep.log" ]

source_calls=$(wc -l < "$TMP/source.log")
if SOURCE_MYSQL="$TMP/source-mysql" REPLICA_MYSQL="$TMP/replica-mysql" \
  MONOTONIC_MS="$TMP/monotonic-ms" SLEEP="$TMP/sleep" RESULT_DIR="$TMP/invalid" \
  RUN_KEY="bad'key" REPLICA_EXPERIMENT=steady PROBE_MAX_SEQUENCE=1 \
  "$ROOT/k6/product-replica-probe.sh" 2>/dev/null; then
  echo 'invalid probe run key unexpectedly passed' >&2
  exit 1
fi
[ "$(wc -l < "$TMP/source.log")" -eq "$source_calls" ]

cat > "$TMP/fault-source-mysql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
args="$*"
printf '%s\n' "$args" >> "$FAKE_FAULT_SOURCE_LOG"
case "$args" in
  *'DROP TABLE IF EXISTS product_db.loadtest_replication_heartbeat'*) printf '0\n' > "$FAKE_FAULT_SCHEMA" ;;
  *'INSERT INTO product_db.loadtest_replication_heartbeat'*'VALUES ('*)
    sequence=$(printf '%s\n' "$args" | sed -E "s/.*VALUES \('[^']*',(-?[0-9]+),.*/\1/")
    printf '%s\n' "$sequence" > "$FAKE_FAULT_SOURCE_SEQUENCE"
    ;;
  *'SELECT COALESCE(MAX(sequence),0)'*) cat "$FAKE_FAULT_SOURCE_SEQUENCE" ;;
  *'SELECT COUNT(*)'*'sequence = 0'*) printf '1\n' ;;
  *'SELECT available_qty'*) printf '100\n' ;;
esac
FAKE

cat > "$TMP/fault-replica-mysql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
args="$*"
case "$args" in
  *'information_schema.statistics'*)
    if [ "$(cat "$FAKE_FAULT_SCHEMA")" = 0 ]; then printf '1\n' > "$FAKE_FAULT_SCHEMA"; printf '\n'; else printf 'run_key,sequence\n'; fi
    ;;
  *'SHOW REPLICA STATUS'*)
    printf '%s\n' 'Replica_IO_Running: Yes' 'Replica_SQL_Running: Yes' \
      'Seconds_Behind_Source: 0' 'Retrieved_Gtid_Set: source:1-3' 'Executed_Gtid_Set: source:1-3'
    ;;
  *'SELECT COALESCE(MIN(sequence),0)'*) cat "$FAKE_FAULT_SOURCE_SEQUENCE" ;;
  *'SELECT COALESCE(MAX(sequence),0)'*) cat "$FAKE_FAULT_SOURCE_SEQUENCE" ;;
  *'STOP REPLICA SQL_THREAD'*) printf 'stop-sql-thread\n' >> "$FAKE_FAULT_LIFECYCLE_LOG" ;;
  *'START REPLICA SQL_THREAD'*) printf 'start-sql-thread\n' >> "$FAKE_FAULT_LIFECYCLE_LOG" ;;
esac
FAKE

cat > "$TMP/fault-monotonic-ms" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
while ! mkdir "$FAKE_CLOCK_LOCK" 2>/dev/null; do :; done
count=$(cat "$FAKE_FAULT_CLOCK_COUNT")
printf '%s\n' "$((count + 1))" > "$FAKE_FAULT_CLOCK_COUNT"
rmdir "$FAKE_CLOCK_LOCK"
printf '%s\n' "$((count * 10000))"
FAKE

cat > "$TMP/curl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_HTTP_LOG"
case "$*" in
  *'/v1/stock/reserve'*) exit 28 ;;
  *'/v1/stock/release'*) printf '200' ;;
  *) printf '{"skus":[{"skuId":101,"availableQty":100}]}' ;;
esac
FAKE
chmod +x "$TMP/fault-source-mysql" "$TMP/fault-replica-mysql" "$TMP/fault-monotonic-ms" "$TMP/curl"

: > "$TMP/fault-source.log"
: > "$TMP/fault-lifecycle.log"
: > "$TMP/http.log"
printf '0\n' > "$TMP/fault-source-sequence"
printf '0\n' > "$TMP/fault-clock-count"
printf '0\n' > "$TMP/fault-schema"
if PATH="$TMP:$PATH" FAKE_FAULT_SOURCE_LOG="$TMP/fault-source.log" \
  FAKE_FAULT_SOURCE_SEQUENCE="$TMP/fault-source-sequence" \
  FAKE_FAULT_SCHEMA="$TMP/fault-schema" \
  FAKE_FAULT_LIFECYCLE_LOG="$TMP/fault-lifecycle.log" \
  FAKE_FAULT_CLOCK_COUNT="$TMP/fault-clock-count" FAKE_CLOCK_LOCK="$TMP/clock.lock" \
  FAKE_HTTP_LOG="$TMP/http.log" FAKE_SLEEP_LOG="$TMP/sleep.log" SOURCE_MYSQL="$TMP/fault-source-mysql" \
  REPLICA_MYSQL="$TMP/fault-replica-mysql" MONOTONIC_MS="$TMP/fault-monotonic-ms" \
  SLEEP="$TMP/sleep" RESULT_DIR="$TMP/fault-results" RUN_KEY=probe-failure \
  REPLICA_EXPERIMENT=lag PROBE_PRODUCT_ID=1 PROBE_SKU_ID=101 \
  PRODUCT_URL=http://product.example PROBE_MAX_SEQUENCE=1 PROBE_DURATION_SECONDS=1 \
  PROBE_RECOVERY_TIMEOUT_SECONDS=30 "$ROOT/k6/product-replica-probe.sh"; then
  echo 'timed-out reserve unexpectedly passed' >&2
  exit 1
fi
grep -q 'stop-sql-thread' "$TMP/fault-lifecycle.log"
grep -q 'start-sql-thread' "$TMP/fault-lifecycle.log"
grep -q 'replica-probe-probe-failure.*stock/release' "$TMP/http.log" || {
  echo 'timed-out primary reservation was not released' >&2
  exit 1
}
grep -q 'replica-probe-reject-probe-failure.*stock/release' "$TMP/http.log" || {
  echo 'second idempotency key was not cleanup-eligible' >&2
  exit 1
}
grep -q 'SELECT available_qty' "$TMP/fault-source.log" || {
  echo 'cleanup did not verify restored source stock' >&2
  exit 1
}
grep -q 'SELECT COUNT(\*) FROM product_db.loadtest_replication_heartbeat.*sequence = 0' "$TMP/fault-source.log" || {
  echo 'probe did not wait for the workload-start coordination marker' >&2
  exit 1
}

cat > "$TMP/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  stop)
    printf 'stop-container\n' >> "$FAKE_CONTAINER_LOG"
    [ "${FAKE_DOCKER_FAIL_STOP:-0}" != 1 ]
    ;;
  start) printf 'start-container\n' >> "$FAKE_CONTAINER_LOG" ;;
  *) echo "unexpected docker command: $*" >&2; exit 1 ;;
esac
FAKE
chmod +x "$TMP/docker"
: > "$TMP/container.log"
printf '0\n' > "$TMP/fault-source-sequence"
printf '0\n' > "$TMP/fault-clock-count"
PATH="$TMP:$PATH" FAKE_CONTAINER_LOG="$TMP/container.log" \
  FAKE_FAULT_SOURCE_LOG="$TMP/fault-source.log" FAKE_FAULT_SOURCE_SEQUENCE="$TMP/fault-source-sequence" \
  FAKE_FAULT_SCHEMA="$TMP/fault-schema" \
  FAKE_FAULT_LIFECYCLE_LOG="$TMP/fault-lifecycle.log" FAKE_FAULT_CLOCK_COUNT="$TMP/fault-clock-count" \
  FAKE_CLOCK_LOCK="$TMP/clock.lock" FAKE_SLEEP_LOG="$TMP/sleep.log" \
  SOURCE_MYSQL="$TMP/fault-source-mysql" REPLICA_MYSQL="$TMP/fault-replica-mysql" \
  MONOTONIC_MS="$TMP/fault-monotonic-ms" SLEEP="$TMP/sleep" RESULT_DIR="$TMP/outage-results" \
  RUN_KEY=probe-outage REPLICA_EXPERIMENT=outage PROBE_MAX_SEQUENCE=1 PROBE_DURATION_SECONDS=1 \
  PROBE_RECOVERY_TIMEOUT_SECONDS=30 "$ROOT/k6/product-replica-probe.sh"
[ "$(head -n 2 "$TMP/container.log" | tr '\n' ' ')" = 'stop-container start-container ' ]
awk -F '\t' '$2 == "container" && $3 == 60 {ok=1} END {exit !ok}' \
  "$TMP/outage-results/probe-outage.replica-faults.tsv"

: > "$TMP/container.log"
printf '0\n' > "$TMP/fault-source-sequence"
printf '0\n' > "$TMP/fault-clock-count"
if PATH="$TMP:$PATH" FAKE_CONTAINER_LOG="$TMP/container.log" FAKE_DOCKER_FAIL_STOP=1 \
  FAKE_FAULT_SOURCE_LOG="$TMP/fault-source.log" FAKE_FAULT_SOURCE_SEQUENCE="$TMP/fault-source-sequence" \
  FAKE_FAULT_SCHEMA="$TMP/fault-schema" \
  FAKE_FAULT_LIFECYCLE_LOG="$TMP/fault-lifecycle.log" FAKE_FAULT_CLOCK_COUNT="$TMP/fault-clock-count" \
  FAKE_CLOCK_LOCK="$TMP/clock.lock" FAKE_SLEEP_LOG="$TMP/sleep.log" \
  SOURCE_MYSQL="$TMP/fault-source-mysql" REPLICA_MYSQL="$TMP/fault-replica-mysql" \
  MONOTONIC_MS="$TMP/fault-monotonic-ms" SLEEP="$TMP/sleep" RESULT_DIR="$TMP/outage-failure-results" \
  RUN_KEY=probe-outage-failure REPLICA_EXPERIMENT=outage PROBE_MAX_SEQUENCE=1 PROBE_DURATION_SECONDS=1 \
  PROBE_RECOVERY_TIMEOUT_SECONDS=30 "$ROOT/k6/product-replica-probe.sh"; then
  echo 'failed container stop unexpectedly passed' >&2
  exit 1
fi
grep -q 'start-container' "$TMP/container.log" || {
  echo 'outage failure did not invoke container recovery trap' >&2
  exit 1
}
