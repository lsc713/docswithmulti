#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
require() { rg -q -- "$2" "$1" || { echo "missing $2 in $1" >&2; exit 1; }; }

require "$ROOT/infra/load-test/variables.tf" 'variable "load_test_profile"'
require "$ROOT/infra/load-test/instances.tf" 'var.load_test_profile == "product"'
require "$ROOT/infra/load-test/network.tf" 'source_dest_check *= *false'
require "$ROOT/infra/load-test/network.tf" 'network_interface_id'
require "$ROOT/infra/load-test/outputs.tf" 'output "egress_mode"'

# Product-only topologies have no payment service, so only orphan recovery is disabled.
# Full keeps the application default enabled, while cancel-restore remains untouched.
require "$ROOT/product-service/src/main/resources/application.yml" 'PRODUCT_ORPHAN_RECOVERY_ENABLED:true'
require "$ROOT/infra/load-test/deploy/product-readonly.compose.yml" 'PRODUCT_ORPHAN_RECOVERY_ENABLED: "false"'
require "$ROOT/infra/load-test/deploy/product-scaleout.compose.yml" 'PRODUCT_ORPHAN_RECOVERY_ENABLED: "false"'
require "$ROOT/infra/load-test/deploy/ssm-deploy.sh" 'echo "-f product-readonly.compose.yml"'
require "$ROOT/infra/load-test/deploy/ssm-deploy.sh" 'echo "-f product-scaleout.compose.yml"'

if docker compose version >/dev/null 2>&1; then
  full=$(IMAGE_NS=test docker compose \
    -f "$ROOT/infra/load-test/deploy/product.compose.yml" config --format json)
  product=$(IMAGE_NS=test docker compose \
    -f "$ROOT/infra/load-test/deploy/product.compose.yml" \
    -f "$ROOT/infra/load-test/deploy/product-readonly.compose.yml" config --format json)
  scaleout=$(IMAGE_NS=test docker compose \
    -f "$ROOT/infra/load-test/deploy/product.compose.yml" \
    -f "$ROOT/infra/load-test/deploy/product-scaleout.compose.yml" config --format json)

  jq -e '(.services["product-service"].environment | has("PRODUCT_ORPHAN_RECOVERY_ENABLED")) == false' \
    <<<"$full" >/dev/null
  jq -e '.services["product-service"].environment.PRODUCT_ORPHAN_RECOVERY_ENABLED == "false"' \
    <<<"$product" >/dev/null
  jq -e '.services["product-service"].environment.PRODUCT_ORPHAN_RECOVERY_ENABLED == "false"' \
    <<<"$scaleout" >/dev/null
  jq -e '((.services["product-service"].environment | has("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")) == false)
    and ((.services["product-service"].environment | has("PRODUCT_DATASOURCE_REPLICA_HIKARI_MAXIMUM_POOL_SIZE")) == false)' \
    <<<"$full" >/dev/null
  jq -e '((.services["product-service"].environment | has("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")) == false)
    and ((.services["product-service"].environment | has("PRODUCT_DATASOURCE_REPLICA_HIKARI_MAXIMUM_POOL_SIZE")) == false)' \
    <<<"$product" >/dev/null
  jq -e '.services["product-service"].environment.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE == "50"
    and .services["product-service"].environment.PRODUCT_DATASOURCE_REPLICA_HIKARI_MAXIMUM_POOL_SIZE == "50"' \
    <<<"$scaleout" >/dev/null
fi
