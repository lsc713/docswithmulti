#!/usr/bin/env bash
# run-compensation.sh
#
# Scenario 3 오케스트레이션:
#   1. risk-management-service 중단
#   2. k6로 취소 요청 (risk 없이 → FAILED → compensation_retry INSERT)
#   3. risk 재시작
#   4. compensation-retry 스케줄러(30초) 대기
#   5. compensation_retry 테이블 조회로 결과 확인
#
# 사용법:
#   bash k6/run-compensation.sh
#
# 전제:
#   - docker-compose 실행 중
#   - k6 설치됨 (brew install k6)

set -euo pipefail

# ── 컨테이너 이름 (docker-compose 프로젝트명에 따라 다를 수 있음) ──
# docker ps 로 확인 후 필요 시 수정
RISK_CONTAINER="${RISK_CONTAINER:-multicommerce-risk-management-service-1}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-multicommerce-mysql-risk-1}"

# ── 색상 출력 헬퍼 ──
info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[OK]\033[0m    $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
error() { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; }

# 스크립트 위치 기준으로 프로젝트 루트 탐색
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

info "=== Scenario 3: risk 장애 보상 트랜잭션 테스트 ==="
echo ""

# ──────────────────────────────────────────────────────────────
# 1. risk 서비스 중단
# ──────────────────────────────────────────────────────────────
info "[1/5] risk-management-service 중단..."
if ! docker stop "$RISK_CONTAINER" 2>/dev/null; then
  error "컨테이너를 찾을 수 없습니다: $RISK_CONTAINER"
  error "docker ps 로 실제 컨테이너 이름을 확인 후 RISK_CONTAINER 환경변수를 설정하세요."
  error "예: RISK_CONTAINER=my-risk-1 bash k6/run-compensation.sh"
  exit 1
fi
ok "risk-management-service 중단 완료"
echo ""

# ──────────────────────────────────────────────────────────────
# 2. k6 실행 (risk 다운 상태)
# ──────────────────────────────────────────────────────────────
info "[2/5] k6 실행 (risk DOWN 상태에서 취소 요청)..."
k6 run "$SCRIPT_DIR/compensation-test.js" || true
# k6 exit code는 threshold 실패 시 1이지만, 이 시나리오에서는 에러가 예상됨
echo ""

# ──────────────────────────────────────────────────────────────
# 3. risk 서비스 재시작
# ──────────────────────────────────────────────────────────────
info "[3/5] risk-management-service 재시작..."
docker start "$RISK_CONTAINER"
info "      서비스 기동 대기 (15초)..."
sleep 15
ok "risk-management-service 재시작 완료"
echo ""

# ──────────────────────────────────────────────────────────────
# 4. compensation-retry 스케줄러 대기
#    스케줄러 주기: 30초 → 여유 있게 45초 대기
# ──────────────────────────────────────────────────────────────
info "[4/5] compensation-retry 스케줄러 대기 (45초)..."
sleep 45
echo ""

# ──────────────────────────────────────────────────────────────
# 5. DB 조회
# ──────────────────────────────────────────────────────────────
info "[5/5] compensation_retry 테이블 조회..."
echo ""

docker exec "$MYSQL_CONTAINER" mysql \
  -u risk -prisk risk_db \
  --table \
  -e "
SELECT
  status,
  retry_count,
  COUNT(*)      AS cnt,
  MIN(created_at) AS earliest,
  MAX(updated_at) AS latest
FROM compensation_retry
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
GROUP BY status, retry_count
ORDER BY status, retry_count;
"

echo ""
info "상세 조회 (최대 20건):"
docker exec "$MYSQL_CONTAINER" mysql \
  -u risk -prisk risk_db \
  --table \
  -e "
SELECT
  id,
  cancel_request_id,
  status,
  retry_count,
  created_at,
  updated_at
FROM compensation_retry
WHERE created_at >= NOW() - INTERVAL 10 MINUTE
ORDER BY created_at DESC
LIMIT 20;
"

echo ""
ok "=== 테스트 완료 ==="
echo ""
echo "  compensation_retry에 PENDING 또는 COMPLETED 행이 있으면 보상 트랜잭션이 정상 동작한 것입니다."
echo "  EXHAUSTED 행이 있으면 5회 재시도 모두 실패 — 운영팀 수동 처리 대상입니다."
