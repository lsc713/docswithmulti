#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
require() { rg -q -- "$2" "$1" || { echo "missing $2 in $1" >&2; exit 1; }; }

require "$ROOT/infra/load-test/variables.tf" 'variable "load_test_profile"'
require "$ROOT/infra/load-test/instances.tf" 'var.load_test_profile == "product"'
require "$ROOT/infra/load-test/network.tf" 'source_dest_check *= *false'
require "$ROOT/infra/load-test/network.tf" 'network_interface_id'
require "$ROOT/infra/load-test/outputs.tf" 'output "egress_mode"'
