#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# SSM 원격 배포 드라이버 — terraform apply 로 뜬 9대에 컨테이너를 순서대로 올린다.
#   인터랙티브 SSM 세션 없이 `aws ssm send-command` 로 role 태그별 일괄 실행.
#   앱 이미지는 Docker Hub(public) 에서 pull — 호스트 빌드/소스 불필요.
#   compose yml 만 public repo 에서 clone.
#
# 사용:
#   IMAGE_NS=<dockerhub-user> ./ssm-deploy.sh              # 전체(인프라→DB→앱)
#   IMAGE_NS=<user> ROLES="payment risk" ./ssm-deploy.sh   # 특정 role 만
#
# 환경변수:
#   IMAGE_NS   (필수) Docker Hub 네임스페이스 = 사용자명. 이미지 <NS>/cancel-loadtest:<tag>
#   IMAGE_TAG  (기본 latest) 배포할 태그 접미(예: <sha>). 이미지 <tag>-<IMAGE_TAG>
#   REPO_URL   (기본 public repo) compose yml 을 가져올 git URL
#   AWS_REGION (기본 ap-northeast-2)
# ─────────────────────────────────────────────────────────────
set -euo pipefail

IMAGE_NS="${IMAGE_NS:?Docker Hub 사용자명 필요. 예: IMAGE_NS=myuser ./ssm-deploy.sh}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REPO_URL="${REPO_URL:-https://github.com/lsc713/docswithmulti.git}"
REGION="${AWS_REGION:-ap-northeast-2}"

# role → compose 파일 (순서 = 배포 순서: 인프라 → DB → 앱)
# macOS 기본 bash 3.2 는 연관배열(declare -A) 미지원 → case 로 매핑.
ORDER="infra mysql-payment mysql-risk cold-db cold-svc risk payment"
compose_for() {
  case "$1" in
    infra)         echo infra.compose.yml ;;
    mysql-payment) echo mysql-payment.compose.yml ;;
    mysql-risk)    echo mysql-risk.compose.yml ;;
    cold-db)       echo cold-db.compose.yml ;;
    cold-svc)      echo cold-svc.compose.yml ;;
    risk)          echo risk.compose.yml ;;
    payment)       echo payment.compose.yml ;;
    *)             echo "" ;;
  esac
}

ROLES="${ROLES:-$ORDER}"

# role 태그를 가진 인스턴스에 명령 전송 후 완료까지 폴링. stdout 출력.
ssm_run() {
  local role="$1" script="$2"
  local iid
  iid=$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Role,Values=${role}" "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].InstanceId' --output text)
  [ -n "$iid" ] || { echo "  ⚠ role=${role} 인스턴스(running) 없음 — 건너뜀"; return 1; }

  # 멀티라인 스크립트를 commands 배열 단일 원소로 안전 전달 (개행 보존).
  # --parameters 에 완전한 JSON 을 넘긴다 (shorthand 는 개행을 뭉갬).
  local params cid
  params=$(jq -n --arg s "$script" '{commands: [$s]}')
  cid=$(aws ssm send-command --region "$REGION" \
    --instance-ids $iid \
    --document-name "AWS-RunShellScript" \
    --comment "loadtest deploy ${role}" \
    --parameters "$params" \
    --query 'Command.CommandId' --output text)

  # 완료 폴링
  while :; do
    local st
    st=$(aws ssm list-command-invocations --region "$REGION" --command-id "$cid" \
      --query 'CommandInvocations[0].Status' --output text 2>/dev/null || echo Pending)
    case "$st" in
      Success) break ;;
      Failed|Cancelled|TimedOut) echo "  ✗ ${role} 배포 실패($st)"; ;;
      *) sleep 5; continue ;;
    esac
    break
  done

  aws ssm list-command-invocations --region "$REGION" --command-id "$cid" --details \
    --query 'CommandInvocations[0].CommandPlugins[0].Output' --output text | sed 's/^/    /'
}

for role in $ROLES; do
  file="$(compose_for "$role")"
  [ -n "$file" ] || { echo "알 수 없는 role: $role"; continue; }
  echo "── [$role] ${file} 배포 ──"

  # 원격 호스트에서 실행할 스크립트 (compose clone → pull → up)
  remote=$(cat <<EOF
set -e
export IMAGE_NS='${IMAGE_NS}' IMAGE_TAG='${IMAGE_TAG}'
mkdir -p /opt/loadtest
if [ ! -d /opt/loadtest/repo/.git ]; then
  git clone --depth 1 '${REPO_URL}' /opt/loadtest/repo
else
  git -C /opt/loadtest/repo pull --ff-only || true
fi
cd /opt/loadtest/repo/infra/load-test/deploy
docker compose -f '${file}' pull
docker compose -f '${file}' up -d
docker compose -f '${file}' ps
EOF
)
  ssm_run "$role" "$remote" || true
done

echo
echo "── 헬스체크 (앱 기동은 DB/Kafka 준비 후 재시도로 수렴, 1~3분 소요) ──"
cat <<'HC'
  payment       : curl http://10.0.1.20:8080/actuator/health
  risk          : curl http://10.0.1.21:8083/actuator/health
  merchant-limit: curl http://10.0.1.22:8082/actuator/health
  order         : curl http://10.0.1.22:8081/actuator/health
  → k6 호스트(10.0.1.10)에서 확인하거나 aws ssm start-session 로 접속.
HC
