/**
 * 테스트 픽스처 생성 헬퍼
 *
 * 호출 순서: 가맹점 → 주문 → 결제
 * - 주문은 order-service (8081), 결제는 payment-service (8080),
 *   가맹점은 merchant-limit-service (8082)
 * - 각 결제는 item 1개 → 취소 1회 소진
 */

import http from 'k6/http';
import { BASE, HEADERS } from '../config.js';

// ─── 단건 생성 ────────────────────────────────────────────────

export function createMerchant(tag = '') {
  const res = http.post(
    `${BASE.MERCHANT}/v1/merchants`,
    JSON.stringify({
      merchantKey: `merchant_k6${tag}_${Date.now()}`,
      name: 'k6 테스트 가맹점',
      cancelPeriodDays: 30,
      dailyLimit: 1_000_000_000, // 10억 — 한도 초과 없게 충분히 크게
    }),
    { headers: HEADERS }
  );
  if (res.status !== 200 && res.status !== 201) {
    throw new Error(`가맹점 생성 실패: ${res.status} / ${res.body}`);
  }
  return JSON.parse(res.body); // { merchantId, merchantKey, ... }
}

export function createOrder(userId) {
  const res = http.post(
    `${BASE.ORDER}/v1/orders`,
    JSON.stringify({
      userId,
      items: [
        { productId: 201, itemName: 'k6 테스트 상품', price: 10000 },
      ],
    }),
    { headers: HEADERS }
  );
  if (res.status !== 200 && res.status !== 201) {
    throw new Error(`주문 생성 실패: ${res.status} / ${res.body}`);
  }
  return JSON.parse(res.body); // { orderId, items: [{ orderItemId, ... }] }
}

export function createPayment(merchantId, userId, orderItemId) {
  const res = http.post(
    `${BASE.PAYMENT}/v1/payments`,
    JSON.stringify({
      merchantId,
      userId,
      pgType: 'TOSS',
      cancelPeriodDays: 30,
      items: [
        { orderItemId, productId: 201, itemName: 'k6 테스트 상품', itemAmount: 10000 },
      ],
    }),
    { headers: HEADERS }
  );
  if (res.status !== 200 && res.status !== 201) {
    throw new Error(`결제 생성 실패: ${res.status} / ${res.body}`);
  }
  return JSON.parse(res.body); // { paymentKey, items: [{ paymentItemId, ... }] }
}

// ─── Pool 배치 생성 ──────────────────────────────────────────
//
// 반환: { merchantId, pool: [{ paymentKey, paymentItemId }] }
//
// 배치 50건씩 주문 → 결제 순으로 생성.
// POOL_SIZE=1500 기준 setup 약 30~40초 소요.

export function buildPaymentPool(poolSize) {
  console.log(`[setup] payment pool 생성 시작: ${poolSize}건`);

  const merchant = createMerchant();
  const merchantId = merchant.merchantId ?? merchant.id;
  console.log(`[setup] 가맹점 생성 완료 merchantId=${merchantId}`);

  const pool = [];
  const BATCH = 50;

  for (let offset = 0; offset < poolSize; offset += BATCH) {
    const count = Math.min(BATCH, poolSize - offset);

    // ① 주문 배치
    const orderReqs = Array.from({ length: count }, (_, i) => ({
      method: 'POST',
      url: `${BASE.ORDER}/v1/orders`,
      body: JSON.stringify({
        userId: 9000 + offset + i,
        items: [{ productId: 201, itemName: 'k6 테스트 상품', price: 10000 }],
      }),
      params: { headers: HEADERS },
    }));
    const orderResps = http.batch(orderReqs);

    // ② 결제 배치 (주문 응답에서 orderItemId 추출)
    const paymentReqs = orderResps.map((r, i) => {
      const order = JSON.parse(r.body);
      const orderItemId = order.items[0].orderItemId;
      return {
        method: 'POST',
        url: `${BASE.PAYMENT}/v1/payments`,
        body: JSON.stringify({
          merchantId,
          userId: 9000 + offset + i,
          pgType: 'TOSS',
          cancelPeriodDays: 30,
          items: [{ orderItemId, productId: 201, itemName: 'k6 테스트 상품', itemAmount: 10000 }],
        }),
        params: { headers: HEADERS },
      };
    });
    const paymentResps = http.batch(paymentReqs);

    // ③ pool에 추가
    for (const r of paymentResps) {
      const p = JSON.parse(r.body);
      if (p.paymentKey && p.items?.length > 0) {
        pool.push({ paymentKey: p.paymentKey, paymentItemId: p.items[0].paymentItemId });
      }
    }

    console.log(`[setup] 진행: ${Math.min(offset + BATCH, poolSize)}/${poolSize}`);
  }

  console.log(`[setup] pool 생성 완료: ${pool.length}건`);
  return { merchantId, pool };
}
