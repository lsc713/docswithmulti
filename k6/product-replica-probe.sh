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
SQL_TIMEOUT_SECONDS="${SQL_TIMEOUT_SECONDS:-10}"

[[ "$RUN_KEY" =~ ^[A-Za-z0-9._-]+$ ]] || { echo 'RUN_KEY contains unsupported characters' >&2; exit 1; }
[ "${#RUN_KEY}" -le 64 ] || { echo 'RUN_KEY must be at most 64 characters' >&2; exit 1; }
case "$MODE" in steady|lag|outage) ;; *) echo 'REPLICA_EXPERIMENT must be steady, lag, or outage' >&2; exit 1 ;; esac
for value in "$PROBE_DURATION_SECONDS" "$PROBE_MAX_SEQUENCE" "$PROBE_RECOVERY_TIMEOUT_SECONDS" "$SQL_TIMEOUT_SECONDS"; do
  [[ "$value" =~ ^[0-9]+$ ]] || { echo 'probe durations and sequence limit must be non-negative integers' >&2; exit 1; }
done
[ "$PROBE_DURATION_SECONDS" -gt 0 ] && [ "$PROBE_RECOVERY_TIMEOUT_SECONDS" -gt 0 ] && [ "$SQL_TIMEOUT_SECONDS" -gt 0 ] || {
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

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

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
    "SELECT COALESCE(sequence,0) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'"
}

replica_marker() {
  replica_mysql --skip-column-names -e \
    "SELECT COALESCE(sequence,0) FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'"
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

source_mysql -e '
  CREATE TABLE IF NOT EXISTS product_db.loadtest_replication_heartbeat (
    run_key VARCHAR(64) PRIMARY KEY,
    sequence BIGINT NOT NULL,
    sent_at TIMESTAMP(6) NOT NULL
  ) ENGINE=InnoDB
'
source_mysql -e "DELETE FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'"

collector_pid=
reservation_active=0
reservation_key="replica-probe-$RUN_KEY"

release_reservation() {
  [ "$reservation_active" = 1 ] || return 0
  curl --silent --show-error --connect-timeout 3 --max-time 10 -o /dev/null \
    -H 'Content-Type: application/json' \
    -d "{\"paymentKey\":\"$reservation_key\",\"items\":[{\"skuId\":$PROBE_SKU_ID,\"qty\":100}]}" \
    "$PRODUCT_URL/v1/stock/release" || true
  reservation_active=0
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [ -n "$collector_pid" ]; then kill "$collector_pid" >/dev/null 2>&1 || true; wait "$collector_pid" >/dev/null 2>&1 || true; fi
  release_reservation
  if [ "$MODE" = outage ]; then timeout --foreground 30s docker start mysql-product-replica >/dev/null 2>&1 || true; fi
  wait_for_threads >/dev/null 2>&1 || true
  source_mysql -e "DELETE FROM product_db.loadtest_replication_heartbeat WHERE run_key = '$RUN_KEY'" >/dev/null 2>&1 || true
  rm -f "$SENT_FILE" "$HTTP_BODY"
  rmdir "$STATE_DIR" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT INT TERM

collect_markers() {
  local started now next_write next_status sequence=0 observed=0 last_observed=0 sent lag final_source final_replica
  started=$(monotonic_ms)
  next_write=$started
  next_status=$started
  while :; do
    now=$(monotonic_ms)
    if [ "$now" -ge "$next_write" ]; then
      sequence=$((sequence + 1))
      source_mysql -e "INSERT INTO product_db.loadtest_replication_heartbeat(run_key,sequence,sent_at) VALUES ('$RUN_KEY',$sequence,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE sequence=VALUES(sequence),sent_at=VALUES(sent_at)"
      printf '%s\t%s\n' "$sequence" "$now" >> "$SENT_FILE"
      next_write=$((now + 1000))
    fi
    if [ "$now" -ge "$next_status" ]; then
      read_status >> "$STATUS_FILE"
      next_status=$((now + 1000))
    fi
    observed=$(replica_marker 2>/dev/null || printf '0\n')
    if [[ "$observed" =~ ^[0-9]+$ ]] && [ "$observed" -gt "$last_observed" ] && [ "$observed" -le "$sequence" ]; then
      sent=$(awk -F '\t' -v sequence="$observed" '$1 == sequence {print $2; exit}' "$SENT_FILE")
      if [ -n "$sent" ]; then
        lag=$((now - sent)); [ "$lag" -ge 0 ] || lag=0
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$RUN_KEY" "$observed" "$sent" "$now" "$lag" "$(utc_now)" >> "$LAG_FILE"
        last_observed=$observed
      fi
    fi
    if [ "$PROBE_MAX_SEQUENCE" -gt 0 ]; then
      [ "$sequence" -ge "$PROBE_MAX_SEQUENCE" ] && [ "$last_observed" -ge "$sequence" ] && break
    elif [ $((now - started)) -ge $((PROBE_DURATION_SECONDS * 1000)) ]; then
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

pause_sql() {
  local seconds=$1 prove_stale=$2 started_ms started_utc ended_utc status target previous source_qty_value replica_qty_value
  local replica_visible_qty= convergence_qty= final_restored_qty=
  started_ms=$(monotonic_ms)
  started_utc=$(utc_now)
  replica_mysql -e 'STOP REPLICA SQL_THREAD'
  if [ "$prove_stale" = 1 ]; then
    status=$(http_post_status /v1/stock/reserve \
      "{\"paymentKey\":\"$reservation_key\",\"items\":[{\"productId\":$PROBE_PRODUCT_ID,\"skuId\":$PROBE_SKU_ID,\"qty\":100}]}")
    [ "$status" = 200 ] || { echo "probe reserve returned HTTP $status" >&2; return 1; }
    reservation_active=1
    sleep_for 6
    replica_visible_qty=$(displayed_qty)
    [ "$replica_visible_qty" = 100 ] || { echo "expected stale quantity 100, got $replica_visible_qty" >&2; return 1; }
    status=$(http_post_status /v1/stock/reserve \
      "{\"paymentKey\":\"replica-probe-reject-$RUN_KEY\",\"items\":[{\"productId\":$PROBE_PRODUCT_ID,\"skuId\":$PROBE_SKU_ID,\"qty\":1}]}")
    [ "$status" = 409 ] || { echo "expected primary HTTP 409, got $status" >&2; return 1; }
  fi
  while [ $(( $(monotonic_ms) - started_ms )) -lt $((seconds * 1000)) ]; do sleep_for 0.1; done
  replica_mysql -e 'START REPLICA SQL_THREAD'
  wait_for_threads
  ended_utc=$(utc_now)
  printf '%s\tsql_thread\t%s\t%s\t%s\n' "$MODE" "$seconds" "$started_utc" "$ended_utc" >> "$FAULTS_FILE"

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
    reservation_active=0
    target=$(wait_for_next_source_marker "$previous")
    wait_for_marker "$target"
    wait_for_stock source 100
    wait_for_stock replica 100
    source_qty_value=$(stock_qty source)
    replica_qty_value=$(stock_qty replica)
    [ "$source_qty_value" = 100 ] && [ "$replica_qty_value" = 100 ] || return 1
    sleep_for 6
    final_restored_qty=$(displayed_qty)
    [ "$final_restored_qty" = 100 ] || { echo "expected restored quantity 100, got $final_restored_qty" >&2; return 1; }
    printf '30\t%s\t409\t%s\t%s\n' "$replica_visible_qty" "$convergence_qty" "$final_restored_qty" >> "$STALE_FILE"
  fi
}

collect_markers &
collector_pid=$!

case "$MODE" in
  steady) ;;
  lag)
    sleep_for 60
    pause_sql 5 0
    sleep_for 60
    pause_sql 30 1
    sleep_for 90
    pause_sql 60 0
    sleep_for 120
    ;;
  outage)
    sleep_for 60
    outage_started=$(utc_now)
    timeout --foreground 30s docker stop mysql-product-replica >/dev/null
    sleep_for 60
    timeout --foreground 30s docker start mysql-product-replica >/dev/null
    wait_for_threads
    printf '%s\tcontainer\t60\t%s\t%s\n' "$MODE" "$outage_started" "$(utc_now)" >> "$FAULTS_FILE"
    ;;
esac

wait "$collector_pid"
collector_pid=
