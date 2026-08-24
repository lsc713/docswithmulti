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
require '01-replication-users.sql:/docker-entrypoint-initdb.d/01-replication-users.sql:ro' mysql-product-replication.compose.yml

require '--server-id=2' mysql-product-replica.compose.yml
require '--relay-log=mysql-relay-bin' mysql-product-replica.compose.yml
require '--gtid-mode=ON' mysql-product-replica.compose.yml
require '--read-only=ON' mysql-product-replica.compose.yml
require '--super-read-only=ON' mysql-product-replica.compose.yml
if rg -q 'MYSQL_(DATABASE|USER)' "$HERE/mysql-product-replica.compose.yml"; then
  echo 'replica compose must not initialize an application database or user' >&2
  exit 1
fi

require 'REPLICATION SLAVE, REPLICATION CLIENT' mysql-product-init/01-replication-users.sql
require 'GRANT SELECT ON product_db\.\* TO .product_reader.' mysql-product-init/01-replication-users.sql

require 'full\|product\|product-scaleout\|product-replica' ssm-deploy.sh
require 'ORDER="mysql-product mysql-product-replica redis-product product-a product-b product-c product-d"' ssm-deploy.sh
require 'mysql-product-replica.*mysql-product-replica.compose.yml' ssm-deploy.sh
require 'mysql-product-replication.compose.yml' ssm-deploy.sh
require 'SOURCE_AUTO_POSITION=1' ssm-deploy.sh
require 'SHOW REPLICA STATUS' ssm-deploy.sh
require 'mysql-product-replica-smoke.sh' ssm-deploy.sh

require 'Replica_IO_Running.*Yes' mysql-product-replica-smoke.sh
require 'Replica_SQL_Running.*Yes' mysql-product-replica-smoke.sh
require 'product_reader' mysql-product-replica-smoke.sh
require 'INSERT unexpectedly succeeded' mysql-product-replica-smoke.sh
require 'SMOKE_TIMEOUT_SECONDS:-30' mysql-product-replica-smoke.sh
