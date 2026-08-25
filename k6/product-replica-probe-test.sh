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
  *'INSERT INTO product_db.loadtest_replication_heartbeat'*)
    sequence=$(printf '%s\n' "$args" | sed -E "s/.*VALUES \('[^']*',([0-9]+),.*/\1/")
    printf '%s\n' "$sequence" > "$FAKE_SOURCE_SEQUENCE"
    ;;
  *'SELECT COALESCE(sequence,0)'*)
    cat "$FAKE_SOURCE_SEQUENCE"
    ;;
esac
FAKE

cat > "$TMP/replica-mysql" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *'SHOW REPLICA STATUS'*)
    cat <<'STATUS'
Replica_IO_Running: Yes
Replica_SQL_Running: Yes
Seconds_Behind_Source: 0
Retrieved_Gtid_Set: source:1-3
Executed_Gtid_Set: source:1-3
STATUS
    ;;
  *'SELECT COALESCE(sequence,0)'*)
    count=$(cat "$FAKE_REPLICA_COUNT")
    case "$count" in 0) value=0 ;; 1) value=1 ;; *) value=3 ;; esac
    printf '%s\n' "$((count + 1))" > "$FAKE_REPLICA_COUNT"
    printf '%s\n' "$value"
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
case "$count" in 0|1) value=0 ;; 2) value=1000 ;; *) value=2000 ;; esac
printf '%s\n' "$((count + 1))" > "$FAKE_CLOCK_COUNT"
printf '%s\n' "$value"
FAKE

cat > "$TMP/sleep" <<'FAKE'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$FAKE_SLEEP_LOG"
FAKE
chmod +x "$TMP/source-mysql" "$TMP/replica-mysql" "$TMP/monotonic-ms" "$TMP/sleep"

: > "$TMP/source.log"
: > "$TMP/cleanup.log"
: > "$TMP/sleep.log"
printf '0\n' > "$TMP/source-sequence"
printf '0\n' > "$TMP/replica-count"
printf '0\n' > "$TMP/clock-count"

FAKE_SOURCE_LOG="$TMP/source.log" \
FAKE_SOURCE_SEQUENCE="$TMP/source-sequence" \
FAKE_REPLICA_COUNT="$TMP/replica-count" \
FAKE_CLEANUP_LOG="$TMP/cleanup.log" \
FAKE_CLOCK_COUNT="$TMP/clock-count" \
FAKE_SLEEP_LOG="$TMP/sleep.log" \
SOURCE_MYSQL="$TMP/source-mysql" \
REPLICA_MYSQL="$TMP/replica-mysql" \
MONOTONIC_MS="$TMP/monotonic-ms" \
SLEEP="$TMP/sleep" \
RESULT_DIR="$TMP/results" \
RUN_KEY=probe-test \
REPLICA_EXPERIMENT=steady \
PROBE_MAX_SEQUENCE=3 \
PROBE_DURATION_SECONDS=10 \
PROBE_RECOVERY_TIMEOUT_SECONDS=2 \
  "$ROOT/k6/product-replica-probe.sh"

lag="$TMP/results/probe-test.replica-lag.tsv"
status="$TMP/results/probe-test.replica-status.tsv"
faults="$TMP/results/probe-test.replica-faults.tsv"
stale="$TMP/results/probe-test.replica-stale.tsv"
[ "$(head -n 1 "$lag")" = $'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc' ]
[ "$(head -n 1 "$status")" = $'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set' ]
[ -f "$faults" ] && [ -f "$stale" ]
awk -F '\t' 'NR > 1 { if ($5 < 0) exit 1; rows++ } END { exit rows == 0 }' "$lag"
[ "$(tail -n 1 "$lag" | cut -f2)" = 3 ]
[ "$(sed -n -E "s/.*VALUES \('[^']*',([0-9]+),.*/\1/p" "$TMP/source.log" | tr '\n' ' ')" = '1 2 3 ' ]
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
