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

test('route state restores only for the authenticated numeric owner', () => {
  assert.equal(typeof flow.resolveOrderRouteState, 'function')

  const valid = {
    orderItems: [{ skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 }],
    source: 'product',
  }
  const session = { getItem: () => JSON.stringify({ ownerUserId: 7, flowState: valid }) }

  assert.deepEqual(flow.resolveOrderRouteState(undefined, 7, session), valid)
  assert.equal(flow.resolveOrderRouteState(undefined, 8, session), null)
  assert.equal(flow.resolveOrderRouteState(undefined, null, session), null)
  assert.equal(flow.resolveOrderRouteState({ orderItems: [] }, 7, session), null)
  assert.equal(flow.resolveOrderRouteState(undefined, 7, { getItem: () => '{broken' }), null)
})

test('keeps preview fields but drops unverified checkout stock and comparison prices', () => {
  const [item] = flow.normalizeOrderItems([{
    skuId: 11, productId: 1, itemName: '긴 상품명', optionSummary: '검정 / M', unitPrice: 149000, quantity: 2,
    imageUrl: '/coat.jpg', variant: { 색상: '검정' }, availableQty: 1, price: 159000, arbitrary: 'drop me',
  }])

  assert.deepEqual(item, {
    skuId: 11, productId: 1, itemName: '긴 상품명', optionSummary: '검정 / M', unitPrice: 149000, quantity: 2,
    imageUrl: '/coat.jpg', variant: { 색상: '검정' },
  })
})

test('persists route state with only its numeric authenticated owner', () => {
  assert.equal(typeof flow.persistOrderRouteState, 'function')
  let stored
  const session = { setItem: (key, value) => { stored = { key, value } } }
  const state = {
    orderItems: [{ skuId: 11, productId: 1, itemName: '재킷', optionSummary: '검정 / M', unitPrice: 149000, quantity: 1 }],
    source: 'product',
  }

  flow.persistOrderRouteState(state, 7, session)
  assert.equal(stored.key, 'fashion-shop:order-flow')
  assert.deepEqual(JSON.parse(stored.value), { ownerUserId: 7, flowState: state })
  assert.equal(flow.persistOrderRouteState(state, '7', session), false)
})

test('clears persisted order state through the shared session boundary', () => {
  let removed
  assert.equal(typeof flow.clearOrderRouteState, 'function')
  flow.clearOrderRouteState({ removeItem: key => { removed = key } })
  assert.equal(removed, 'fashion-shop:order-flow')
})
