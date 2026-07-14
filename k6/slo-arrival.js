/**
 * 축 SLO — open-model 도착률 스윕 (p95 < 500ms 봉투 탐색)
 *
 * 왜 open-model 인가:
 *   closed-model(VU 고정, stages.js)은 "동시 사용자 N명이 만드는 처리량"을 잰다 —
 *   서버가 느려지면 VU 도 같이 느려져 도착률이 자동으로 줄어든다(음의 피드백).
 *   따라서 "초당 R건이 들어올 때 지연이 어떻게 되나"라는 SLO 질문에는 답하지 못한다.
 *   open-model(constant-arrival-rate)은 서버 상태와 무관하게 도착률 R 을 강제한다 —
 *   서버가 못 따라가면 in-flight 가 쌓여 p95 가 치솟고, VU 풀이 고갈되면 dropped_iterations 로 드러난다.
 *   => "우리 시스템이 p95<500ms 를 지키며 견디는 최대 도착률 R(무릎)" 를 실측한다.
 *
 * 방법:
 *   각 도착률을 별도 constant-arrival-rate 시나리오로 순차 실행(startTime 스태거) →
 *   http_req_duration{rate:R} 로 rate 별 p95 를 분리해 읽는다. 각 취소는 시딩된 pool 의
 *   고유 슬롯을 소비(이중취소 방지) — 시나리오마다 BASE 오프셋을 겹치지 않게 예약.
 *
 * 실행(AWS):
 *   K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
 *     k6 run -o experimental-prometheus-rw --env TARGET=aws k6/slo-arrival.js
 *   (또는 web dashboard: K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=slo.html k6 run ...)
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

const pool = new SharedArray('payments', () => JSON.parse(open('./seed/paymentKeys.json')));

// 스윕 대상 도착률(rps). 필요 시 RATES 오버라이드: --env RATES=80,120,160
const RATES = (__ENV.RATES || '60,100,140,170,190,210').split(',').map((s) => parseInt(s.trim(), 10));
const HOLD = parseInt(__ENV.HOLD || '90', 10); // 각 도착률 유지 시간(초)
const GAP = 10; // 시나리오 간 gracefulStop 여유(초)
const WARMUP_RATE = 30; // JIT/pool 워밍업 도착률
const WARMUP_SEC = 30;

const cancelSuccess = new Rate('cancel_success_rate');
const cancelDuration = new Trend('cancel_duration_ms', true);

// ── 시나리오 + pool 오프셋 예약 ──
// 각 시나리오는 [base, base+rate*dur) 범위의 pool 인덱스만 소비 → 겹침 없음.
const scenarios = {};
const OFFSETS = {}; // scenarioName → pool base index
let cursor = 0;
let clock = 0;

// 워밍업(측정 제외 — thresholds 없음)
scenarios.warmup = {
  executor: 'constant-arrival-rate',
  rate: WARMUP_RATE, timeUnit: '1s', duration: `${WARMUP_SEC}s`,
  preAllocatedVUs: 60, maxVUs: 200,
  startTime: '0s', exec: 'cancel', tags: { rate: 'warmup' },
};
OFFSETS.warmup = cursor;
cursor += WARMUP_RATE * WARMUP_SEC;
clock += WARMUP_SEC + GAP;

// 측정 스테이지
const thresholds = {};
for (const r of RATES) {
  const name = `r${r}`;
  scenarios[name] = {
    executor: 'constant-arrival-rate',
    rate: r, timeUnit: '1s', duration: `${HOLD}s`,
    // maxVUs 를 넉넉히: 서버 포화로 지연이 커져도 도착률을 유지하려면 VU 가 많이 필요.
    // 여기서 막히면(dropped_iterations>0) k6 자체 한계가 아니라 서버 포화의 2차 신호.
    preAllocatedVUs: 100, maxVUs: 800,
    startTime: `${clock}s`, exec: 'cancel', tags: { rate: String(r) },
    gracefulStop: `${GAP}s`,
  };
  OFFSETS[name] = cursor;
  cursor += r * HOLD;
  clock += HOLD + GAP;
  // SLO threshold: 이 rate 에서 p95<500ms. abortOnFail=false → 무릎 넘어서도 전 구간 관측.
  thresholds[`http_req_duration{rate:${r}}`] = [{ threshold: 'p(95)<500', abortOnFail: false }];
  // 관측용(항상 통과) — rate 별 실패율 sub-metric 계산 강제.
  thresholds[`http_req_failed{rate:${r}}`] = [{ threshold: 'rate<1', abortOnFail: false }];
}

// pool 예약 총량 검증(부팅 시 즉시 실패 → 헛런 방지)
if (cursor > pool.length) {
  throw new Error(
    `pool 부족: 예약 ${cursor}건 > 시드 ${pool.length}건. SEED_COUNT 늘려 재시딩하거나 RATES/HOLD 축소.`
  );
}

export const options = { scenarios, thresholds, discardResponseBodies: false };

export function cancel() {
  const base = OFFSETS[exec.scenario.name];
  const idx = base + exec.scenario.iterationInTest;
  if (idx >= pool.length) {
    // 오프셋 예약이 맞으면 도달 불가. 방어적으로만.
    exec.test.abort(`pool 소진 idx=${idx} >= ${pool.length}`);
    return;
  }
  const { paymentKey, paymentItemId } = pool[idx];

  // 태그를 여기서 덮어쓰지 않는다 — 시나리오 레벨 tags:{rate} 가 이 시나리오의
  // 모든 http_req_* 샘플에 자동 전파되어 http_req_duration{rate:R} sub-metric 을 채운다.
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({ cancelItems: [{ paymentItemId }], cancelReason: 'k6 SLO 도착률' }),
    { headers: HEADERS }
  );
  cancelDuration.add(res.timings.duration);
  const ok = check(res, {
    'HTTP 200': (r) => r.status === 200,
    'status COMPLETED': (r) => {
      try { return JSON.parse(r.body).status === 'COMPLETED'; } catch { return false; }
    },
  });
  cancelSuccess.add(ok);
}

// rate 별 p95/p99/실패율/처리량을 컴팩트 JSON + 표로 요약(무릎 판독용).
export function handleSummary(data) {
  const rows = [];
  for (const r of RATES) {
    const dur = data.metrics[`http_req_duration{rate:${r}}`]?.values || {};
    const fail = data.metrics[`http_req_failed{rate:${r}}`]?.values || {};
    rows.push({
      rate: r,
      count: dur.count ?? 0,
      p50: round(dur.med),
      p95: round(dur['p(95)']),
      p99: round(dur['p(99)']),
      max: round(dur.max),
      fail_pct: round((fail.rate ?? 0) * 100),
      slo_ok: (dur['p(95)'] ?? Infinity) < 500,
    });
  }
  const dropped = data.metrics.dropped_iterations?.values?.count ?? 0;
  const summary = { dropped_iterations: dropped, stages: rows };

  let t = '\n=== SLO 도착률 스윕 (p95<500ms 봉투) ===\n';
  t += 'rate  count    p50    p95    p99    max   fail%  SLO\n';
  for (const s of rows) {
    t += `${pad(s.rate, 4)}  ${pad(s.count, 6)}  ${pad(s.p50, 5)}  ${pad(s.p95, 5)}  ${pad(s.p99, 5)}  ${pad(s.max, 5)}  ${pad(s.fail_pct, 5)}  ${s.slo_ok ? 'OK' : 'XX'}\n`;
  }
  t += `dropped_iterations(전체): ${dropped}\n`;
  return {
    stdout: t,
    '/opt/loadtest/repo/k6/slo-result.json': JSON.stringify(summary, null, 2),
  };
}
function round(v) { return v == null ? null : Math.round(v * 10) / 10; }
function pad(v, w) { return String(v ?? '-').padStart(w); }
