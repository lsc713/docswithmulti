#!/usr/bin/env bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)

require() {
  local pattern="$1" file="$2"
  rg -q -- "$pattern" "$HERE/$file" || {
    echo "missing contract in $file: $pattern" >&2
    return 1
  }
}

require '--max-connections=500' mysql-product-replication.compose.yml
require '--innodb-buffer-pool-size=4G' mysql-product-replication.compose.yml
require '--server-id=1' mysql-product-replication.compose.yml
require '--log-bin=mysql-bin' mysql-product-replication.compose.yml
require '--binlog-format=ROW' mysql-product-replication.compose.yml
require '--gtid-mode=ON' mysql-product-replication.compose.yml
require '--enforce-gtid-consistency=ON' mysql-product-replication.compose.yml
require '01-replication-users.sql:/docker-entrypoint-initdb.d/01-replication-users.sql:ro' mysql-product-replication.compose.yml

require '--server-id=2' mysql-product-replica.compose.yml
require '--relay-log=mysql-relay-bin' mysql-product-replica.compose.yml
require '--gtid-mode=ON' mysql-product-replica.compose.yml
require '--enforce-gtid-consistency=ON' mysql-product-replica.compose.yml
if rg -q -- '--(read-only|super-read-only|log-bin|binlog-format)' "$HERE/mysql-product-replica.compose.yml"; then
  echo 'replica must initialize writable and must not enable the out-of-scope replica binlog' >&2
  exit 1
fi
if rg -q 'MYSQL_(DATABASE|USER)' "$HERE/mysql-product-replica.compose.yml"; then
  echo 'replica compose must not initialize an application database or user' >&2
  exit 1
fi

diff -u <(printf '%s\n' \
  "CREATE USER IF NOT EXISTS 'product_replicator'@'%' IDENTIFIED BY 'product_replicator';" \
  "GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'product_replicator'@'%';" \
  '' \
  "CREATE USER IF NOT EXISTS 'product_reader'@'%' IDENTIFIED BY 'product_reader';" \
  "GRANT SELECT ON product_db.* TO 'product_reader'@'%';") \
  "$HERE/mysql-product-init/01-replication-users.sql"

require 'full\|product\|product-scaleout\|product-replica' ssm-deploy.sh
require 'ORDER="mysql-product mysql-product-replica redis-product product-a product-b product-c product-d"' ssm-deploy.sh
require 'mysql-product-replica.*mysql-product-replica.compose.yml' ssm-deploy.sh
rg -Uq 'elif \[ "\$LOAD_TEST_PROFILE" = "product-replica" \] && \[ "\$1" = "mysql-product" \]; then\n    echo "-f mysql-product-replication\.compose\.yml"' "$HERE/ssm-deploy.sh"
require 'SOURCE_AUTO_POSITION=1' ssm-deploy.sh
require 'GET_SOURCE_PUBLIC_KEY=1' ssm-deploy.sh
require 'SHOW REPLICA STATUS' ssm-deploy.sh
require 'mysql-product-replica-smoke.sh' ssm-deploy.sh
require 'SET PERSIST read_only=ON; SET PERSIST super_read_only=ON' ssm-deploy.sh
if rg -q 'SET GLOBAL (read_only|super_read_only)' "$HERE/ssm-deploy.sh"; then
  echo 'replica read-only state must be persisted across restarts' >&2
  exit 1
fi
require 'SELECT @@GLOBAL.read_only, @@GLOBAL.super_read_only' ssm-deploy.sh
require '/opt/loadtest/.product-replica-source-initialized' ssm-deploy.sh
require '/opt/loadtest/.product-replica-source-fresh' ssm-deploy.sh
require 'docker volume inspect deploy_mysql-product-data' ssm-deploy.sh
require 'unproven mysql-product volume' ssm-deploy.sh
rg -Uq 'SET PERSIST read_only=ON; SET PERSIST super_read_only=ON;.[\s\S]*SELECT @@GLOBAL\.read_only, @@GLOBAL\.super_read_only.[\s\S]*SHOW REPLICA STATUS' "$HERE/ssm-deploy.sh"
rg -Uq 'if docker volume inspect deploy_mysql-product-data[^\n]*; then\n  \[ -f "\\\$source_marker" \] \|\| \[ -f "\\\$fresh_marker" \][\s\S]*else\n  rm -f "\\\$source_marker" "\\\$fresh_marker"[\s\S]*touch "\\\$fresh_marker"\nfi' "$HERE/ssm-deploy.sh"
rg -Uq 'if \[ "\$source_host" = .10\.0\.1\.33. \]; then[\s\S]*START REPLICA;[\s\S]*else[\s\S]*STOP REPLICA;[\s\S]*CHANGE REPLICATION SOURCE TO' "$HERE/ssm-deploy.sh"
rg -Uq '\./mysql-product-replica-smoke\.sh\nEOF' "$HERE/ssm-deploy.sh"
rg -Uq 'ssm_run "\$role" "\$remote"\n  if \[ "\$LOAD_TEST_PROFILE" = "product-replica" \] && \[ "\$role" = "mysql-product-replica" \]; then[\s\S]*touch /opt/loadtest/\.product-replica-source-initialized' "$HERE/ssm-deploy.sh"

require 'Replica_IO_Running.*Yes' mysql-product-replica-smoke.sh
require 'Replica_SQL_Running.*Yes' mysql-product-replica-smoke.sh
require 'replica_root_sql "\$remaining" --column-names --vertical' mysql-product-replica-smoke.sh
require 'product_reader' mysql-product-replica-smoke.sh
require 'INSERT unexpectedly succeeded' mysql-product-replica-smoke.sh
require 'reader_status' mysql-product-replica-smoke.sh
require 'ERROR \(1142\|1290\)' mysql-product-replica-smoke.sh
require 'SMOKE_TIMEOUT_SECONDS:-30' mysql-product-replica-smoke.sh
require 'timeout --foreground' mysql-product-replica-smoke.sh
if rg -q '^[[:space:]]*docker exec' "$HERE/mysql-product-replica-smoke.sh"; then
  echo 'smoke docker exec must be wrapped by a process timeout' >&2
  exit 1
fi
rg -Uq 'DROP TABLE IF EXISTS product_db\.loadtest_replication_smoke[\s\S]*wait_for .replicated smoke table cleanup.[\s\S]*information_schema\.tables[\s\S]* 0\ntrap - EXIT' "$HERE/mysql-product-replica-smoke.sh" || {
  echo 'successful replication smoke must remove its temporary table from source and replica' >&2
  exit 1
}

if docker compose version >/dev/null 2>&1; then
  base=$(docker compose -f "$HERE/mysql-product.compose.yml" config --format json)
  source=$(docker compose -f "$HERE/mysql-product.compose.yml" -f "$HERE/mysql-product-replication.compose.yml" config --format json)
  replica=$(docker compose -f "$HERE/mysql-product-replica.compose.yml" config --format json)

  jq -e '.services["mysql-product"].command == ["--max-connections=500", "--innodb-buffer-pool-size=4G"]
    and all(.services["mysql-product"].volumes[]; .target != "/docker-entrypoint-initdb.d/01-replication-users.sql")' <<<"$base" >/dev/null
  jq -e '.services["mysql-product"].command == ["--max-connections=500", "--innodb-buffer-pool-size=4G", "--server-id=1", "--log-bin=mysql-bin", "--binlog-format=ROW", "--gtid-mode=ON", "--enforce-gtid-consistency=ON"]
    and any(.services["mysql-product"].volumes[]; .target == "/docker-entrypoint-initdb.d/01-replication-users.sql" and .read_only == true)
    and .volumes["mysql-product-data"].name == "deploy_mysql-product-data"' <<<"$source" >/dev/null
  jq -e '.services["mysql-product-replica"].command == ["--server-id=2", "--relay-log=mysql-relay-bin", "--gtid-mode=ON", "--enforce-gtid-consistency=ON"]
    and ((.services["mysql-product-replica"].environment | has("MYSQL_DATABASE")) == false)
    and ((.services["mysql-product-replica"].environment | has("MYSQL_USER")) == false)
    and .services["mysql-product-replica"].healthcheck.test == ["CMD-SHELL", "MYSQL_PWD=root mysql -uroot -e '\''SELECT 1'\''"]' <<<"$replica" >/dev/null
fi

(
  fake_bin=$(mktemp -d)
  trap 'rm -rf "$fake_bin"' EXIT
  log="$fake_bin/ssm.log"

  cat >"$fake_bin/aws" <<'AWS'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-} ${2:-}" in
  'ec2 describe-instances') echo i-task4-test ;;
  'ssm describe-instance-information') echo Online ;;
  'ssm send-command')
    shift 2
    comment='' params=''
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --comment) comment="$2"; shift 2 ;;
        --parameters) params="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    printf '%s\t%s\n' "$comment" "$(jq -cn --arg value "$params" '$value')" >>"$SSM_TEST_LOG"
    if [ "${SSM_TEST_FAIL_PREFLIGHT:-0}" = 1 ]; then
      case "$params" in *"source_volume_required='1'"*) echo command-preflight-fail; exit ;; esac
    fi
    echo command-task4-test
    ;;
  'ssm list-command-invocations')
    case " $* " in
      *' --details '*) : ;;
      *'command-preflight-fail'*) echo Failed ;;
      *) echo Success ;;
    esac
    ;;
  *) echo "unexpected fake aws call: $*" >&2; exit 1 ;;
esac
AWS
  chmod +x "$fake_bin/aws"

  SSM_TEST_LOG="$log" PATH="$fake_bin:$PATH" \
    IMAGE_NS=test REPO_REF=deadbeef LOAD_TEST_PROFILE=product-replica \
    ROLES=mysql-product-replica DEPLOY_OBS=0 SSM_READY_ATTEMPTS=1 SSM_POLL_ATTEMPTS=1 \
    bash "$HERE/ssm-deploy.sh" >/dev/null

  count=$(wc -l <"$log" | tr -d ' ')
  [ "$count" = 3 ] || {
    echo "replica-only deploy must send source preflight, replica, then success marker; got $count commands" >&2
    exit 1
  }
  first=$(sed -n '1p' "$log")
  second=$(sed -n '2p' "$log")
  third=$(sed -n '3p' "$log")
  case "$first" in *'loadtest deploy mysql-product'*'source_volume_required='"'"'1'"'"''*) ;; *) echo 'replica-only source preflight is missing or not strict' >&2; exit 1 ;; esac
  case "$second" in *'loadtest deploy mysql-product-replica'*'./mysql-product-replica-smoke.sh'*) ;; *) echo 'replica command must follow source preflight and run smoke' >&2; exit 1 ;; esac
  case "$third" in *'loadtest deploy mysql-product'*'.product-replica-source-initialized'*) ;; *) echo 'success marker command must be last' >&2; exit 1 ;; esac

  : >"$log"
  if SSM_TEST_LOG="$log" SSM_TEST_FAIL_PREFLIGHT=1 PATH="$fake_bin:$PATH" \
    IMAGE_NS=test REPO_REF=deadbeef LOAD_TEST_PROFILE=product-replica \
    ROLES=mysql-product-replica DEPLOY_OBS=0 SSM_READY_ATTEMPTS=1 SSM_POLL_ATTEMPTS=1 \
    bash "$HERE/ssm-deploy.sh" >/dev/null 2>&1; then
    echo 'replica-only deploy must stop when source preflight fails' >&2
    exit 1
  fi
  [ "$(wc -l <"$log" | tr -d ' ')" = 1 ] || {
    echo 'failed source preflight must not send replica or success-marker commands' >&2
    exit 1
  }
)
