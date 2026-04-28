/**
 * Scenario 1: 정상 취소 플로우 부하 테스트
 *
 * 목표: TPS 100, p99 < 1초
 * 설정: VU 50명, 30초
 *
 * 실행:
 *   k6 run k6/load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { BASE, HEADERS, POOL_SIZE } from './config.js';
import { buildPaymentPool } from './helpers/data-factory.js';

// ─── 커스텀 메트릭 ───────────────────────────────────────────
const cancelSuccess = new Rate('cancel_success_rate');
const cancelDuration = new Trend('cancel_duration_ms', true);

// ─── 옵션 ────────────────────────────────────────────────────
export const options = {
  vus: 50,
  duration: '30s',
  thresholds: {
    // 목표: p99 < 1초
    http_req_duration:   ['p(99)<1000'],
    // 에러율 1% 미만
    http_req_failed:     ['rate<0.01'],
    // 취소 성공률 99% 이상
    cancel_success_rate: ['rate>0.99'],
  },
};

// ─── 테스트 데이터 준비 (1회) ────────────────────────────────
export function setup() {
  const data = buildPaymentPool(POOL_SIZE);
  // CB waitDurationInOpenState(10초) 이후 HALF_OPEN → CLOSED 전환 대기
  console.log('[setup] CB 초기화 대기 (10초)...');
  sleep(10);
  return data;
}

// ─── VU 메인 루프 ────────────────────────────────────────────
export default function (data) {
  // 각 VU가 겹치지 않는 pool 슬롯을 사용
  // VU는 1-indexed, ITER는 0-indexed
  // VU1/ITER0=0, VU2/ITER0=1, ..., VU50/ITER0=49
  // VU1/ITER1=50, VU2/ITER1=51, ...
  const idx = (__VU - 1) + (__ITER * options.vus);
  if (idx >= data.pool.length) {
    // pool 소진: 30초 내에 모든 슬롯이 사용된 경우
    return;
  }

  const { paymentKey, paymentItemId } = data.pool[idx];

  const start = Date.now();
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({
      cancelItems: [{ paymentItemId }],
      cancelReason: 'k6 부하 테스트',
    }),
    { headers: HEADERS }
  );
  cancelDuration.add(Date.now() - start);

  // 실패 시 로그 추가
  if (res.status !== 200) {
    console.log(`[실패] VU=${__VU} ITER=${__ITER} idx=${idx} paymentKey=${paymentKey} status=${res.status} body=${res.body}`);
  }
  
  const ok = check(res, {
    'HTTP 200':           r => r.status === 200,
    'cancelRequestId 존재': r => {
      try { return !!JSON.parse(r.body).cancelRequestId; }
      catch { return false; }
    },
    'status COMPLETED':  r => {
      try { return JSON.parse(r.body).status === 'COMPLETED'; }
      catch { return false; }
    },
  });

  cancelSuccess.add(ok);
}
