/**
 * Scenario 2: 동시 취소 멱등성 테스트
 *
 * 같은 paymentKey + paymentItemId로 10개 VU가 동시에 취소 요청.
 * 기대:
 *   - 모든 요청 HTTP 200
 *   - cancel_request는 1건만 생성 (COMPLETED)
 *   - cancelRequestId 동일
 *
 * 실행:
 *   k6 run k6/idempotency-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, HEADERS } from './config.js';
import { createMerchant, createOrder, createPayment } from './helpers/data-factory.js';

// ─── 옵션 ────────────────────────────────────────────────────
export const options = {
  vus: 10,
  iterations: 10, // VU당 1회씩, 총 10회
  thresholds: {
    http_req_failed: ['rate==0'],   // 실패 0건
    checks:          ['rate==1.0'], // 모든 check 통과
  },
};

// ─── 픽스처 생성 (1회) ───────────────────────────────────────
export function setup() {
  const merchant   = createMerchant('_idempotency');
  const merchantId = merchant.merchantId ?? merchant.id;

  const order      = createOrder(99999);
  const orderItemId = order.items[0].orderItemId;

  const payment     = createPayment(merchantId, 99999, orderItemId);

  console.log(`[setup] paymentKey=${payment.paymentKey}  paymentItemId=${payment.items[0].paymentItemId}`);

  return {
    paymentKey:    payment.paymentKey,
    paymentItemId: payment.items[0].paymentItemId,
  };
}

// ─── VU 메인 루프 ────────────────────────────────────────────
export default function (data) {
  // 0~20ms 랜덤 지연으로 동시성 극대화
  // (완전히 같은 나노초는 불가능하나, DB 트랜잭션 경쟁 유발에 충분)
  sleep(Math.random() * 0.02);

  const res = http.post(
    `${BASE.PAYMENT}/v1/payments/${data.paymentKey}/cancel`,
    JSON.stringify({
      cancelItems: [{ paymentItemId: data.paymentItemId }],
      cancelReason: '멱등성 동시 요청 테스트',
    }),
    { headers: HEADERS }
  );

  check(res, {
    'HTTP 200':           r => r.status === 200,
    'cancelRequestId 존재': r => {
      try { return !!JSON.parse(r.body).cancelRequestId; }
      catch { return false; }
    },
  });

  if (res.status === 200) {
    const body = JSON.parse(res.body);
    console.log(`VU=${__VU} cancelRequestId=${body.cancelRequestId} status=${body.status}`);
  }
}

// ─── 최종 검증 (teardown은 setup 데이터 접근 가능) ───────────
export function teardown(data) {
  // 처리 완료 대기 (PENDING/PROCESSING → COMPLETED 전환 시간)
  sleep(2);

  const res = http.get(
    `${BASE.PAYMENT}/v1/payments/${data.paymentKey}/cancels`,
    { headers: HEADERS }
  );

  if (res.status !== 200) {
    console.error(`[teardown] 취소 목록 조회 실패: ${res.status} / ${res.body}`);
    return;
  }

  const body = JSON.parse(res.body);
  const total = body.totalElements;
  const items = body.content ?? [];

  // ── 멱등성 결과 출력 ──
  console.log('');
  console.log('══════════════════════════════════════');
  console.log('  멱등성 테스트 결과');
  console.log('══════════════════════════════════════');
  console.log(`  동시 요청:       10건`);
  console.log(`  생성된 취소 요청: ${total}건  (기대: 1건)`);

  if (items.length > 0) {
    const statuses = items.map(c => c.status);
    console.log(`  상태 목록:       ${statuses.join(', ')}`);
    console.log(`  COMPLETED 건수:  ${statuses.filter(s => s === 'COMPLETED').length}건  (기대: 1건)`);
  }

  if (total === 1) {
    console.log('  결과:            ✓ PASS');
  } else {
    console.log('  결과:            ✗ FAIL — 멱등성 위반');
  }
  console.log('══════════════════════════════════════');
}
