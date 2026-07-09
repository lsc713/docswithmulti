#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# payment 대규모 시딩 (풀 고갈 대비)
#   merchant N개 = HTTP 생성 (싸다)
#   payment M개 = payment_db 직접 bulk INSERT (수만 건도 초 단위)
#   산출물: k6/seed/paymentKeys.json  ← stages.js 가 SharedArray 로 로드
#
# 취소 경로만 재므로 order_db / risk 스키마는 건드리지 않는다.
# risk 는 최초 취소 시 merchant-limit HTTP 로 daily_limit 를 가져온다.
#
# 사용:
#   local:  SEED_COUNT=5000 ./k6/seed/seed.sh
#   aws:    SEED_COUNT=100000 TARGET=aws \
#           MERCHANT_URL=http://10.0.1.22:8082 \
#           MYSQL_HOST=10.0.1.30 MYSQL_PORT=3306 ./k6/seed/seed.sh
# ─────────────────────────────────────────────────────────────
set -euo pipefail

SEED_COUNT="${SEED_COUNT:-5000}"
MERCHANT_COUNT="${MERCHANT_COUNT:-10}"
MERCHANT_URL="${MERCHANT_URL:-http://localhost:8082}"

# 로컬 docker-compose 는 mysql-payment 를 3311 로 매핑. aws 는 10.0.1.30:3306.
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3311}"
MYSQL_USER="${MYSQL_USER:-payment}"
MYSQL_PASS="${MYSQL_PASS:-payment}"
MYSQL_DB="${MYSQL_DB:-payment_db}"

OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/paymentKeys.json}"
PREFIX="pay_k6_$(date +%s)_"   # 재시딩 시 UK 충돌 방지 (run 별 유니크)

command -v mysql >/dev/null || { echo "mysql 클라이언트가 필요합니다"; exit 1; }
db() { mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$@" "$MYSQL_DB"; }

echo "[seed] merchant ${MERCHANT_COUNT}개 생성 (HTTP ${MERCHANT_URL})"
MIDS=()
for i in $(seq 1 "$MERCHANT_COUNT"); do
  resp=$(curl -sf -X POST "${MERCHANT_URL}/v1/merchants" -H 'Content-Type: application/json' \
    -d "{\"merchantKey\":\"merchant_k6seed_${i}_$(date +%s%N)\",\"name\":\"k6 seed 가맹점\",\"cancelPeriodDays\":30,\"dailyLimit\":1000000000}")
  mid=$(printf '%s' "$resp" | sed -n 's/.*"merchantId"[: ]*\([0-9]*\).*/\1/p')
  [ -z "$mid" ] && mid=$(printf '%s' "$resp" | sed -n 's/.*"id"[: ]*\([0-9]*\).*/\1/p')
  [ -z "$mid" ] && { echo "merchant 생성 실패: $resp"; exit 1; }
  MIDS+=("$mid")
done
echo "[seed] merchantIds: ${MIDS[*]}"

echo "[seed] payment ${SEED_COUNT}건 SQL 생성 → INSERT"
# ① payment bulk (multi-row VALUES, merchantId 라운드로빈)
MIDS_STR="${MIDS[*]}" awk -v n="$SEED_COUNT" -v mc="$MERCHANT_COUNT" -v pfx="$PREFIX" '
BEGIN{
  split(ENVIRON["MIDS_STR"], mids, " ");
  print "INSERT INTO payment (payment_key, merchant_id, user_id, pg_type, total_amount, currency, cancel_period_days, status) VALUES";
  for(i=1;i<=n;i++){
    mid=mids[((i-1)%mc)+1];
    printf "(\x27%s%d\x27,%s,%d,\x27TOSS\x27,10000,\x27KRW\x27,90,\x27COMPLETED\x27)%s\n", pfx, i, mid, 9000+i, (i<n?",":";");
  }
}' | db

# ② payment_item: payment 에서 조인 INSERT (order_item_id = id 기반 유니크, status ACTIVE)
db <<SQL
INSERT INTO payment_item (payment_id, order_item_id, product_id, product_auto_id, item_name, item_amount, status)
SELECT id, id + 100000000, 201, 1, 'k6 seed 상품', total_amount, 'ACTIVE'
FROM payment
WHERE payment_key LIKE '${PREFIX}%';
SQL

echo "[seed] paymentKeys.json 내보내기 → ${OUT}"
# JSON_ARRAYAGG: GROUP_CONCAT(1024B 제한) 회피. -r: 배치 이스케이프 비활성.
db -N -B -r <<SQL > "$OUT"
SELECT IFNULL(JSON_ARRAYAGG(
  JSON_OBJECT('paymentKey', p.payment_key, 'paymentItemId', pi.id, 'merchantId', p.merchant_id)
), JSON_ARRAY())
FROM payment p JOIN payment_item pi ON pi.payment_id = p.id
WHERE p.payment_key LIKE '${PREFIX}%';
SQL

CNT=$(grep -o '"paymentKey"' "$OUT" | wc -l | tr -d ' ')
echo "[seed] 완료: ${CNT}건 → ${OUT}"
[ "$CNT" -eq "$SEED_COUNT" ] || echo "[seed] ⚠ 기대 ${SEED_COUNT} != 실제 ${CNT} — 확인 필요"
