#!/bin/bash

# =============================================================
# multicommerce 테스트 자동화 스크립트
# 서버 부팅 → 테스트 데이터 생성 → 결제 취소 API 호출
# =============================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

# 색상
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# =============================================================
# 1. 인프라 확인
# =============================================================
log "인프라 상태 확인 중..."

if ! docker ps | grep -q "mysql-payment"; then
  warn "Docker 컨테이너가 없습니다. docker-compose up -d 실행 중..."
  docker-compose up -d
  sleep 10
fi

# =============================================================
# 2. 서버 부팅
# =============================================================
log "기존 서비스 프로세스 종료 중..."

kill_port() {
  local port=$1
  local pids
  pids=$(lsof -ti :$port 2>/dev/null) || true
  if [ -n "$pids" ]; then
    echo "$pids" | xargs kill -9 2>/dev/null || true
    log "포트 $port 프로세스 종료 완료"
  fi
}

kill_port 8080
kill_port 8081
kill_port 8082
kill_port 8083
sleep 2

log "서버 부팅 시작..."

start_service() {
  local module=$1
  local port=$2
  log "$module 시작 (포트: $port)..."
  ./gradlew :$module:bootRun > "$LOG_DIR/$module.log" 2>&1 &
  echo $! > "$LOG_DIR/$module.pid"
}

start_service "merchant-limit-service" 8082
start_service "risk-management-service" 8083
start_service "order-service" 8081
start_service "payment-service" 8080

# 서버 헬스체크
wait_for_server() {
  local name=$1
  local port=$2
  local max=30
  local count=0

  log "$name 시작 대기 중..."
  until curl -s "http://localhost:$port/actuator/health" > /dev/null 2>&1; do
    sleep 2
    count=$((count + 1))
    if [ $count -ge $max ]; then
      error "$name 시작 실패 (타임아웃). 로그: $LOG_DIR/$name.log"
    fi
    echo -n "."
  done
  echo ""
  log "$name 시작 완료"
}

wait_for_server "merchant-limit-service" 8082
wait_for_server "risk-management-service" 8083
wait_for_server "order-service" 8081
wait_for_server "payment-service" 8080

# =============================================================
# 3. 테스트 데이터 생성
# =============================================================
log "테스트 데이터 생성 중..."

# 가맹점 생성
log "가맹점 생성..."
MERCHANT_RESPONSE=$(curl -s -X POST http://localhost:8082/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{
    "merchantKey": "merchant_test_001",
    "name": "테스트 가맹점",
    "cancelPeriodDays": 30,
    "dailyLimit": 10000000
  }')
echo "가맹점 응답: $MERCHANT_RESPONSE"
MERCHANT_ID=$(echo $MERCHANT_RESPONSE | grep -o '"merchantId":[0-9]*' | grep -o '[0-9]*')
log "가맹점 ID: $MERCHANT_ID"

# 주문 생성
log "주문 생성..."
ORDER_RESPONSE=$(curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 9001,
    "items": [
      { "productId": 201, "itemName": "흰 셔츠 M", "price": 30000 },
      { "productId": 202, "itemName": "청바지 32", "price": 50000 }
    ]
  }')
echo "주문 응답: $ORDER_RESPONSE"
ORDER_ITEM_ID_1=$(echo $ORDER_RESPONSE | grep -o '"orderItemId":[0-9]*' | head -1 | grep -o '[0-9]*')
ORDER_ITEM_ID_2=$(echo $ORDER_RESPONSE | grep -o '"orderItemId":[0-9]*' | tail -1 | grep -o '[0-9]*')
log "주문 아이템 ID: $ORDER_ITEM_ID_1, $ORDER_ITEM_ID_2"

# 결제 생성
log "결제 생성..."
PAYMENT_RESPONSE=$(curl -s -X POST http://localhost:8080/v1/payments \
  -H "Content-Type: application/json" \
  -d "{
    \"merchantId\": $MERCHANT_ID,
    \"userId\": 9001,
    \"pgType\": \"TOSS\",
    \"cancelPeriodDays\": 30,
    \"items\": [
      { \"orderItemId\": $ORDER_ITEM_ID_1, \"productId\": 201, \"itemName\": \"흰 셔츠 M\", \"itemAmount\": 30000 },
      { \"orderItemId\": $ORDER_ITEM_ID_2, \"productId\": 202, \"itemName\": \"청바지 32\", \"itemAmount\": 50000 }
    ]
  }")
echo "결제 응답: $PAYMENT_RESPONSE"
PAYMENT_KEY=$(echo $PAYMENT_RESPONSE | grep -o '"paymentKey":"[^"]*"' | grep -o '"[^"]*"$' | tr -d '"')
PAYMENT_ITEM_ID_1=$(echo $PAYMENT_RESPONSE | grep -o '"paymentItemId":[0-9]*' | head -1 | grep -o '[0-9]*' || true)
PAYMENT_ITEM_ID_2=$(echo $PAYMENT_RESPONSE | grep -o '"paymentItemId":[0-9]*' | tail -1 | grep -o '[0-9]*' || true)
log "결제 키: $PAYMENT_KEY"
log "결제 아이템 ID: $PAYMENT_ITEM_ID_1, $PAYMENT_ITEM_ID_2"

# =============================================================
# 4. 결제 취소 API 호출
# =============================================================
log "결제 취소 요청 중..."
sleep 2

CANCEL_RESPONSE=$(curl -s -X POST "http://localhost:8080/v1/payments/$PAYMENT_KEY/cancel" \
  -H "Content-Type: application/json" \
  -d "{
    \"cancelReason\": \"단순 변심\",
    \"cancelItems\": [
      { \"paymentItemId\": $PAYMENT_ITEM_ID_1 },
      { \"paymentItemId\": $PAYMENT_ITEM_ID_2 }
    ]
  }")

echo ""
echo "=============================="
echo "결제 취소 응답:"
echo $CANCEL_RESPONSE | python3 -m json.tool 2>/dev/null || echo $CANCEL_RESPONSE
echo "=============================="

# 결과 확인
STATUS=$(echo $CANCEL_RESPONSE | grep -o '"status":"[^"]*"' | grep -o ':[^}]*' | tr -d ':"')
if [ "$STATUS" = "COMPLETED" ]; then
  log "✅ 결제 취소 성공! status=$STATUS"
else
  warn "⚠️ 결제 취소 상태: $STATUS (COMPLETED가 아님 - 로그 확인 필요)"
fi

# =============================================================
# 5. 서버 종료 (선택)
# =============================================================
read -p "서버를 종료하시겠습니까? (y/N): " STOP
if [ "$STOP" = "y" ] || [ "$STOP" = "Y" ]; then
  log "서버 종료 중..."
  for pid_file in "$LOG_DIR"/*.pid; do
    if [ -f "$pid_file" ]; then
      kill $(cat "$pid_file") 2>/dev/null || true
      rm "$pid_file"
    fi
  done
  log "서버 종료 완료"
fi