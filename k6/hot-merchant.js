/**
 * 축 B — 경합: 핫 merchant
 *
 * 시딩 pool 을 단일 merchantId 로 필터 → 모든 취소가 그 가맹점의
 * merchant_cancel_usage 단일 row 를 FOR UPDATE 로 경합.
 * daily_limit:{merchantId} Redis 단일 키 hotspot 도 함께 관측.
 *
 * (같은 payment_id 따닥은 idempotency-test.js — 이건 "같은 merchant, 다른 payment")
 *
 * 실행:
 *   k6 run --env VUS=30 --env DURATION=2m k6/hot-merchant.js
 *   k6 run --env MERCHANT_ID=42 k6/hot-merchant.js   # 특정 가맹점 지정
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

// 단일 merchant 로 필터한 pool (SharedArray 함수는 init 에서 1회 실행)
const pool = new SharedArray('hot-merchant', () => {
  const raw = JSON.parse(open('./seed/paymentKeys.json'));
  const target = __ENV.MERCHANT_ID ? Number(__ENV.MERCHANT_ID) : raw[0].merchantId;
  return raw.filter((p) => p.merchantId === target);
});

const cancelSuccess = new Rate('cancel_success_rate');
const cancelDuration = new Trend('cancel_duration_ms', true);

export const options = {
  scenarios: {
    hotMerchant: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 30),
      duration: __ENV.DURATION || '2m',
      exec: 'cancel',
      tags: { stage: 'hot-merchant' },
    },
  },
  thresholds: {
    // 경합이라 지연은 오르는 게 정상 → abort 안 함, 관측만
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
  },
};

export function cancel() {
  const idx = exec.scenario.iterationInTest;
  if (idx >= pool.length) {
    exec.test.abort(`핫 merchant pool 소진 (${pool.length}건). SEED_COUNT/MERCHANT_COUNT 조정 필요.`);
    return;
  }
  const { paymentKey, paymentItemId } = pool[idx];

  const start = Date.now();
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({ cancelItems: [{ paymentItemId }], cancelReason: 'k6 핫 merchant 경합' }),
    { headers: HEADERS, tags: { stage: 'hot-merchant' } }
  );
  cancelDuration.add(Date.now() - start);

  const ok = check(res, {
    'HTTP 200': (r) => r.status === 200,
    'status COMPLETED': (r) => {
      try { return JSON.parse(r.body).status === 'COMPLETED'; } catch { return false; }
    },
  });
  cancelSuccess.add(ok);
}
