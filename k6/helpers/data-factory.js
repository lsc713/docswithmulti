/**
 * 테스트 픽스처 생성 헬퍼
 *
 * 호출 순서: 가맹점 → 주문 → 결제
 * - 주문은 order-service (8081), 결제는 payment-service (8080),
 *   가맹점은 merchant-limit-service (8082)
 * - 각 결제는 item 1개 → 취소 1회 소진
 */

import http from 'k6/http';
import { sleep } from 'k6';
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
// 반환: { merchantIds, pool: [{ paymentKey, paymentItemId, merchantId }] }
//
// 가맹점 MERCHANT_COUNT개를 생성하고 poolSize건을 균등 분배.
// → merchant_cancel_usage FOR UPDATE 경합을 가맹점 수만큼 분산.
// → 가맹점당 약 poolSize / MERCHANT_COUNT 건 (150건 @ 1500/10).
//
// 배치 10건씩, 배치 간 sleep(0.2) — risk CB OPEN 방지.

const MERCHANT_COUNT = 10;

export function buildPaymentPool(poolSize) {
  console.log(`[setup] payment pool 생성 시작: ${poolSize}건 / 가맹점 ${MERCHANT_COUNT}개`);

  // ① 가맹점 MERCHANT_COUNT개 순차 생성
  const merchantIds = [];
  for (let m = 0; m < MERCHANT_COUNT; m++) {
    const merchant = createMerchant(`_${m}`);
    merchantIds.push(merchant.merchantId ?? merchant.id);
  }
  console.log(`[setup] 가맹점 생성 완료: [${merchantIds}]`);

  const pool = [];
  const BATCH = 10; // risk CB 트리거 방지: 배치를 작게 유지

  for (let offset = 0; offset < poolSize; offset += BATCH) {
    const count = Math.min(BATCH, poolSize - offset);
    // 배치 단위로 가맹점 라운드로빈 — 가맹점당 FOR UPDATE 경합 분산
    const merchantId = merchantIds[Math.floor(offset / BATCH) % MERCHANT_COUNT];

    // ② 주문 배치
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

    // ③ 결제 배치 (주문 응답에서 orderItemId 추출)
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

    // ④ pool에 추가
    for (const r of paymentResps) {
      const p = JSON.parse(r.body);
      if (p.paymentKey && p.items?.length > 0) {
        pool.push({ paymentKey: p.paymentKey, paymentItemId: p.items[0].paymentItemId, merchantId });
      }
    }

    console.log(`[setup] 진행: ${Math.min(offset + BATCH, poolSize)}/${poolSize} (merchantId=${merchantId})`);
    sleep(0.2); // 배치 간 대기 — risk CB OPEN 방지
  }

  console.log(`[setup] pool 생성 완료: ${pool.length}건`);
  return { merchantIds, pool };
}
