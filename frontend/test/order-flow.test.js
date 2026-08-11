import assert from 'node:assert/strict'
import test from 'node:test'

const flow = await import('../src/orderFlow.js').catch(() => ({}))

test('normalizes ProductDetail and Cart lines to one orderItems schema and aggregates totals', () => {
  assert.equal(typeof flow.normalizeOrderItems, 'function')

  const orderItems = flow.normalizeOrderItems([
    { skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 },
    { skuId: 22, productId: 2, itemName: '니트', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2 },
  ])

  assert.deepEqual(orderItems, [
    { skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 },
    { skuId: 22, productId: 2, itemName: '니트', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2 },
  ])
  assert.deepEqual(flow.aggregateOrderItems(orderItems), {
    itemCount: 2,
    quantity: 3,
    subtotal: 307000,
    shipping: 0,
    discount: 0,
    grandTotal: 307000,
  })
})

test('route state falls back to session data and rejects invalid or empty orders', () => {
  assert.equal(typeof flow.resolveOrderRouteState, 'function')

  const valid = {
    orderItems: [{ skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 }],
    source: 'product',
  }
  const session = { getItem: () => JSON.stringify(valid) }

  assert.deepEqual(flow.resolveOrderRouteState(undefined, session), valid)
  assert.equal(flow.resolveOrderRouteState({ orderItems: [] }, session), null)
  assert.equal(flow.resolveOrderRouteState(undefined, { getItem: () => '{broken' }), null)
})

test('preserves only API-backed presentation fields and derives stock or price changes per item', () => {
  assert.equal(typeof flow.getOrderItemState, 'function')
  const [item] = flow.normalizeOrderItems([{
    skuId: 11, productId: 1, itemName: '긴 상품명', optionSummary: '검정 / M', unitPrice: 149000, quantity: 2,
    imageUrl: '/coat.jpg', variant: { 색상: '검정' }, availableQty: 1, price: 159000, arbitrary: 'drop me',
  }])

  assert.deepEqual(item, {
    skuId: 11, productId: 1, itemName: '긴 상품명', optionSummary: '검정 / M', unitPrice: 149000, quantity: 2,
    imageUrl: '/coat.jpg', variant: { 색상: '검정' }, availableQty: 1, price: 159000,
  })
  assert.deepEqual(flow.getOrderItemState(item), {
    blocked: true,
    reasons: ['재고 1개 · 수량 변경 필요', '가격 변경 ₩149,000 → ₩159,000'],
  })
})

test('persists route state in session and builds the confirmed order/payment payloads', () => {
  assert.equal(typeof flow.persistOrderRouteState, 'function')
  assert.equal(typeof flow.buildOrderPayload, 'function')
  assert.equal(typeof flow.buildPaymentPayload, 'function')
  let stored
  const session = { setItem: (key, value) => { stored = { key, value } } }
  const state = {
    orderItems: [{ skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 }],
    source: 'product',
  }

  flow.persistOrderRouteState(state, session)
  assert.equal(stored.key, 'fashion-shop:order-flow')
  assert.deepEqual(JSON.parse(stored.value), state)
  assert.deepEqual(flow.buildOrderPayload(state.orderItems), {
    items: [{ productId: 1, itemName: '재킷 검정 / M', price: 149000 }],
  })
  assert.deepEqual(flow.buildPaymentPayload(state.orderItems, [{ orderItemId: 101 }]), {
    merchantId: 1,
    pgType: 'TOSS',
    cancelPeriodDays: 7,
    items: [{ orderItemId: 101, productId: 1, itemName: '재킷 검정 / M', itemAmount: 149000, skuId: 11, quantity: 1 }],
  })
})

test('persists only validated created-order item linkage and rejects malformed linkage', () => {
  const orderItems = [
    { skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 },
    { skuId: 22, productId: 2, itemName: '니트', optionSummary: '회색 / L', unitPrice: 79000, quantity: 2 },
  ]
  let stored
  const session = { setItem: (_key, value) => { stored = value } }

  assert.equal(flow.persistOrderRouteState({
    orderItems,
    source: 'product',
    productId: 1,
    createdOrderItems: [
      { orderItemId: 101, itemName: '서버 응답의 불필요한 필드' },
      { orderItemId: 102, price: 158000 },
    ],
    arbitrary: 'drop me',
  }, session), true)
  assert.deepEqual(JSON.parse(stored), {
    orderItems,
    source: 'product',
    productId: 1,
    createdOrderItems: [{ orderItemId: 101 }, { orderItemId: 102 }],
  })

  assert.equal(flow.resolveOrderRouteState({ orderItems, createdOrderItems: [{ orderItemId: 101 }] }, { getItem: () => null }), null)
  assert.equal(flow.resolveOrderRouteState({ orderItems, createdOrderItems: [{ orderItemId: 101 }, { orderItemId: 0 }] }, { getItem: () => null }), null)
})

test('payment retry after remount reuses the minimally persisted created order', async () => {
  assert.equal(typeof flow.submitOrderPayment, 'function')
  const orderItems = [{ skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 }]
  const createdOrder = { orderId: 91, status: 'CREATED', items: [{ orderItemId: 101, itemName: '재킷' }] }
  let persistedLinkage
  let orderCalls = 0
  let paymentCalls = 0
  const api = {
    createOrder: async () => { orderCalls += 1; return createdOrder },
    createPayment: async () => { paymentCalls += 1; if (paymentCalls === 1) throw new Error('PG timeout'); return { paymentKey: 'pay_retry' } },
  }

  await assert.rejects(
    flow.submitOrderPayment(orderItems, api, { current: null }, linkage => { persistedLinkage = linkage }),
    /PG timeout/,
  )
  assert.deepEqual(persistedLinkage, [{ orderItemId: 101 }])
  assert.deepEqual(
    await flow.submitOrderPayment(orderItems, api, { current: persistedLinkage }),
    { paymentKey: 'pay_retry' },
  )
  assert.equal(orderCalls, 1)
  assert.equal(paymentCalls, 2)
})
