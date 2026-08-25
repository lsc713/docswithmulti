#!/usr/bin/env bash
set -euo pipefail

RUN_KEY="${RUN_KEY:?RUN_KEY required}"
MODE="${REPLICA_EXPERIMENT:-steady}"
RESULT_DIR="${RESULT_DIR:-/opt/loadtest/results}"
SOURCE_HOST="${SOURCE_HOST:-10.0.1.33}"
PRODUCT_URL="${PRODUCT_URL:-}"
PRODUCT_URL="${PRODUCT_URL%/}"
PROBE_PRODUCT_ID="${PROBE_PRODUCT_ID:-}"
PROBE_SKU_ID="${PROBE_SKU_ID:-}"
PROBE_DURATION_SECONDS="${PROBE_DURATION_SECONDS:-720}"
PROBE_MAX_SEQUENCE="${PROBE_MAX_SEQUENCE:-0}"
PROBE_RECOVERY_TIMEOUT_SECONDS="${PROBE_RECOVERY_TIMEOUT_SECONDS:-120}"
PROBE_START_TIMEOUT_SECONDS="${PROBE_START_TIMEOUT_SECONDS:-300}"
SQL_TIMEOUT_SECONDS="${SQL_TIMEOUT_SECONDS:-10}"

[[ "$RUN_KEY" =~ ^[A-Za-z0-9._-]+$ ]] || { echo 'RUN_KEY contains unsupported characters' >&2; exit 1; }
[ "${#RUN_KEY}" -le 64 ] || { echo 'RUN_KEY must be at most 64 characters' >&2; exit 1; }
case "$MODE" in steady|lag|outage) ;; *) echo 'REPLICA_EXPERIMENT must be steady, lag, or outage' >&2; exit 1 ;; esac
for value in "$PROBE_DURATION_SECONDS" "$PROBE_MAX_SEQUENCE" "$PROBE_RECOVERY_TIMEOUT_SECONDS" "$PROBE_START_TIMEOUT_SECONDS" "$SQL_TIMEOUT_SECONDS"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo 'probe durations and sequence limit must be non-negative integers' >&2; exit 1; }
done
[ "$PROBE_DURATION_SECONDS" -gt 0 ] && [ "$PROBE_RECOVERY_TIMEOUT_SECONDS" -gt 0 ] && [ "$PROBE_START_TIMEOUT_SECONDS" -gt 0 ] && [ "$SQL_TIMEOUT_SECONDS" -gt 0 ] || {
  echo 'probe duration and timeouts must be positive' >&2
  exit 1
}
if [ "$MODE" = lag ]; then
  [[ "$PROBE_PRODUCT_ID" =~ ^[1-9][0-9]*$ && "$PROBE_SKU_ID" =~ ^[1-9][0-9]*$ ]] || {
    echo 'lag requires positive PROBE_PRODUCT_ID and PROBE_SKU_ID' >&2
    exit 1
  }
  [ -n "$PRODUCT_URL" ] || { echo 'lag requires PRODUCT_URL' >&2; exit 1; }
fi

source_mysql() {
  if [ -n "${SOURCE_MYSQL:-}" ]; then
    timeout --foreground "${SQL_TIMEOUT_SECONDS}s" "$SOURCE_MYSQL" "$@"
  else
    timeout --foreground "${SQL_TIMEOUT_SECONDS}s" docker run --rm --network host -e MYSQL_PWD=root mysql:8.0 \
      mysql --batch --raw -uroot --connect-timeout=5 -h "$SOURCE_HOST" "$@"
  fi
}

replica_mysql() {
  if [ -n "${REPLICA_MYSQL:-}" ]; then
    timeout --foreground "${SQL_TIMEOUT_SECONDS}s" "$REPLICA_MYSQL" "$@"
  else
    timeout --foreground "${SQL_TIMEOUT_SECONDS}s" docker exec -e MYSQL_PWD=root mysql-product-replica \
      mysql --batch --raw -uroot --connect-timeout=5 "$@"
  fi
}

monotonic_ms() {
  if [ -n "${MONOTONIC_MS:-}" ]; then "$MONOTONIC_MS"; else awk '{printf "%d\n", $1 * 1000}' /proc/uptime; fi
}

sleep_for() {
  if [ -n "${SLEEP:-}" ]; then "$SLEEP" "$1"; else sleep "$1"; fi
}

wait_until() {
  local deadline=$1
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do sleep_for 0.1; done
}

utc_now() {
  if [ -n "${UTC_NOW:-}" ]; then "$UTC_NOW"; else date -u +%Y-%m-%dT%H:%M:%S.%6NZ; fi
}

mkdir -p "$RESULT_DIR"
LAG_FILE="$RESULT_DIR/$RUN_KEY.replica-lag.tsv"
STATUS_FILE="$RESULT_DIR/$RUN_KEY.replica-status.tsv"
FAULTS_FILE="$RESULT_DIR/$RUN_KEY.replica-faults.tsv"
STALE_FILE="$RESULT_DIR/$RUN_KEY.replica-stale.tsv"
STATE_DIR="$RESULT_DIR/.$RUN_KEY.replica-probe"
mkdir -p "$STATE_DIR"
SENT_FILE="$STATE_DIR/sent.tsv"
HTTP_BODY="$STATE_DIR/http-body.json"
printf 'run_key\tsequence\tsent_monotonic_ms\tobserved_monotonic_ms\tlag_ms\tobserved_utc\n' > "$LAG_FILE"
printf 'observed_utc\treplica_io_running\treplica_sql_running\tseconds_behind_source\tretrieved_gtid_set\texecuted_gtid_set\n' > "$STATUS_FILE"
printf 'mode\tfault\tduration_seconds\tstarted_utc\tended_utc\n' > "$FAULTS_FILE"
printf 'pause_seconds\treplica_visible_qty\tprimary_reserve_http_status\tconvergence_qty\tfinal_restored_qty\n' > "$STALE_FILE"
: > "$SENT_FILE"

source_marker() {
  source_mysql --skip-column-names -e \
    "SELECT COALESCE(MAX(sequence),0) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'"
}

replica_marker() {
  replica_mysql --skip-column-names -e \
    "SELECT COALESCE(MAX(sequence),0) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'"
}

replica_next_marker() {
  local last=$1
  replica_mysql --skip-column-names -e \
    "SELECT COALESCE(MIN(sequence),0) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY' AND sequence > $last"
}

read_status() {
  local raw io sql behind retrieved executed
  raw=$(replica_mysql --vertical -e 'SHOW REPLICA STATUS' 2>/dev/null || true)
  io=$(printf '%s\n' "$raw" | awk -F ': ' '$1 ~ /Replica_IO_Running$/ {print $2; exit}')
  sql=$(printf '%s\n' "$raw" | awk -F ': ' '$1 ~ /Replica_SQL_Running$/ {print $2; exit}')
  behind=$(printf '%s\n' "$raw" | awk -F ': ' '$1 ~ /Seconds_Behind_Source$/ {print $2; exit}')
  retrieved=$(printf '%s\n' "$raw" | awk -F ': ' '$1 ~ /Retrieved_Gtid_Set$/ {print $2; exit}')
  executed=$(printf '%s\n' "$raw" | awk -F ': ' '$1 ~ /Executed_Gtid_Set$/ {print $2; exit}')
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$(utc_now)" "${io:-No}" "${sql:-No}" "${behind:-}" "$retrieved" "$executed"
}

threads_running() {
  local status
  status=$(read_status)
  [ "$(printf '%s\n' "$status" | cut -f2-3)" = $'Yes\tYes' ]
}

wait_for_threads() {
  local deadline=$(( $(monotonic_ms) + PROBE_RECOVERY_TIMEOUT_SECONDS * 1000 ))
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    replica_mysql -e 'START REPLICA SQL_THREAD' >/dev/null 2>&1 || true
    threads_running && return 0
    sleep_for 1
  done
  echo 'timed out waiting for replica threads' >&2
  return 1
}

wait_for_marker() {
  local target=$1 deadline=$(( $(monotonic_ms) + PROBE_RECOVERY_TIMEOUT_SECONDS * 1000 )) observed
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    observed=$(replica_marker 2>/dev/null || printf '0\n')
    [ "$observed" -ge "$target" ] 2>/dev/null && return 0
    sleep_for 0.1
  done
  echo "timed out waiting for replica marker $target" >&2
  return 1
}

wait_for_heartbeat_schema() {
  local expected=$1 deadline=$(( $(monotonic_ms) + PROBE_RECOVERY_TIMEOUT_SECONDS * 1000 )) primary_key
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    primary_key=$(replica_mysql --skip-column-names -e \
      "SELECT COALESCE(GROUP_CONCAT(column_name ORDER BY seq_in_index),'') FROM information_schema.statistics WHERE table_schema = 'product_db' AND table_name = 'loadtest_replication_heartbeat' AND index_name = 'PRIMARY'" 2>/dev/null || true)
    [ "$primary_key" = "$expected" ] && return 0
    sleep_for 0.1
  done
  echo 'timed out waiting for heartbeat schema on replica' >&2
  return 1
}

source_mysql -e 'DROP TABLE IF EXISTS product_db.loadtest_replication_heartbeat'
wait_for_heartbeat_schema ''
source_mysql -e '
  CREATE TABLE product_db.loadtest_replication_heartbeat (
    run_key VARCHAR(64) NOT NULL,
    sequence BIGINT NOT NULL,
    sent_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_key, sequence)
  ) ENGINE=InnoDB
'
wait_for_heartbeat_schema run_key,sequence

collector_pid=
reservation_key="replica-probe-$RUN_KEY"
reject_key="replica-probe-reject-$RUN_KEY"
reservation_cleanup=0
reject_cleanup=0

release_reservations() {
  local attempt value
  [ "$reservation_cleanup" = 1 ] || [ "$reject_cleanup" = 1 ] || return 0
  for attempt in 1 2 3; do
    if [ "$reservation_cleanup" = 1 ]; then
      curl --silent --show-error --connect-timeout 3 --max-time 10 -o /dev/null \
        -H 'Content-Type: application/json' \
        -d "{\"paymentKey\":\"$reservation_key\",\"items\":[{\"skuId\":$PROBE_SKU_ID,\"qty\":100}]}" \
        "$PRODUCT_URL/v1/stock/release" >/dev/null 2>&1 || true
    fi
    if [ "$reject_cleanup" = 1 ]; then
      curl --silent --show-error --connect-timeout 3 --max-time 10 -o /dev/null \
        -H 'Content-Type: application/json' \
        -d "{\"paymentKey\":\"$reject_key\",\"items\":[{\"skuId\":$PROBE_SKU_ID,\"qty\":1}]}" \
        "$PRODUCT_URL/v1/stock/release" >/dev/null 2>&1 || true
    fi
    value=$(stock_qty source 2>/dev/null || true)
    if [ "$value" = 100 ]; then
      reservation_cleanup=0
      reject_cleanup=0
      return 0
    fi
    [ "$attempt" -eq 3 ] || sleep_for 1
  done
  echo 'failed to restore probe source stock to 100' >&2
  return 1
}

cleanup() {
  local status=$? cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if [ -n "$collector_pid" ]; then kill "$collector_pid" >/dev/null 2>&1 || true; wait "$collector_pid" >/dev/null 2>&1 || true; fi
  if [ "$MODE" = outage ]; then timeout --foreground 30s docker start mysql-product-replica >/dev/null 2>&1 || cleanup_status=1; fi
  wait_for_threads >/dev/null 2>&1 || cleanup_status=1
  release_reservations || cleanup_status=1
  source_mysql -e "DELETE FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'" >/dev/null 2>&1 || true
  rm -f "$SENT_FILE" "$HTTP_BODY"
  rmdir "$STATE_DIR" >/dev/null 2>&1 || true
  [ "$cleanup_status" = 0 ] || status=1
  exit "$status"
}
trap cleanup EXIT INT TERM

collect_markers() {
  local started now observed_at next_write next_status sequence=0 observed=0 last_observed=0 sent lag final_source final_replica
  started=$(monotonic_ms)
  next_write=$started
  next_status=$started
  while :; do
    now=$(monotonic_ms)
    if [ "$now" -ge "$next_write" ] && { [ "$PROBE_MAX_SEQUENCE" -eq 0 ] || [ "$sequence" -lt "$PROBE_MAX_SEQUENCE" ]; }; then
      sequence=$((sequence + 1))
      source_mysql -e "INSERT INTO product_db.loadtest_replication_heartbeat(run_key,sequence,sent_at) VALUES ('$RUN_KEY',$sequence,UTC_TIMESTAMP(6))"
      printf '%s\t%s\n' "$sequence" "$now" >> "$SENT_FILE"
      next_write=$((now + 1000))
    fi
    if [ "$now" -ge "$next_status" ]; then
      read_status >> "$STATUS_FILE"
      next_status=$((now + 1000))
    fi
    observed=$(replica_next_marker "$last_observed" 2>/dev/null || printf '0\n')
    observed_at=$(monotonic_ms)
    if [[ "$observed" =~ ^[0-9]+$ ]] && [ "$observed" -gt "$last_observed" ] && [ "$observed" -le "$sequence" ]; then
      sent=$(awk -F '\t' -v sequence="$observed" '$1 == sequence {print $2; exit}' "$SENT_FILE")
      if [ -n "$sent" ]; then
        lag=$((observed_at - sent)); [ "$lag" -ge 0 ] || lag=0
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$RUN_KEY" "$observed" "$sent" "$observed_at" "$lag" "$(utc_now)" >> "$LAG_FILE"
        last_observed=$observed
      fi
    fi
    if [ "$PROBE_MAX_SEQUENCE" -gt 0 ]; then
      [ "$sequence" -ge "$PROBE_MAX_SEQUENCE" ] && [ "$last_observed" -ge "$sequence" ] && break
    elif [ $((observed_at - started)) -ge $((PROBE_DURATION_SECONDS * 1000)) ]; then
      break
    fi
    sleep_for 0.1
  done

  final_source=$(source_marker)
  wait_for_marker "$final_source"
  final_replica=$(replica_marker)
  [ "$final_replica" = "$final_source" ] || { echo "final marker mismatch: source=$final_source replica=$final_replica" >&2; return 1; }
  read_status >> "$STATUS_FILE"
  threads_running || { echo 'replica threads did not recover' >&2; return 1; }
  printf '%s\tsource_final\t%s\t%s\t%s\n' "$MODE" "$final_source" "$(utc_now)" "$(utc_now)" >> "$FAULTS_FILE"
}

http_post_status() {
  local path=$1 body=$2
  curl --silent --show-error --connect-timeout 3 --max-time 10 -o "$HTTP_BODY" -w '%{http_code}' \
    -H 'Content-Type: application/json' -d "$body" "$PRODUCT_URL$path"
}

displayed_qty() {
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$PRODUCT_URL/v1/products/$PROBE_PRODUCT_ID" | \
    jq -er --argjson sku "$PROBE_SKU_ID" '.skus[] | select(.skuId == $sku) | .availableQty'
}

stock_qty() {
  local side=$1 query="SELECT available_qty FROM product_db.product_stock WHERE sku_id = $PROBE_SKU_ID"
  if [ "$side" = source ]; then source_mysql --skip-column-names -e "$query"; else replica_mysql --skip-column-names -e "$query"; fi
}

wait_for_stock() {
  local side=$1 expected=$2 deadline=$(( $(monotonic_ms) + PROBE_RECOVERY_TIMEOUT_SECONDS * 1000 )) value
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    value=$(stock_qty "$side" 2>/dev/null || true)
    [ "$value" = "$expected" ] && return 0
    sleep_for 0.1
  done
  echo "timed out waiting for $side stock $expected" >&2
  return 1
}

wait_for_next_source_marker() {
  local previous=$1 deadline=$(( $(monotonic_ms) + PROBE_RECOVERY_TIMEOUT_SECONDS * 1000 )) current
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    current=$(source_marker)
    if [ "$current" -gt "$previous" ]; then printf '%s\n' "$current"; return 0; fi
    sleep_for 0.1
  done
  echo 'timed out waiting for next source marker' >&2
  return 1
}

wait_for_workload_start() {
  local deadline=$(( $(monotonic_ms) + PROBE_START_TIMEOUT_SECONDS * 1000 )) ready
  while [ "$(monotonic_ms)" -lt "$deadline" ]; do
    ready=$(source_mysql --skip-column-names -e \
      "SELECT COUNT(*) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY' AND sequence = 0" 2>/dev/null || true)
    if [ "$ready" = 1 ]; then monotonic_ms; return 0; fi
    sleep_for 0.1
  done
  echo 'timed out waiting for workload-start marker' >&2
  return 1
}

pause_sql() {
  local seconds=$1 prove_stale=$2 started_ms started_utc ended_utc status target previous source_qty_value replica_qty_value
  local replica_visible_qty= convergence_qty= final_restored_qty=
  replica_mysql -e 'STOP REPLICA SQL_THREAD'
  started_ms=$(monotonic_ms)
  started_utc=$(utc_now)
  if [ "$prove_stale" = 1 ]; then
    reservation_cleanup=1
    reject_cleanup=1
    status=$(http_post_status /v1/stock/reserve \
      "{\"paymentKey\":\"$reservation_key\",\"items\":[{\"productId\":$PROBE_PRODUCT_ID,\"skuId\":$PROBE_SKU_ID,\"qty\":100}]}")
    [ "$status" = 200 ] || { echo "probe reserve returned HTTP $status" >&2; return 1; }
    sleep_for 6
    replica_visible_qty=$(displayed_qty)
    [ "$replica_visible_qty" = 100 ] || { echo "expected stale quantity 100, got $replica_visible_qty" >&2; return 1; }
    status=$(http_post_status /v1/stock/reserve \
      "{\"paymentKey\":\"$reject_key\",\"items\":[{\"productId\":$PROBE_PRODUCT_ID,\"skuId\":$PROBE_SKU_ID,\"qty\":1}]}")
    [ "$status" = 409 ] || { echo "expected primary HTTP 409, got $status" >&2; return 1; }
  fi
  wait_until "$((started_ms + seconds * 1000))"
  ended_utc=$(utc_now)
  printf '%s\tsql_thread\t%s\t%s\t%s\n' "$MODE" "$seconds" "$started_utc" "$ended_utc" >> "$FAULTS_FILE"
  replica_mysql -e 'START REPLICA SQL_THREAD'
  wait_for_threads
  PAUSE_RECOVERY_STARTED_MS=$(monotonic_ms)

  if [ "$prove_stale" = 1 ]; then
    target=$(source_marker)
    wait_for_marker "$target"
    wait_for_stock source 0
    wait_for_stock replica 0
    sleep_for 6
    convergence_qty=$(displayed_qty)
    [ "$convergence_qty" = 0 ] || { echo "expected converged quantity 0, got $convergence_qty" >&2; return 1; }
    previous=$target
    status=$(http_post_status /v1/stock/release \
      "{\"paymentKey\":\"$reservation_key\",\"items\":[{\"skuId\":$PROBE_SKU_ID,\"qty\":100}]}")
    [ "$status" = 200 ] || { echo "probe release returned HTTP $status" >&2; return 1; }
    target=$(wait_for_next_source_marker "$previous")
    wait_for_marker "$target"
    wait_for_stock source 100
    wait_for_stock replica 100
    source_qty_value=$(stock_qty source)
    replica_qty_value=$(stock_qty replica)
    [ "$source_qty_value" = 100 ] && [ "$replica_qty_value" = 100 ] || return 1
    reservation_cleanup=0
    sleep_for 6
    final_restored_qty=$(displayed_qty)
    [ "$final_restored_qty" = 100 ] || { echo "expected restored quantity 100, got $final_restored_qty" >&2; return 1; }
    printf '30\t%s\t409\t%s\t%s\n' "$replica_visible_qty" "$convergence_qty" "$final_restored_qty" >> "$STALE_FILE"
  fi
}

source_mysql -e "INSERT INTO product_db.loadtest_replication_heartbeat(run_key,sequence,sent_at) VALUES ('$RUN_KEY',-1,UTC_TIMESTAMP(6))"
workload_started_ms=$(wait_for_workload_start)
collect_markers &
collector_pid=$!

case "$MODE" in
  steady) ;;
  lag)
    wait_until "$((workload_started_ms + 60000))"
    pause_sql 5 0
    wait_until "$((PAUSE_RECOVERY_STARTED_MS + 60000))"
    pause_sql 30 1
    wait_until "$((PAUSE_RECOVERY_STARTED_MS + 90000))"
    pause_sql 60 0
    wait_until "$((PAUSE_RECOVERY_STARTED_MS + 120000))"
    ;;
  outage)
    wait_until "$((workload_started_ms + 60000))"
    timeout --foreground 30s docker stop mysql-product-replica >/dev/null
    outage_started_ms=$(monotonic_ms)
    outage_started=$(utc_now)
    wait_until "$((outage_started_ms + 60000))"
    outage_ended=$(utc_now)
    timeout --foreground 30s docker start mysql-product-replica >/dev/null
    wait_for_threads
    printf '%s\tcontainer\t60\t%s\t%s\n' "$MODE" "$outage_started" "$outage_ended" >> "$FAULTS_FILE"
    ;;
esac

wait "$collector_pid"
collector_pid=
