#!/usr/bin/env bash
set -euo pipefail

SOURCE_HOST="${SOURCE_HOST:-10.0.1.33}"
SMOKE_TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-30}"
case "$SMOKE_TIMEOUT_SECONDS" in
  ''|*[!0-9]*|0) echo 'SMOKE_TIMEOUT_SECONDS must be a positive integer' >&2; exit 1 ;;
esac

run_key="${SMOKE_RUN_KEY:-$(date +%s)-$$}"
case "$run_key" in
  ''|*[!A-Za-z0-9._:-]*) echo 'SMOKE_RUN_KEY contains unsupported characters' >&2; exit 1 ;;
esac
[ "${#run_key}" -le 64 ] || { echo 'SMOKE_RUN_KEY must be at most 64 characters' >&2; exit 1; }

replica_root_sql() {
  docker exec -e MYSQL_PWD=root mysql-product-replica \
    mysql --batch --skip-column-names -uroot "$@"
}

source_root_sql() {
  replica_root_sql --connect-timeout=5 -h "$SOURCE_HOST" "$@"
}

reader_sql() {
  docker exec -e MYSQL_PWD=product_reader mysql-product-replica \
    mysql --batch --skip-column-names --connect-timeout=5 \
      -h 127.0.0.1 -uproduct_reader product_db "$@"
}

wait_for() {
  local description="$1" query="$2" expected="$3" deadline=$((SECONDS + SMOKE_TIMEOUT_SECONDS)) value
  while [ "$SECONDS" -lt "$deadline" ]; do
    value=$(replica_root_sql -e "$query" 2>/dev/null || true)
    [ "$value" = "$expected" ] && return 0
    sleep 1
  done
  echo "timed out waiting for $description" >&2
  return 1
}

deadline=$((SECONDS + SMOKE_TIMEOUT_SECONDS))
while [ "$SECONDS" -lt "$deadline" ]; do
  status=$(replica_root_sql -e 'SHOW REPLICA STATUS\G')
  if printf '%s\n' "$status" | grep -q 'Replica_IO_Running: Yes' && \
     printf '%s\n' "$status" | grep -q 'Replica_SQL_Running: Yes'; then
    break
  fi
  sleep 1
done
printf '%s\n' "$status" | grep -q 'Replica_IO_Running: Yes' || { echo 'Replica_IO_Running is not Yes' >&2; exit 1; }
printf '%s\n' "$status" | grep -q 'Replica_SQL_Running: Yes' || { echo 'Replica_SQL_Running is not Yes' >&2; exit 1; }
[ "$(replica_root_sql -e 'SELECT @@GLOBAL.read_only, @@GLOBAL.super_read_only')" = $'1\t1' ] || {
  echo 'replica read_only and super_read_only must both be ON' >&2
  exit 1
}

source_root_sql -e '
  CREATE TABLE IF NOT EXISTS product_db.loadtest_replication_smoke (
    run_key VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
  ) ENGINE=InnoDB;
'
source_root_sql -e "INSERT INTO product_db.loadtest_replication_smoke(run_key) VALUES ('$run_key')"
cleanup() {
  source_root_sql -e "DELETE FROM product_db.loadtest_replication_smoke WHERE run_key = '$run_key'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for 'marker replication' \
  "SELECT COUNT(*) FROM product_db.loadtest_replication_smoke WHERE run_key = '$run_key'" 1

[ "$(reader_sql -e "SELECT COUNT(*) FROM loadtest_replication_smoke WHERE run_key = '$run_key'")" = 1 ] || {
  echo 'product_reader SELECT failed' >&2
  exit 1
}

reader_write_key="write-${run_key:0:58}"
if reader_sql -e "INSERT INTO loadtest_replication_smoke(run_key) VALUES ('$reader_write_key')" >/dev/null 2>&1; then
  replica_root_sql -e "DELETE FROM product_db.loadtest_replication_smoke WHERE run_key = '$reader_write_key'" >/dev/null 2>&1 || true
  echo 'product_reader INSERT unexpectedly succeeded' >&2
  exit 1
fi

source_root_sql -e "DELETE FROM product_db.loadtest_replication_smoke WHERE run_key = '$run_key'"
wait_for 'replicated marker cleanup' \
  "SELECT COUNT(*) FROM product_db.loadtest_replication_smoke WHERE run_key = '$run_key'" 0
trap - EXIT

echo "replication smoke passed: $run_key"
