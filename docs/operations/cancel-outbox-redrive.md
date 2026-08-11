# Cancel outbox redrive operator guide

이 절차는 `DEAD` 상태인 결제 취소 outbox를 점검하고, 안전 조건을 만족할 때만 같은 이벤트를 다시 발행합니다. 모든 내부 API 호출에는 관리자 역할과 작업자 식별자가 필요합니다.

## 0. 로컬 자동 smoke fixture

운영 명령을 사용하기 전에 다음 테스트로 전체 흐름을 재현할 수 있습니다. 테스트는 Testcontainers의 임시 MySQL과 Kafka를 클래스 전체에서 한 번씩 사용하고, 유효한 `DEAD` 원본 행을 자동으로 시드한 뒤 실제 HTTP 인증·컨트롤러·직렬화와 dispatcher/convergence를 통과합니다. 로컬 DB의 기존 데이터는 변경하지 않습니다.

```bash
./gradlew :payment-service:test \
  --tests 'com.example.payment.integration.CancelOutboxRedriveWorkerIT.operatorHttpFlowInspectsRequestsDispatchesAndReadsResolvedJsonObjects'
```

실행 중인 로컬 payment DB에서 수동 절차를 확인하려면 기존 `DEAD` 행을 안전한 필드만 조회해 선택합니다. 비밀번호는 명령행에 쓰지 않고 프롬프트에서 입력합니다.

```bash
docker compose exec mysql-payment \
  mysql --user=payment --password payment_db \
  --execute="SELECT id, status, retry_count, created_at
               FROM cancel_event_outbox
              WHERE status = 'DEAD'
              ORDER BY created_at DESC
              LIMIT 20;"
```

조회한 `id`를 아래 `OUTBOX_ID`에 사용합니다. 안전 조건을 우회하기 위해 `PENDING` 또는 `PUBLISHED` 행을 SQL로 `DEAD`로 바꾸지 않습니다. 사용할 `DEAD` 행이 없다면 위 자동 smoke fixture를 사용하거나, 로컬 환경의 정상 outbox 실패 처리로 생성된 행을 기다립니다.

## 1. 작업 변수 설정

```bash
PAYMENT_BASE_URL=http://localhost:8080
OUTBOX_ID=123
OPERATOR_ID=ops@example.com
```

`OPERATOR_ID`에는 실제 작업자를 식별할 수 있는 값을 사용합니다. 인증 토큰, 이벤트 payload, payment key는 셸 히스토리나 티켓에 남기지 않습니다.

## 2. 원본 outbox 점검

```bash
inspect_cancel_outbox() {
  if ! INSPECTION_RESPONSE=$(curl --fail --silent --show-error \
    --connect-timeout 3 --max-time 10 \
    -H "X-User-Role: ADMIN" \
    -H "X-User-Id: ${OPERATOR_ID}" \
    "${PAYMENT_BASE_URL}/internal/cancel-outbox/${OUTBOX_ID}"); then
    printf '%s\n' "outbox 점검 요청에 실패했습니다. 작업을 중단하세요." >&2
    return 1
  fi

  printf '%s\n' "${INSPECTION_RESPONSE}" |
    jq '{outboxId, decision, reasonCode, order, stock}'
}

inspect_cancel_outbox
```

응답의 `decision`과 `reasonCode`, `order`, `stock`만 확인합니다. 이 API는 replay payload와 payment key를 반환하지 않습니다.

## 3. redrive 요청

사유에는 payload나 payment key를 넣지 말고 작업 목적만 기록합니다.

```bash
request_cancel_redrive() {
  if ! REDRIVE_RESPONSE=$(curl --fail --silent --show-error \
    --connect-timeout 3 --max-time 10 \
    -X POST \
    -H "X-User-Role: ADMIN" \
    -H "X-User-Id: ${OPERATOR_ID}" \
    -H "Content-Type: application/json" \
    -d '{"reason":"consumer 장애 복구 후 취소 이벤트 재발행"}' \
    "${PAYMENT_BASE_URL}/internal/cancel-outbox/${OUTBOX_ID}/redrives"); then
    printf '%s\n' "redrive 요청에 실패했습니다. 상태 조회를 시작하지 마세요." >&2
    return 1
  fi

  printf '%s\n' "${REDRIVE_RESPONSE}" |
    jq '{redriveId, status, requestedBy, reason, requestedAt}' || return 1
  REDRIVE_ID=$(printf '%s\n' "${REDRIVE_RESPONSE}" | jq -er '.redriveId') || return 1
}

request_cancel_redrive
```

POST는 작업을 접수한 뒤 HTTP 202와 `REQUESTED` 상태를 반환합니다.

## 4. 2초 간격으로 상태 확인

```bash
REDRIVE_DEADLINE_EPOCH=$(( $(date +%s) + 60 ))

while :; do
  REDRIVE_NOW_EPOCH=$(date +%s)
  if [ "${REDRIVE_NOW_EPOCH}" -ge "${REDRIVE_DEADLINE_EPOCH}" ]; then
    printf '%s\n' \
      "REDRIVING이 60초 안에 수렴하지 않았습니다. 자동 폴링을 중단하고 수동 조사하세요."
    break
  fi
  REDRIVE_REMAINING_SECONDS=$(( REDRIVE_DEADLINE_EPOCH - REDRIVE_NOW_EPOCH ))

  if ! REDRIVE_RESPONSE=$(curl --fail --silent --show-error \
    --connect-timeout 3 --max-time "${REDRIVE_REMAINING_SECONDS}" \
    -H "X-User-Role: ADMIN" \
    -H "X-User-Id: ${OPERATOR_ID}" \
    "${PAYMENT_BASE_URL}/internal/cancel-outbox/redrives/${REDRIVE_ID}"); then
    printf '%s\n' "redrive 상태 조회에 실패했습니다. 자동 폴링을 중단하세요." >&2
    break
  fi

  printf '%s\n' "${REDRIVE_RESPONSE}" |
    jq '{redriveId, sourceOutboxId, status, failureStage, lastError,
         requestedBy, reason, requestedAt, startedAt, completedAt,
         result, beforeState, afterState}' || break
  REDRIVE_STATUS=$(printf '%s\n' "${REDRIVE_RESPONSE}" | jq -er '.status') || break

  case "${REDRIVE_STATUS}" in
    RESOLVED|RESOLVED_ALREADY_APPLIED|REJECTED|FAILED)
      break
      ;;
  esac

  sleep 2
done
```

상태 의미:

- `RESOLVED`: 이벤트 발행 ACK를 저장했고 order와 stock 모두 `APPLIED`로 수렴했습니다.
- `RESOLVED_ALREADY_APPLIED`: 발행 전 점검에서 이미 양쪽이 적용된 것을 확인해 Kafka에는 발행하지 않았습니다.
- `REJECTED`: 원본 또는 하위 시스템 상태가 안전한 재발행 조건을 만족하지 않아 발행하지 않았습니다. `lastError`의 안전한 reason code를 확인합니다.
- `FAILED`: `failureStage`와 `lastError`를 함께 확인합니다. 동일 source outbox에 새 요청을 만들 수 있지만, 아래 표의 점검을 마치기 전에는 자동 재요청하지 않습니다.
- `REDRIVING`: 사전 점검, broker ACK 대기, 또는 최대 60초의 수렴 관찰 중입니다.

`REDRIVING`이 60초를 넘으면 자동으로 다시 요청하거나 payload를 수동 발행하지 않습니다. recovery poller가 `startedAt` 기준 정확히 60초가 된 행을 최종 처리하므로 다음 상태 조회에서 `FAILED` 또는 `RESOLVED`를 확인합니다. executor 포화로 recovery 작업이 거절되면 행은 그대로 남고 다음 recovery polling 주기에 다시 선택됩니다.

운영 로그와 장애 티켓에는 redrive ID, source outbox ID, 상태, reason code만 기록합니다. 원본 payload와 payment key는 복사하지 않습니다.

## 5. 종료 상태 해석과 안전한 다음 작업

CLI 응답에서는 `status`, `failureStage`, `lastError` 조합을 그대로 사용합니다. `failureStage`나 `lastError`가 `null`인 경우 임의의 기본값으로 해석하지 않습니다.

| 상태 조합 | 의미 | 안전한 다음 작업 |
| --- | --- | --- |
| `FAILED / PUBLISH / PREFLIGHT_UNKNOWN` | 발행 전 order 또는 stock 점검이 `UNKNOWN`이어서 Kafka send를 호출하지 않았습니다. | 하위 점검 API의 가용성을 복구하고 2절을 다시 실행합니다. `REDRIVE_REQUIRED`가 확인될 때만 새 redrive를 요청합니다. |
| `FAILED / PUBLISH / KAFKA_TIMEOUT` | Kafka future가 제한 시간 안에 완료되지 않았습니다. broker가 이벤트를 받았는지는 이 상태만으로 단정할 수 없습니다. | consumer 적용 상태와 broker 장애를 먼저 확인하고 2절을 다시 실행합니다. 수동 Kafka 발행은 하지 않습니다. |
| `FAILED / PUBLISH / KAFKA_SEND_FAILED` | Kafka future가 예외로 완료되었거나 send 단계가 실패했습니다. | producer/broker 오류를 복구한 뒤 하위 적용 상태를 다시 점검합니다. 새 요청은 점검 결과가 `REDRIVE_REQUIRED`일 때만 만듭니다. |
| `FAILED / PUBLISH / PUBLISH_STATE_UNKNOWN` | broker ACK 뒤 DB의 ACK 저장 전에 프로세스가 중단됐거나, ACK 없는 `REDRIVING` 행이 정확히 60초에 도달했습니다. 이벤트는 이미 전달됐을 수 있습니다. | **반드시 inspect-before-retry를 수행합니다.** 2절에서 양쪽이 `APPLIED`면 재발행하지 않습니다. `REDRIVE_REQUIRED`여도 consumer의 중복 처리 보장을 확인한 뒤 새 reason으로 요청합니다. |
| `FAILED / CONVERGENCE / CONVERGENCE_TIMEOUT` | ACK는 저장됐지만 정확히 60초의 최종 점검에서도 한쪽 이상이 `NOT_APPLIED`였습니다. | `afterState`의 안전한 상태·수량 증거로 미적용 consumer를 조사합니다. 동일 이벤트 재요청은 자동화하지 않습니다. |
| `FAILED / CONVERGENCE / DOWNSTREAM_UNKNOWN` | ACK는 저장됐지만 최종 점검이 `UNKNOWN`이거나 점검 호출이 실패했습니다. | 하위 점검 가용성을 복구한 뒤 2절을 다시 실행합니다. 현재 결과만으로 재발행 여부를 결정하지 않습니다. |
| `FAILED / CONVERGENCE / INCONSISTENT_DOWNSTREAM_STATE` | ACK 뒤 최종 점검에서 수량 또는 상태 불일치가 확인됐습니다. | 수동 정합성 복구 대상으로 전환하고 재발행하지 않습니다. `afterState`의 안전한 증거만 티켓에 남깁니다. |
| `RESOLVED_ALREADY_APPLIED / - / -` | preflight에서 양쪽이 이미 `APPLIED`여서 Kafka 발행 없이 종료했습니다. | 추가 작업이 없습니다. 이 attempt ID를 변경 이력에 남깁니다. |
| `REJECTED / - / INVALID_PAYLOAD` | 저장된 원본 이벤트가 필수 필드, source ID 일치, item 형식 검증을 통과하지 못했습니다. | payload를 CLI로 조회·복사·수정하지 않습니다. 생성 경로와 원본 데이터 문제를 별도 장애로 조사합니다. |
| `REJECTED / - / 그 외 code` | 원본이 `DEAD`가 아니거나 취소·결제 상태 또는 하위 상태가 안전 조건을 만족하지 않습니다. | `lastError`에 해당하는 원본/하위 상태를 수정 또는 확인하고, 안전 점검 전에는 재요청하지 않습니다. |

새 요청은 새 redrive ID를 생성합니다. 이전 `FAILED` attempt를 덮어쓰지 않으므로 장애 티켓에는 모든 attempt ID와 각 reason을 시간순으로 남깁니다.

## 6. executor 포화와 recovery 확인

redrive dispatcher, convergence poller, recovery poller는 queue가 없는 최대 5개 공용 executor를 사용합니다. 다음 Micrometer meter를 대시보드에서 함께 확인합니다.

- `payment.cancel.redrive.executor.active`: 실행 중인 작업 수입니다. 지속적으로 `5`이면 포화 상태입니다.
- `payment.cancel.redrive.executor.rejected.total`: 빈 slot 없이 제출돼 거절된 누적 작업 수입니다. 증가량으로 포화를 경보합니다.
- `payment.cancel.redrive.terminal.total{status="FAILED",failure_stage="PUBLISH|CONVERGENCE"}`: 단계별 최종 실패 수입니다.

queue가 없으므로 여섯 번째 요청이나 recovery 작업은 executor 밖에 적재되지 않습니다. DB 상태가 `REQUESTED` 또는 만료된 `REDRIVING`으로 유지되어 다음 polling 주기에 다시 선택됩니다. 포화 중에는 SQL로 상태를 바꾸거나 스케줄러를 동시에 수동 호출하지 말고, active가 내려간 뒤 정상 polling으로 해당 ID가 시작 또는 최종 처리되는지 4절의 60초 제한 안에서 확인합니다.

## 7. ACK 저장 crash window와 중복 이벤트 전제

Kafka ACK 수신과 `cancel_outbox_redrive.result` 저장은 하나의 원자 트랜잭션이 아닙니다. ACK 직후 상태 저장이 실패하면 첫 attempt는 `REDRIVING/result=null`로 남았다가 정확히 60초에 `FAILED/PUBLISH/PUBLISH_STATE_UNKNOWN`이 됩니다. inspect-before-retry 뒤 새 attempt를 만들면 원본 `DEAD` 행은 변경하지 않은 채 동일한 Kafka key와 payload가 다시 발행될 수 있습니다.

따라서 이 복구 절차는 order와 product consumer가 cancel request 기준으로 중복 이벤트를 idempotent no-op 처리한다는 보장에 의존합니다. 해당 회귀 테스트가 통과하지 않는 배포에서는 `PUBLISH_STATE_UNKNOWN`을 재시도하지 않고 수동 정합성 복구로 전환합니다. 운영 명령, 로그, 티켓에는 payload나 payment key를 출력하거나 요청하지 않습니다.
