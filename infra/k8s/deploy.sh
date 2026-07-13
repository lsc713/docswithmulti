#!/usr/bin/env bash
# 클러스터 매니페스트 순서 배포 (server 노드에서 실행). Strimzi operator는 사전 설치 전제.
set -euo pipefail
K="sudo k3s kubectl"
BASE="$(cd "$(dirname "$0")" && pwd)"

$K apply -f "$BASE/kafka/strimzi-kafka.yaml"
$K -n kafka wait kafka/cancel-kafka --for=condition=Ready --timeout=600s

$K apply -f "$BASE/redis/redis.yaml" -f "$BASE/apps/config.yaml"
$K rollout status deploy/redis --timeout=120s

$K apply -f "$BASE/apps/payment.yaml" -f "$BASE/apps/risk.yaml" \
         -f "$BASE/apps/merchant-limit.yaml" -f "$BASE/apps/order.yaml"
$K rollout status deploy/payment deploy/risk deploy/merchant-limit deploy/order --timeout=300s
$K get pods -o wide
