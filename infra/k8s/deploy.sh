#!/usr/bin/env bash
# 클러스터 매니페스트 순서 배포 (server 노드에서 실행). Strimzi operator는 사전 설치 전제.
set -euo pipefail
K="sudo k3s kubectl"
BASE="$(cd "$(dirname "$0")" && pwd)"

$K apply -f "$BASE/kafka/strimzi-kafka.yaml"
$K -n kafka wait kafka/cancel-kafka --for=condition=Ready --timeout=600s

$K apply -f "$BASE/redis/redis.yaml" -f "$BASE/apps/config.yaml" -f "$BASE/apps/jwt-secret.yaml"
$K rollout status deploy/redis --timeout=120s

$K apply -f "$BASE/apps/payment.yaml" -f "$BASE/apps/risk.yaml" \
         -f "$BASE/apps/merchant-limit.yaml" -f "$BASE/apps/order.yaml" \
         -f "$BASE/apps/user-service.yaml" -f "$BASE/apps/api-gateway.yaml"
$K rollout status deploy/payment deploy/risk deploy/merchant-limit deploy/order \
                  deploy/user-service deploy/api-gateway --timeout=300s

# 앱 기동 후 NetworkPolicy 적용 — payment:8080을 api-gateway 파드로만 제한(DEPLOY-02).
$K apply -f "$BASE/networkpolicy/payment-ingress.yaml"
$K get pods -o wide
