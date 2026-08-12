#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# SSM 원격 배포 드라이버 — terraform apply 로 뜬 인스턴스에 컨테이너를 순서대로 올린다.
#   인터랙티브 SSM 세션 없이 `aws ssm send-command` 로 role 태그별 일괄 실행.
#   앱 이미지는 Docker Hub(public) 에서 pull — 호스트 빌드/소스 불필요. compose yml 만 clone.
#   관측(node-exporter 전 호스트 + obs 스택)도 함께 배포한다.
#
# 사용:
#   IMAGE_NS=<dockerhub-user> ./ssm-deploy.sh                      # 앱+관측 전체
#   IMAGE_NS=<user> ROLES="payment risk" ./ssm-deploy.sh          # 특정 role 만(관측 스킵)
#   IMAGE_NS=<user> LOG_CLOUDWATCH=1 ./ssm-deploy.sh              # 앱 로그를 CloudWatch 로
#   IMAGE_NS=<user> DEPLOY_OBS=0 ./ssm-deploy.sh                  # 관측 배포 생략
#
# 환경변수:
#   IMAGE_NS       (필수) Docker Hub 네임스페이스 = 사용자명
#   IMAGE_TAG      (기본 latest) 배포 태그 접미
#   REPO_URL       (기본 public repo) compose yml clone 소스
#   AWS_REGION     (기본 ap-northeast-2)
#   LOG_CLOUDWATCH (기본 0) 1이면 앱 컨테이너 로그를 awslogs→CloudWatch 로 전송
#   DEPLOY_OBS     (기본 1) node-exporter(전 호스트)+obs 스택 배포. ROLES 지정 시 자동 0.
# ─────────────────────────────────────────────────────────────
set -euo pipefail

IMAGE_NS="${IMAGE_NS:?Docker Hub 사용자명 필요. 예: IMAGE_NS=myuser ./ssm-deploy.sh}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REPO_URL="${REPO_URL:-https://github.com/lsc713/docswithmulti.git}"
REGION="${AWS_REGION:-ap-northeast-2}"
LOG_CLOUDWATCH="${LOG_CLOUDWATCH:-0}"

# role → compose 파일 (순서 = 배포 순서: 인프라 → DB → 앱)
# macOS 기본 bash 3.2 는 연관배열(declare -A) 미지원 → case 로 매핑.
ORDER="infra mysql-payment mysql-risk cold-db mysql-product cold-svc risk payment product"
compose_for() {
  case "$1" in
    infra)         echo infra.compose.yml ;;
    mysql-payment) echo mysql-payment.compose.yml ;;
    mysql-risk)    echo mysql-risk.compose.yml ;;
    cold-db)       echo cold-db.compose.yml ;;
    mysql-product) echo mysql-product.compose.yml ;;
    cold-svc)      echo cold-svc.compose.yml ;;
    risk)          echo risk.compose.yml ;;
    payment)       echo payment.compose.yml ;;
    product)       echo product.compose.yml ;;
    *)             echo "" ;;
  esac
}

# LOG_CLOUDWATCH=1 일 때 앱 role 에 awslogs 오버라이드 -f 를 덧붙임 (검증된 기본 경로는 불변).
logging_override() {
  [ "$LOG_CLOUDWATCH" = "1" ] || { echo ""; return; }
  case "$1" in
    payment)  echo "-f logging/payment.cw.yml" ;;
    risk)     echo "-f logging/risk.cw.yml" ;;
    cold-svc) echo "-f logging/cold-svc.cw.yml" ;;
    *)        echo "" ;;
  esac
}

# ROLES 를 명시하면 관측 배포는 생략(부분 재배포 시 방해 방지).
if [ -n "${ROLES:-}" ]; then DEPLOY_OBS="${DEPLOY_OBS:-0}"; else DEPLOY_OBS="${DEPLOY_OBS:-1}"; fi
ROLES="${ROLES:-$ORDER}"

# role 태그 인스턴스에 스크립트 전송 후 완료 폴링. stdout 출력.
ssm_run() {
  local role="$1" script="$2" iid params cid st
  iid=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Role,Values=${role}" "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].InstanceId' --output text)
  [ -n "$iid" ] || { echo "  ⚠ role=${role} 인스턴스(running) 없음 — 건너뜀"; return 1; }

  params=$(jq -n --arg s "$script" '{commands: [$s]}')
  cid=$(aws ssm send-command --region "$REGION" \
    --instance-ids $iid \
    --document-name "AWS-RunShellScript" \
    --comment "loadtest deploy ${role}" \
    --parameters "$params" \
    --timeout-seconds 600 \
    --query 'Command.CommandId' --output text)

  while :; do
    st=$(aws ssm list-command-invocations --region "$REGION" --command-id "$cid" \
      --query 'CommandInvocations[0].Status' --output text 2>/dev/null || echo Pending)
    case "$st" in
      Success) break ;;
      Failed|Cancelled|TimedOut) echo "  ✗ ${role} 실패($st)"; break ;;
      *) sleep 5 ;;
    esac
  done
  aws ssm list-command-invocations --region "$REGION" --command-id "$cid" --details \
    --query 'CommandInvocations[0].CommandPlugins[0].Output' --output text | sed 's/^/    /'
}

# 공통 clone 헤더 (repo 가 없으면 clone, 있으면 최신화)
clone_header() {
  cat <<EOF
set -e
mkdir -p /opt/loadtest
if [ ! -d /opt/loadtest/repo/.git ]; then
  git clone --depth 1 '${REPO_URL}' /opt/loadtest/repo
else
  git -C /opt/loadtest/repo pull --ff-only || true
fi
EOF
}

for role in $ROLES; do
  file="$(compose_for "$role")"
  [ -n "$file" ] || { echo "알 수 없는 role: $role"; continue; }
  extra="$(logging_override "$role")"
  echo "── [$role] ${file} 배포${extra:+ (+CloudWatch 로그)} ──"

  remote="$(clone_header)
export IMAGE_NS='${IMAGE_NS}' IMAGE_TAG='${IMAGE_TAG}' AWS_REGION='${REGION}'
# 실측 토글 포워딩 (로컬 env → 원격 compose). 미설정 시 안전한 기본값(off).
export SERVER_TOMCAT_MBEANREGISTRY_ENABLED='${SERVER_TOMCAT_MBEANREGISTRY_ENABLED:-false}' OTEL_JAVAAGENT='${OTEL_JAVAAGENT:-}' LOADTEST_QUERYCOUNT_ENABLED='${LOADTEST_QUERYCOUNT_ENABLED:-false}' CANCEL_PUBLISH_MODE='${CANCEL_PUBLISH_MODE:-INLINE}' CANCEL_OUTBOX_POLL_MS='${CANCEL_OUTBOX_POLL_MS:-10000}' CANCEL_OUTBOX_BATCH_SIZE='${CANCEL_OUTBOX_BATCH_SIZE:-1000}'
cd /opt/loadtest/repo/infra/load-test/deploy
docker compose -f '${file}' ${extra} pull
docker compose -f '${file}' ${extra} up -d
# 호스트 지표: node-exporter (관측용, 항상)
docker compose -f ../observability/node-exporter.compose.yml up -d >/dev/null 2>&1 || true
docker compose -f '${file}' ${extra} ps"
  ssm_run "$role" "$remote" || true
done

# ── 관측 스택 (obs 인스턴스: Prometheus + Grafana + exporters) + obs 호스트 node-exporter ──
if [ "$DEPLOY_OBS" = "1" ]; then
  echo "── [obs] 관측 스택(Prometheus/Grafana/exporters) + node-exporter ──"
  obs_remote="$(clone_header)
cd /opt/loadtest/repo/infra/load-test/observability
docker compose up -d
docker compose -f node-exporter.compose.yml up -d
docker compose ps"
  ssm_run "obs" "$obs_remote" || echo "  (obs 인스턴스가 없으면 terraform apply 후 재실행)"
fi

echo
echo "── 확인 방법 (SSM 포트포워딩으로 노트북 브라우저에서) ──"
cat <<'HC'
  ./infra/load-test/deploy/port-forward.sh grafana   # http://localhost:3000  (대시보드)
  ./infra/load-test/deploy/port-forward.sh kafka      # http://localhost:8989  (consumer lag)
  ./infra/load-test/deploy/port-forward.sh prometheus # http://localhost:9090  (Targets)
  헬스: ./port-forward.sh payment → curl localhost:8080/actuator/health
  로그: LOG_CLOUDWATCH=1 로 배포했다면 콘솔 CloudWatch Logs Insights (/loadtest/apps)
HC
