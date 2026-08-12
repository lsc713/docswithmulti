#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT
SEED_COUNT=2 OUT="$OUT" "$ROOT/k6/seed/product-detail-seed.sh"
[ "$(jq length "$OUT")" -eq 2 ]
id=$(jq -r '.[0]' "$OUT")
curl -sf "${PRODUCT_URL:-http://localhost:8084}/v1/products/$id" | jq -e '
  (.category|length)==3 and (.images|length)==3 and (.skus|length)==9 and
  (.variantOptions|length)==2 and (.specs|length)==2' >/dev/null
