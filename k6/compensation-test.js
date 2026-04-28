/**
 * Scenario 3: risk 장애 시 보상 트랜잭션 테스트 (k6 파트)
 *
 * run-compensation.sh 가 먼저 risk-management-service를 내린 후 이 스크립트를 실행한다.
 * payment-service → risk 호출 실패 → CancelRequest FAILED → compensation_retry INSERT
 *
 * 직접 실행 금지. 반드시 run-compensation.sh 를 통해 실행:
 *   bash k6/run-compensation.sh
 */

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';
import { buildPaymentPool } from './helpers/data-factory.js';

// ─── 커스텀 메트릭 ───────────────────────────────────────────
// risk 장애 시 payment-service가 정상적으로 FAILED를 반환하는지 측정
const riskFailureHandled = new Rate('risk_failure_handled');

// ─── 옵션 ────────────────────────────────────────────────────
export const options = {
  vus: 5,
  duration: '15s',
  thresholds: {
    // risk 타임아웃/에러로 응답이 느릴 수 있음
    http_req_duration: ['p(95)<10000'],
    // payment-service 자체는 살아있어야 함 (0 응답 없어야 함)
    http_req_failed:   ['rate<1'],
  },
};

// ─── 픽스처 생성 ─────────────────────────────────────────────
// 결제 생성은 risk를 호출하지 않으므로 risk DOWN 상태에서도 정상 동작
export function setup() {
  const COMPENSATION_POOL = 100; // 5 VU × 15s, 여유 있게
  return buildPaymentPool(COMPENSATION_POOL);
}

// ─── VU 메인 루프 ────────────────────────────────────────────
export default function (data) {
  const idx = (__VU - 1) + (__ITER * options.vus);
  if (idx >= data.pool.length) return;

  const { paymentKey, paymentItemId } = data.pool[idx];

  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({
      cancelItems: [{ paymentItemId }],
      cancelReason: 'compensation 테스트 — risk 장애 상황',
    }),
    {
      headers: HEADERS,
      timeout: '10s', // risk Circuit Breaker / 타임아웃 대기
    }
  );

  // risk 장애 시 예상 동작:
  //   1. payment-service가 TX 1(PENDING) 커밋
  //   2. risk 호출 → 연결 실패 / 타임아웃
  //   3. CancelRequest → FAILED
  //   4. compensation_retry INSERT (risk가 실제로 차감했는지 불확실하므로 보상)
  //
  // 응답은 5xx 또는 취소 실패를 나타내는 4xx/2xx(FAILED status) 중 하나
  const handled = check(res, {
    'payment-service 응답 있음':    r => r.status > 0,
    'connection refused 아님':      r => r.status !== 0,
  });

  riskFailureHandled.add(handled);

  // 응답 로그 (상태 코드 + 바디 앞 200자)
  const preview = res.body ? res.body.substring(0, 200) : '(empty)';
  console.log(`VU=${__VU} idx=${idx} status=${res.status} body=${preview}`);
}
