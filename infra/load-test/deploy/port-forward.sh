#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# SSM 포트포워딩 — 퍼블릭 IP 없이 사설 인스턴스 포트를 노트북 localhost 로.
#   퍼블릭 IP·SSH·터널 없이 브라우저로 Grafana/Kafka UI/actuator 확인.
#
# 사용:  ./port-forward.sh grafana        # http://localhost:3000
#        ./port-forward.sh kafka          # http://localhost:8989
#        LOCAL_PORT=13000 ./port-forward.sh grafana   # 로컬 포트 변경
#
# 전제:  aws cli + session-manager-plugin 설치.
#   설치: https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
# ─────────────────────────────────────────────────────────────
set -euo pipefail
REGION="${AWS_REGION:-ap-northeast-2}"
LOAD_TEST_PROFILE="${LOAD_TEST_PROFILE:-full}"
what="${1:-}"

case "$what" in
  grafana)        role=obs;      rport=3000 ;;  # 대시보드 (admin/admin, 익명 Viewer)
  prometheus)     if [ "$LOAD_TEST_PROFILE" = "product" ]; then role=k6; else role=obs; fi; rport=9090 ;;
  kafka|kafka-ui) role=infra;    rport=8989 ;;  # consumer lag / 토픽
  payment)        role=payment;  rport=8080 ;;  # /actuator/health, /actuator/prometheus
  product)        role=product;  rport=8084 ;;
  product-db)     role=mysql-product; rport=3306 ;;
  risk)           role=risk;     rport=8083 ;;
  merchant-limit) role=cold-svc; rport=8082 ;;
  order)          role=cold-svc; rport=8081 ;;
  *) echo "사용: $0 {grafana|prometheus|kafka|payment|product|product-db|risk|merchant-limit|order}"; exit 1 ;;
esac
lport="${LOCAL_PORT:-$rport}"

command -v session-manager-plugin >/dev/null || {
  echo "session-manager-plugin 필요: https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html"; exit 1; }

iid=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Role,Values=$role" "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)
[ -n "$iid" ] && [ "$iid" != "None" ] || { echo "role=$role 인스턴스(running) 없음 — terraform apply 됐나?"; exit 1; }

echo "▶ $what : http://localhost:${lport}  ←  ${role}(${iid}):${rport}   (Ctrl-C 로 종료)"
exec aws ssm start-session --region "$REGION" --target "$iid" \
  --document-name AWS-StartPortForwardingSession \
  --parameters "{\"portNumber\":[\"${rport}\"],\"localPortNumber\":[\"${lport}\"]}"
