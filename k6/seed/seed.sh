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
# ── 현실 믹스용 (기본값 = 기존 동작 보존) ──
HOT_MERCHANT_COUNT="${HOT_MERCHANT_COUNT:-2}"          # 핫 가맹점 수(앞에서부터)
HOT_TRAFFIC_PCT="${HOT_TRAFFIC_PCT:-0}"               # 0=라운드로빈(기존) · >0=파레토 편중(핫으로 %)
ITEMS_PER_PAYMENT="${ITEMS_PER_PAYMENT:-1}"           # 결제당 아이템 수(부분취소용)
TIGHT_DAILY_LIMIT="${TIGHT_DAILY_LIMIT:-1000000000}"  # 핫 가맹점 한도(초과 시 거부/FAILED 창발)

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
  lim=1000000000
  [ "$HOT_TRAFFIC_PCT" -gt 0 ] && [ "$i" -le "$HOT_MERCHANT_COUNT" ] && lim="$TIGHT_DAILY_LIMIT"   # 핫 가맹점만 타이트
  resp=$(curl -sf -X POST "${MERCHANT_URL}/v1/merchants" -H 'Content-Type: application/json' \
    -d "{\"merchantKey\":\"merchant_k6seed_${i}_$(date +%s%N)\",\"name\":\"k6 seed 가맹점\",\"cancelPeriodDays\":30,\"dailyLimit\":${lim}}")
  mid=$(printf '%s' "$resp" | sed -n 's/.*"merchantId"[: ]*\([0-9]*\).*/\1/p')
  [ -z "$mid" ] && mid=$(printf '%s' "$resp" | sed -n 's/.*"id"[: ]*\([0-9]*\).*/\1/p')
  [ -z "$mid" ] && { echo "merchant 생성 실패: $resp"; exit 1; }
  MIDS+=("$mid")
done
echo "[seed] merchantIds: ${MIDS[*]}"

echo "[seed] payment ${SEED_COUNT}건 SQL 생성 → INSERT"
# ① payment bulk (multi-row VALUES). HOT_TRAFFIC_PCT>0 이면 파레토 편중, 아니면 라운드로빈.
MIDS_STR="${MIDS[*]}" awk -v n="$SEED_COUNT" -v mc="$MERCHANT_COUNT" -v pfx="$PREFIX" \
  -v htp="$HOT_TRAFFIC_PCT" -v hc="$HOT_MERCHANT_COUNT" -v ipp="$ITEMS_PER_PAYMENT" '
BEGIN{
  srand();
  split(ENVIRON["MIDS_STR"], mids, " ");
  ta=ipp*10000;   # 결제 총액 = 아이템수 × 10000 (아이템 합과 일치)
  print "INSERT INTO payment (payment_key, merchant_id, user_id, pg_type, total_amount, currency, cancel_period_days, status) VALUES";
  for(i=1;i<=n;i++){
    if(htp>0){
      if(rand()*100 < htp) mid=mids[1+int(rand()*hc)];          # 핫(앞 hc개)로 htp%
      else mid=mids[hc+1+int(rand()*(mc-hc))];                   # 나머지는 콜드
    } else mid=mids[((i-1)%mc)+1];                               # 기존 라운드로빈
    printf "(\047%s%d\047,%s,%d,\047TOSS\047,%d,\047KRW\047,90,\047COMPLETED\047)%s\n", pfx, i, mid, 9000+i, ta, (i<n?",":";");
  }
}' | db

# ② payment_item: 결제당 ITEMS_PER_PAYMENT 개 (숫자표 크로스조인). order_item_id=id*100+k 유니크.
NUMS=$(for k in $(seq 1 "$ITEMS_PER_PAYMENT"); do [ "$k" -eq 1 ] && printf "SELECT 1 AS k" || printf " UNION ALL SELECT %d" "$k"; done)
db <<SQL
INSERT INTO payment_item (payment_id, order_item_id, product_id, product_auto_id, item_name, item_amount, status)
SELECT p.id, p.id*100 + n.k, 201, 1, 'k6 seed 상품', 10000, 'ACTIVE'
FROM payment p JOIN (${NUMS}) n
WHERE p.payment_key LIKE '${PREFIX}%';
SQL

echo "[seed] paymentKeys.json 내보내기 → ${OUT}"
# JSON_ARRAYAGG: GROUP_CONCAT(1024B 제한) 회피. -r: 배치 이스케이프 비활성.
db -N -B -r <<SQL > "$OUT"
SELECT IFNULL(JSON_ARRAYAGG(j), JSON_ARRAY()) FROM (
  SELECT JSON_OBJECT(
    'paymentKey', p.payment_key, 'merchantId', p.merchant_id,
    'paymentItemId', MIN(pi.id),
    'paymentItemIds', JSON_ARRAYAGG(pi.id)
  ) AS j
  FROM payment p JOIN payment_item pi ON pi.payment_id = p.id
  WHERE p.payment_key LIKE '${PREFIX}%'
  GROUP BY p.id, p.payment_key, p.merchant_id
) t;
SQL

CNT=$(grep -o '"paymentKey"' "$OUT" | wc -l | tr -d ' ')
echo "[seed] 완료: ${CNT}건 → ${OUT}"
[ "$CNT" -eq "$SEED_COUNT" ] || echo "[seed] ⚠ 기대 ${SEED_COUNT} != 실제 ${CNT} — 확인 필요"
