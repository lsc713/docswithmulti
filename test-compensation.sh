#!/bin/bash

# =============================================================
# compensation-retry 시나리오 테스트 스크립트
#
# 시나리오:
#   1. 가맹점/주문/결제 생성
#   2. risk-management-service 중단
#   3. 취소 요청 → FAILED 응답 확인
#   4. payment DB: cancel_request status=FAILED 확인
#   5. payment DB: compensation_retry 데이터 확인
#   6. risk-management-service 재시작
#   7. 60초 대기 (compensation-retry 스케줄러 30초 주기)
#   8. payment DB: compensation_retry status=DONE 확인
# =============================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

# 색상
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()     { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
error()   { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
section() { echo -e "\n${CYAN}=== $1 ===${NC}"; }

# DB 쿼리 헬퍼: payment_db (포트 3311)
query_payment_db() {
  mysql -h 127.0.0.1 -P 3311 -u payment -ppayment payment_db \
    --skip-column-names -se "$1" 2>/dev/null
}

# =============================================================
# 인프라 초기화 및 재시작
# =============================================================
section "인프라 초기화"

log "Docker 컨테이너 및 볼륨 초기화 중..."
docker-compose down -v 2>/dev/null || true
sleep 3

log "Docker 컨테이너 시작 중..."
docker-compose up -d
log "DB/Kafka 초기화 대기 중 (20초)..."
sleep 20

# =============================================================
# 기존 프로세스 정리 후 전체 서비스 기동
# =============================================================
section "서비스 기동"

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti :"$port" 2>/dev/null) || true
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill -9 2>/dev/null || true
    log "포트 $port 프로세스 종료"
  fi
}

log "기존 프로세스 종료 중..."
kill_port 8080
kill_port 8081
kill_port 8082
kill_port 8083
sleep 2

start_service() {
  local module=$1
  local port=$2
  log "$module 시작 (포트: $port)..."
  ./gradlew :"$module":bootRun > "$LOG_DIR/$module.log" 2>&1 &
  echo $! > "$LOG_DIR/$module.pid"
}

start_service "merchant-limit-service" 8082
start_service "risk-management-service" 8083
start_service "order-service" 8081
start_service "payment-service" 8080

wait_for_server() {
  local name=$1
  local port=$2
  local max=30
  local count=0
  log "$name 시작 대기 중..."
  until curl -s "http://localhost:$port/actuator/health" > /dev/null 2>&1; do
    sleep 2
    count=$((count + 1))
    [ $count -ge $max ] && error "$name 시작 실패. 로그: $LOG_DIR/$name.log"
    echo -n "."
  done
  echo ""
  log "$name 준비 완료"
}

wait_for_server "merchant-limit-service" 8082
wait_for_server "risk-management-service" 8083
wait_for_server "order-service" 8081
wait_for_server "payment-service" 8080

# =============================================================
# 1단계: 테스트 데이터 생성
# =============================================================
section "1단계: 테스트 데이터 생성"

log "가맹점 생성..."
MERCHANT_RESPONSE=$(curl -s -X POST http://localhost:8082/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{
    "merchantKey": "comp_test_merchant",
    "name": "보상재시도 테스트 가맹점",
    "cancelPeriodDays": 30,
    "dailyLimit": 10000000
  }')
echo "가맹점 응답: $MERCHANT_RESPONSE"
MERCHANT_ID=$(echo "$MERCHANT_RESPONSE" | grep -o '"merchantId":[0-9]*' | grep -o '[0-9]*')
[ -z "$MERCHANT_ID" ] && error "가맹점 생성 실패"
log "가맹점 ID: $MERCHANT_ID"

log "주문 생성..."
ORDER_RESPONSE=$(curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 9001,
    "items": [
      { "productId": 301, "itemName": "테스트 상품 A", "price": 40000 },
      { "productId": 302, "itemName": "테스트 상품 B", "price": 60000 }
    ]
  }')
echo "주문 응답: $ORDER_RESPONSE"
ORDER_ITEM_ID_1=$(echo "$ORDER_RESPONSE" | grep -o '"orderItemId":[0-9]*' | head -1 | grep -o '[0-9]*')
ORDER_ITEM_ID_2=$(echo "$ORDER_RESPONSE" | grep -o '"orderItemId":[0-9]*' | tail -1 | grep -o '[0-9]*')
[ -z "$ORDER_ITEM_ID_1" ] && error "주문 생성 실패"
log "주문 아이템 ID: $ORDER_ITEM_ID_1, $ORDER_ITEM_ID_2"

log "결제 생성..."
PAYMENT_RESPONSE=$(curl -s -X POST http://localhost:8080/v1/payments \
  -H "Content-Type: application/json" \
  -d "{
    \"merchantId\": $MERCHANT_ID,
    \"userId\": 9001,
    \"pgType\": \"TOSS\",
    \"cancelPeriodDays\": 30,
    \"items\": [
      { \"orderItemId\": $ORDER_ITEM_ID_1, \"productId\": 301, \"itemName\": \"테스트 상품 A\", \"itemAmount\": 40000 },
      { \"orderItemId\": $ORDER_ITEM_ID_2, \"productId\": 302, \"itemName\": \"테스트 상품 B\", \"itemAmount\": 60000 }
    ]
  }")
echo "결제 응답: $PAYMENT_RESPONSE"
PAYMENT_KEY=$(echo "$PAYMENT_RESPONSE" | grep -o '"paymentKey":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"')
PAYMENT_ITEM_ID_1=$(echo "$PAYMENT_RESPONSE" | grep -o '"paymentItemId":[0-9]*' | head -1 | grep -o '[0-9]*' || true)
PAYMENT_ITEM_ID_2=$(echo "$PAYMENT_RESPONSE" | grep -o '"paymentItemId":[0-9]*' | tail -1 | grep -o '[0-9]*' || true)
[ -z "$PAYMENT_KEY" ] && error "결제 생성 실패"
log "결제 키: $PAYMENT_KEY"
log "결제 아이템 ID: $PAYMENT_ITEM_ID_1, $PAYMENT_ITEM_ID_2"

# =============================================================
# 2단계: risk-management-service 중단
# =============================================================
section "2단계: risk-management-service 중단"

log "risk-management-service PID 탐색..."
RISK_PID=$(lsof -ti :8083 2>/dev/null | head -1) || true
if [ -z "$RISK_PID" ]; then
  # pid 파일에서 확인
  if [ -f "$LOG_DIR/risk-management-service.pid" ]; then
    RISK_PID=$(cat "$LOG_DIR/risk-management-service.pid")
  fi
fi
[ -z "$RISK_PID" ] && error "risk-management-service PID를 찾을 수 없습니다"

log "risk-management-service 종료 (PID: $RISK_PID)..."
kill "$RISK_PID" 2>/dev/null || true

# 포트가 해제될 때까지 대기
count=0
while lsof -ti :8083 > /dev/null 2>&1; do
  sleep 1
  count=$((count + 1))
  [ $count -ge 10 ] && { kill -9 "$(lsof -ti :8083)" 2>/dev/null || true; break; }
done
log "risk-management-service 종료 완료"
sleep 1

# =============================================================
# 3단계: 취소 요청 → FAILED 응답 확인
# =============================================================
section "3단계: 취소 요청 (risk 서비스 다운 상태)"

log "결제 취소 요청 중..."
CANCEL_RESPONSE=$(curl -s -X POST "http://localhost:8080/v1/payments/$PAYMENT_KEY/cancel" \
  -H "Content-Type: application/json" \
  -d "{
    \"cancelReason\": \"보상재시도 테스트\",
    \"cancelItems\": [
      { \"paymentItemId\": $PAYMENT_ITEM_ID_1 },
      { \"paymentItemId\": $PAYMENT_ITEM_ID_2 }
    ]
  }" 2>/dev/null || echo '{}')

echo ""
echo "취소 응답:"
echo "$CANCEL_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$CANCEL_RESPONSE"

CANCEL_STATUS=$(echo "$CANCEL_RESPONSE" | grep -o '"status":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"' || true)
CANCEL_ERROR_CODE=$(echo "$CANCEL_RESPONSE" | grep -o '"code":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"' || true)

# FAILED 또는 에러 응답(risk 서비스 연결 실패)이어야 정상
if [ "$CANCEL_STATUS" = "FAILED" ] || [ -n "$CANCEL_ERROR_CODE" ]; then
  log "✅ 취소 요청이 예상대로 실패했습니다 (status=$CANCEL_STATUS, code=$CANCEL_ERROR_CODE)"
else
  warn "⚠️ 예상치 못한 취소 상태: status=$CANCEL_STATUS (risk 서비스가 실제로 응답했을 수 있습니다)"
fi

sleep 2

# =============================================================
# 4단계: cancel_request status=FAILED 확인
# =============================================================
section "4단계: cancel_request 상태 확인 (payment DB)"

CANCEL_REQUEST_STATUS=$(query_payment_db \
  "SELECT status FROM cancel_request ORDER BY created_at DESC LIMIT 1;")

echo "cancel_request 최신 상태: $CANCEL_REQUEST_STATUS"
if [ "$CANCEL_REQUEST_STATUS" = "FAILED" ]; then
  log "✅ cancel_request.status = FAILED 확인"
else
  warn "⚠️ cancel_request.status = $CANCEL_REQUEST_STATUS (FAILED 예상)"
fi

# =============================================================
# 5단계: compensation_retry 데이터 확인
# =============================================================
section "5단계: compensation_retry 데이터 확인 (payment DB)"

COMP_COUNT=$(query_payment_db \
  "SELECT COUNT(*) FROM compensation_retry WHERE status IN ('PENDING', 'FAILED');")
COMP_ROW=$(query_payment_db \
  "SELECT id, cancel_request_id, merchant_id, restore_amount, status, attempt_count
   FROM compensation_retry ORDER BY created_at DESC LIMIT 1;")

echo "compensation_retry 건수: $COMP_COUNT"
echo "compensation_retry 최신 행:"
echo "$COMP_ROW"

if [ "$COMP_COUNT" -ge 1 ]; then
  log "✅ compensation_retry에 재시도 대기 데이터 존재"
else
  warn "⚠️ compensation_retry 데이터 없음 (보상 재시도 로직 확인 필요)"
fi

# =============================================================
# 6단계: risk-management-service 재시작
# =============================================================
section "6단계: risk-management-service 재시작"

log "risk-management-service 재시작..."
./gradlew :risk-management-service:bootRun > "$LOG_DIR/risk-management-service.log" 2>&1 &
echo $! > "$LOG_DIR/risk-management-service.pid"

wait_for_server "risk-management-service" 8083

# =============================================================
# 7단계: 60초 대기 (compensation-retry 스케줄러 30초 주기)
# =============================================================
section "7단계: compensation-retry 스케줄러 대기"

log "60초 대기 중 (compensation-retry 스케줄러가 최대 2회 실행됩니다)..."
for i in $(seq 60 -1 1); do
  printf "\r남은 시간: %2d초 " "$i"
  sleep 1
done
echo ""

# =============================================================
# 8단계: compensation_retry status=DONE 확인
# =============================================================
section "8단계: compensation_retry 최종 상태 확인"

COMP_DONE_STATUS=$(query_payment_db \
  "SELECT status FROM compensation_retry ORDER BY created_at DESC LIMIT 1;")
COMP_DONE_ROW=$(query_payment_db \
  "SELECT id, cancel_request_id, status, attempt_count, last_error, updated_at
   FROM compensation_retry ORDER BY created_at DESC LIMIT 1;")

echo "compensation_retry 최신 행:"
echo "$COMP_DONE_ROW"

echo ""
echo "=============================="
if [ "$COMP_DONE_STATUS" = "DONE" ]; then
  log "✅ compensation_retry.status = DONE — 보상 재시도 성공!"
else
  warn "⚠️ compensation_retry.status = $COMP_DONE_STATUS (DONE 예상 — 스케줄러 로그 확인)"
  echo ""
  warn "payment-service 스케줄러 로그 확인:"
  grep -i "compensation\|retry\|보상" "$LOG_DIR/payment-service.log" 2>/dev/null | tail -20 || true
fi
echo "=============================="

# =============================================================
# 서버 종료 (선택)
# =============================================================
echo ""
read -r -p "서버를 종료하시겠습니까? (y/N): " STOP
if [ "$STOP" = "y" ] || [ "$STOP" = "Y" ]; then
  log "서버 종료 중..."
  for pid_file in "$LOG_DIR"/*.pid; do
    if [ -f "$pid_file" ]; then
      kill "$(cat "$pid_file")" 2>/dev/null || true
      rm "$pid_file"
    fi
  done
  log "서버 종료 완료"
fi
