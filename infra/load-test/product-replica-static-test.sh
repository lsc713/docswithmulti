#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
rg -q 'product-replica' "$ROOT/infra/load-test/variables.tf"
rg -q 'mysql-product-replica.*10.0.1.34' "$ROOT/infra/load-test/instances.tf"
rg -q 'contains\(\["product-scaleout", "product-replica"\]' "$ROOT/infra/load-test/product-nlb.tf"
rg -q 'contains\(\["mysql-product", "mysql-product-replica"\], each.key\)' "$ROOT/infra/load-test/instances.tf"
