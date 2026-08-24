#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
rg -q 'contains\(\["full", "product", "product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/variables.tf"
rg -q 'mysql-product-replica.*10.0.1.34' "$ROOT/infra/load-test/instances.tf"
rg -Uq 'resource "aws_instance" "nat" \{\n  count\s*=\s*contains\(\["product", "product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/network.tf"
rg -Uq 'resource "aws_route" "private_nat_instance" \{\n  count\s*=\s*contains\(\["product", "product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/network.tf"
rg -Uq 'output "egress_mode" \{[\s\S]*value\s*=\s*contains\(\["product", "product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/outputs.tf"
rg -Uq 'resource "aws_lb" "product" \{\n  count\s*=\s*contains\(\["product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/product-nlb.tf"
rg -Uq 'resource "aws_lb_target_group" "product" \{\n  count\s*=\s*contains\(\["product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/product-nlb.tf"
rg -Uq 'resource "aws_lb_listener" "product" \{\n  count\s*=\s*contains\(\["product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/product-nlb.tf"
rg -Uq 'resource "aws_lb_target_group_attachment" "product" \{\n  for_each\s*=\s*contains\(\["product-scaleout", "product-replica"\], var.load_test_profile\)' "$ROOT/infra/load-test/product-nlb.tf"
rg -q 'iops\s*=\s*contains\(\["mysql-product", "mysql-product-replica"\], each.key\)' "$ROOT/infra/load-test/instances.tf"
rg -q 'throughput\s*=\s*contains\(\["mysql-product", "mysql-product-replica"\], each.key\)' "$ROOT/infra/load-test/instances.tf"
