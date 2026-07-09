/**
 * 축 A — 처리량 스테이지 (분산 payment, 무경합)
 *
 * measurement-journey.md §3 축 A 의 S0~S4 를 STAGE 로 선택 실행.
 * 시딩된 payment pool(k6/seed/paymentKeys.json)을 각 취소가 고유 슬롯으로 소비.
 *
 * 실행:
 *   ./k6/seed/seed.sh                                # 먼저 시딩
 *   k6 run --env STAGE=baseline k6/stages.js
 *   K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
 *     k6 run -o experimental-prometheus-rw --env TARGET=aws --env STAGE=ramp k6/stages.js
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

// 시딩된 pool — SharedArray 로 VU 간 메모리 공유 (수만 건도 1회 파싱)
const pool = new SharedArray('payments', () => JSON.parse(open('./seed/paymentKeys.json')));

const STAGE = __ENV.STAGE || 'baseline';

const cancelSuccess = new Rate('cancel_success_rate');
const cancelDuration = new Trend('cancel_duration_ms', true);

// 스테이지별 executor (measurement-journey §3 축 A)
const SCENARIOS = {
  // S0 smoke: 워밍업(JIT/pool) + 정합성
  smoke: { executor: 'shared-iterations', vus: 1, iterations: 20, maxDuration: '2m' },
  // S1 baseline: 무경합 기준선
  baseline: { executor: 'constant-vus', vus: 10, duration: '3m' },
  // S2 ramp: knee 탐색
  ramp: {
    executor: 'ramping-vus', startVUs: 10, gracefulRampDown: '10s',
    stages: [
      { target: 10, duration: '3m' },
      { target: 50, duration: '3m' },
      { target: 100, duration: '3m' },
    ],
  },
  // S3 stress: breaking point (abort 안 함 — 붕괴 관측이 목적)
  stress: {
    executor: 'ramping-vus', startVUs: 50, gracefulRampDown: '10s',
    stages: [
      { target: 100, duration: '2m' },
      { target: 200, duration: '2m' },
      { target: 400, duration: '2m' },
    ],
  },
  // S4 soak: knee 70% 지속 → 누수/pool 고갈/GC 추세
  soak: { executor: 'constant-vus', vus: 70, duration: '30m' },
};

const scenario = SCENARIOS[STAGE];
if (!scenario) throw new Error(`알 수 없는 STAGE=${STAGE}. 가능: ${Object.keys(SCENARIOS).join(', ')}`);

// baseline/smoke 만 엄격 thresholds. ramp/stress/soak 은 관측 목적이라 abort 안 함.
const strict = STAGE === 'baseline' || STAGE === 'smoke';

export const options = {
  scenarios: { [STAGE]: { ...scenario, exec: 'cancel', tags: { stage: STAGE } } },
  thresholds: strict
    ? {
        [`http_req_duration{stage:${STAGE}}`]: ['p(99)<1000'],
        http_req_failed: ['rate<0.01'],
        cancel_success_rate: ['rate>0.99'],
      }
    : {
        // 관측만 — abortOnFail 없음 (붕괴 지점까지 진행)
        http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
      },
};

export function cancel() {
  // 전역 단조 인덱스 → 램프에서도 각 취소가 고유 payment 소비 (이중취소 방지)
  const idx = exec.scenario.iterationInTest;
  if (idx >= pool.length) {
    exec.test.abort(`payment pool 소진 (${pool.length}건). SEED_COUNT 늘려 재시딩 필요.`);
    return;
  }
  const { paymentKey, paymentItemId } = pool[idx];

  const start = Date.now();
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({ cancelItems: [{ paymentItemId }], cancelReason: 'k6 부하 스테이지' }),
    { headers: HEADERS, tags: { stage: STAGE } }
  );
  cancelDuration.add(Date.now() - start);

  const ok = check(res, {
    'HTTP 200': (r) => r.status === 200,
    'status COMPLETED': (r) => {
      try { return JSON.parse(r.body).status === 'COMPLETED'; } catch { return false; }
    },
  });
  cancelSuccess.add(ok);
  if (!ok) {
    console.log(`[실패] stage=${STAGE} idx=${idx} key=${paymentKey} status=${res.status} body=${res.body}`);
  }
}
