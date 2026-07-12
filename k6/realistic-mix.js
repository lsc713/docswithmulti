/*
 * 현실적 취소 믹스 — "220rps 균등"이 현실 편중/재히트에선 얼마인지 재측정.
 *
 * 구성 (효과 순):
 *   ① 핫 merchant 편중  — 취소가 소수 가맹점에 몰림 (usage 행 경합). ← SEED가 만듦(파레토)
 *   ② 재요청(멱등/충돌)  — 앞 키를 전체 items로 재요청. 타깃이 전체취소였으면 같은 request_hash→dedup(3쿼리),
 *                          부분취소였으면 hash 다름+이미취소 아이템→거부(이미-취소 충돌). 둘 다 현실 사용자 행동.  ← 주사위
 *   ③ 부분취소           — 다중아이템 중 일부만 취소.                   ← 여기서 주사위(다중아이템 시드 필요)
 *   ④ FAILED/거부 창발   — 핫 가맹점 타이트 한도 초과 → 거부/FAILED.    ← SEED 타이트 한도에서 창발
 *
 * 해석: 균등 100% 성공이 아니다. `cancel_success_rate{path=rehit}`·`{path=new}` < 100% 는 버그가 아니라
 *   현실 신호(한도초과 거부·이미-취소 충돌). path별 성공률·지연이 "220 균등"의 현실 보정치다.
 *
 * 정직성 불변식: 비-200 = 의도된 4xx 거부여야 한다. payment ErrorCode 상 모든 비즈니스 거부는 4xx
 *   (422 MERCHANT_CANCEL_LIMIT_EXCEEDED·INVALID_PAYMENT_ITEM_STATUS 등), 5xx 는 INTERNAL_ERROR/503(의존성 다운)뿐.
 *   → `server_error_rate` 는 정상 런에서 0(threshold 로 강제). 5xx가 나면 "현실 거부"가 아니라 진짜 결함.
 *   집계 "성공 25%"는 시스템 품질이 아니라 타이트 한도 하 거부 비율이며, 5xx=0 이면 전량 정상 거부다.
 *
 * 전제 재시딩 (핫 편중·다중아이템·타이트 한도):
 *   ITEMS_PER_PAYMENT=3 HOT_MERCHANT_COUNT=2 HOT_TRAFFIC_PCT=80 TIGHT_DAILY_LIMIT=200000000 \
 *   SEED_COUNT=100000 TARGET=aws MERCHANT_URL=http://10.0.1.22:8082 MYSQL_HOST=10.0.1.30 ./k6/seed/seed.sh
 *
 * 실행:
 *   TARGET=aws VUS=300 DURATION=6m PROM=http://10.0.1.50:9090/api/v1/write k6 run k6/realistic-mix.js
 *
 * 노브(env): REHIT_PCT(기본 15) · PARTIAL_PCT(기본 30, 신규 중 비율) · VUS · DURATION
 *   ※ 편중도/타이트도는 SEED 노브로 조절(위). k6는 재히트·부분취소 비율만.
 */
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Rate, Trend, Counter } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

const REHIT_PCT = Number(__ENV.REHIT_PCT ?? 15) / 100;
const PARTIAL_PCT = Number(__ENV.PARTIAL_PCT ?? 30) / 100;
const VUS = Number(__ENV.VUS ?? 300);
const DURATION = __ENV.DURATION ?? '6m';

// 시드 JSON: [{ paymentKey, merchantId, paymentItemId, paymentItemIds:[...] }]
// paymentItemIds 없으면(구 시드) paymentItemId 단건으로 폴백 → 부분취소는 자동 비활성.
const pool = new SharedArray('payments', () => {
  const raw = JSON.parse(open('./seed/paymentKeys.json'));
  return raw.map((p) => ({
    paymentKey: p.paymentKey,
    merchantId: p.merchantId,
    items: p.paymentItemIds && p.paymentItemIds.length ? p.paymentItemIds : [p.paymentItemId],
  }));
});

const success = new Rate('cancel_success_rate');
// 상태클래스 분해 — "실패(비-200)"가 의도된 4xx 거부인지, 진짜 장애(5xx)인지 못박는다.
//   payment ErrorCode 상 모든 비즈니스 거부는 4xx(422 한도초과·이미취소 등), 5xx는 INTERNAL_ERROR/503(의존성 다운)뿐.
//   따라서 정상 런에서 server_error_rate 는 0 이어야 한다(아래 threshold 로 강제). 4xx 는 현실 거부 신호.
const serverError = new Rate('server_error_rate'); // 5xx 비율 — 0 이 불변식(1건이라도 나면 거부 아닌 결함)
const reject4xx = new Rate('reject_4xx_rate');      // 4xx 비율 — 의도된 비즈니스 거부(한도·이미취소)
const dur = new Trend('cancel_duration_ms', true);
const cNew = new Counter('path_new');
const cRehit = new Counter('path_rehit');
const cPartial = new Counter('path_partial');

export const options = {
  scenarios: {
    mix: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'mix' },
  },
  thresholds: {
    // 관측 목적 — abort 안 함. 현실 믹스에선 100% 성공이 아닐 수 있다(거부/한도).
    'http_req_duration': ['p(95)<5000'],
    // 정직성 불변식: 비-200 은 전부 의도된 4xx 거부여야 한다. 5xx가 1건이라도 나면
    // 그건 "현실 거부"가 아니라 진짜 결함(버그 500 또는 의존성 다운 503) → 런 실패로 표시.
    'server_error_rate': ['rate==0'],
  },
};

// 다중아이템 중 1..len-1개 무작위 (전체 아님 = 진짜 부분취소)
function pickSubset(items) {
  if (items.length <= 1) return items;
  const shuffled = [...items].sort(() => Math.random() - 0.5);
  const k = 1 + Math.floor(Math.random() * (items.length - 1)); // 1..len-1
  return shuffled.slice(0, k);
}

function cancel(paymentKey, itemIds, path, counter) {
  counter.add(1);
  const start = Date.now();
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${paymentKey}/cancel`,
    JSON.stringify({
      cancelItems: itemIds.map((id) => ({ paymentItemId: id })),
      cancelReason: 'k6 realistic-mix',
    }),
    { headers: HEADERS, tags: { path } }
  );
  dur.add(Date.now() - start, { path });
  // 성공 = HTTP 200 (COMPLETED 또는 멱등 재히트의 기존 상태). 한도초과 거부는 4xx → 실패로 집계(의도).
  success.add(res.status === 200, { path });
  // 비-200 을 4xx(의도된 거부) / 5xx(진짜 장애)로 분해. 5xx 는 정상 런에서 0이어야 한다.
  serverError.add(res.status >= 500, { path });
  reject4xx.add(res.status >= 400 && res.status < 500, { path });
  return res;
}

export function mix() {
  const idx = exec.scenario.iterationInTest;
  const r = Math.random();

  // ② 재요청 — 앞 구간 키를 전체 items로 재요청.
  //   - 타깃이 전체취소였으면 같은 request_hash → dedup(기존 상태 반환).
  //   - 타깃이 부분취소였으면 hash 다름 + 이미취소 아이템 → 거부(이미-취소 충돌).
  //   - 타깃이 아직 미취소(앞선 rehit 이터레이션의 idx)면 신규 취소로 처리(소수).
  //   → success{path=rehit} < 100% 는 정상(위 충돌·한도초과). stateless 풀이라 원래 subset 재현 불가.
  if (r < REHIT_PCT && idx > 200) {
    const past = pool[Math.floor(Math.random() * Math.min(idx, pool.length))];
    cancel(past.paymentKey, past.items, 'rehit', cRehit);
    return;
  }

  // ① 신규 취소 — 고유 결제 소비(이중취소 방지). 풀은 시드에서 파레토(핫 80%)라 자연 편중.
  if (idx >= pool.length) {
    exec.test.abort(`payment pool 소진(${pool.length}). SEED_COUNT 늘려 재시딩.`);
    return;
  }
  const p = pool[idx];

  // ③ 부분취소 — 다중아이템일 때만
  if (Math.random() < PARTIAL_PCT && p.items.length > 1) {
    cancel(p.paymentKey, pickSubset(p.items), 'partial', cPartial);
  } else {
    cancel(p.paymentKey, p.items, 'new', cNew);
  }
}
