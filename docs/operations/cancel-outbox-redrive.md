# Cancel outbox redrive operator guide

이 절차는 `DEAD` 상태인 결제 취소 outbox를 점검하고, 안전 조건을 만족할 때만 같은 이벤트를 다시 발행합니다. 모든 내부 API 호출에는 관리자 역할과 작업자 식별자가 필요합니다.

## 1. 작업 변수 설정

```bash
PAYMENT_BASE_URL=http://localhost:8080
OUTBOX_ID=123
OPERATOR_ID=ops@example.com
```

`OPERATOR_ID`에는 실제 작업자를 식별할 수 있는 값을 사용합니다. 인증 토큰, 이벤트 payload, payment key는 셸 히스토리나 티켓에 남기지 않습니다.

## 2. 원본 outbox 점검

```bash
curl --fail --silent --show-error \
  -H "X-User-Role: ADMIN" \
  -H "X-User-Id: ${OPERATOR_ID}" \
  "${PAYMENT_BASE_URL}/internal/cancel-outbox/${OUTBOX_ID}" | jq
```

응답의 `decision`과 `reasonCode`, `order`, `stock`만 확인합니다. 이 API는 replay payload와 payment key를 반환하지 않습니다.

## 3. redrive 요청

사유에는 payload나 payment key를 넣지 말고 작업 목적만 기록합니다.

```bash
REDRIVE_RESPONSE=$(curl --fail --silent --show-error \
  -X POST \
  -H "X-User-Role: ADMIN" \
  -H "X-User-Id: ${OPERATOR_ID}" \
  -H "Content-Type: application/json" \
  -d '{"reason":"consumer 장애 복구 후 취소 이벤트 재발행"}' \
  "${PAYMENT_BASE_URL}/internal/cancel-outbox/${OUTBOX_ID}/redrives")

printf '%s\n' "${REDRIVE_RESPONSE}" | jq
REDRIVE_ID=$(printf '%s\n' "${REDRIVE_RESPONSE}" | jq -er '.redriveId')
```

POST는 작업을 접수한 뒤 HTTP 202와 `REQUESTED` 상태를 반환합니다.

## 4. 2초 간격으로 상태 확인

```bash
while true; do
  REDRIVE_RESPONSE=$(curl --fail --silent --show-error \
    -H "X-User-Role: ADMIN" \
    -H "X-User-Id: ${OPERATOR_ID}" \
    "${PAYMENT_BASE_URL}/internal/cancel-outbox/redrives/${REDRIVE_ID}")

  printf '%s\n' "${REDRIVE_RESPONSE}" | jq
  REDRIVE_STATUS=$(printf '%s\n' "${REDRIVE_RESPONSE}" | jq -r '.status')

  case "${REDRIVE_STATUS}" in
    RESOLVED|RESOLVED_ALREADY_APPLIED|REJECTED)
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
- `REDRIVING`: 사전 점검, broker ACK 대기, 또는 최대 60초의 수렴 관찰 중입니다.

`REDRIVING`이 60초를 넘으면 자동으로 다시 요청하거나 payload를 수동 발행하지 않습니다. 이 경우 수동 조사 대상으로 전환하고, publish/convergence 실패의 최종 상태 처리와 복구 정책을 제공할 issue #108이 반영될 때까지 원본 outbox와 redrive 행을 보존합니다.

운영 로그와 장애 티켓에는 redrive ID, source outbox ID, 상태, reason code만 기록합니다. 원본 payload와 payment key는 복사하지 않습니다.
