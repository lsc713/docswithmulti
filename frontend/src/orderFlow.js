export const ORDER_FLOW_SESSION_KEY = 'fashion-shop:order-flow'

const optionalFields = ['imageUrl', 'images', 'variant', 'availableQty', 'price', 'skuCode']

export function normalizeOrderItems(lines) {
  if (!Array.isArray(lines)) return []

  return lines.flatMap(line => {
    const skuId = Number(line?.skuId)
    const productId = Number(line?.productId)
    const unitPrice = Number(line?.unitPrice)
    const quantity = Math.floor(Number(line?.quantity))
    const itemName = typeof line?.itemName === 'string' ? line.itemName.trim() : ''
    const optionSummary = typeof line?.optionSummary === 'string' ? line.optionSummary.trim() : ''
    if (!Number.isInteger(skuId) || skuId <= 0 || !Number.isInteger(productId) || productId <= 0 ||
        !Number.isFinite(unitPrice) || unitPrice <= 0 || !Number.isInteger(quantity) || quantity <= 0 || !itemName) {
      return []
    }

    const item = { skuId, productId, itemName, optionSummary, unitPrice, quantity }
    for (const field of optionalFields) {
      if (line[field] !== undefined) item[field] = line[field]
    }
    return [item]
  })
}

export function aggregateOrderItems(orderItems) {
  const subtotal = orderItems.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0)
  return {
    itemCount: orderItems.length,
    quantity: orderItems.reduce((sum, item) => sum + item.quantity, 0),
    subtotal,
    shipping: 0,
    discount: 0,
    grandTotal: subtotal,
  }
}

function normalizeState(value) {
  if (!value || typeof value !== 'object') return null
  const orderItems = normalizeOrderItems(value.orderItems)
  if (orderItems.length === 0 || orderItems.length !== value.orderItems?.length) return null
  const state = { orderItems }
  if (value.source === 'product' || value.source === 'cart') state.source = value.source
  if (Number.isInteger(value.productId) && value.productId > 0) state.productId = value.productId
  if (value.createdOrderItems !== undefined) {
    const createdOrderItems = normalizeCreatedOrderItems(value.createdOrderItems, orderItems.length)
    if (!createdOrderItems) return null
    state.createdOrderItems = createdOrderItems
  }
  return state
}

function normalizeCreatedOrderItems(value, expectedCount) {
  if (!Array.isArray(value) || value.length !== expectedCount) return null
  const items = value.map(item => ({ orderItemId: Number(item?.orderItemId) }))
  return items.every(item => Number.isInteger(item.orderItemId) && item.orderItemId > 0) ? items : null
}

export function resolveOrderRouteState(routeState, session = globalThis.sessionStorage) {
  if (routeState !== undefined && routeState !== null) return normalizeState(routeState)
  try {
    return normalizeState(JSON.parse(session?.getItem(ORDER_FLOW_SESSION_KEY) ?? 'null'))
  } catch {
    return null
  }
}

export function persistOrderRouteState(state, session = globalThis.sessionStorage) {
  const normalized = normalizeState(state)
  if (!normalized) return false
  session?.setItem(ORDER_FLOW_SESSION_KEY, JSON.stringify(normalized))
  return true
}

export function getOrderItemState(item) {
  const reasons = []
  if (Number.isFinite(item.availableQty) && item.availableQty < item.quantity) {
    reasons.push(item.availableQty <= 0 ? '품절 · 옵션 변경 필요' : `재고 ${item.availableQty}개 · 수량 변경 필요`)
  }
  if (Number.isFinite(item.price) && item.price !== item.unitPrice) {
    reasons.push(`가격 변경 ₩${item.unitPrice.toLocaleString('ko-KR')} → ₩${item.price.toLocaleString('ko-KR')}`)
  }
  return { blocked: reasons.length > 0, reasons }
}

const lineName = item => `${item.itemName} ${item.optionSummary}`.trim()

export function buildOrderPayload(orderItems) {
  return {
    items: orderItems.map(item => ({
      productId: item.productId,
      itemName: lineName(item),
      price: item.unitPrice * item.quantity,
    })),
  }
}

export function buildPaymentPayload(orderItems, createdOrderItems) {
  const linkage = normalizeCreatedOrderItems(createdOrderItems, orderItems.length)
  if (!linkage) {
    throw new Error('주문 상품 연결 정보가 올바르지 않습니다.')
  }
  return {
    merchantId: 1,
    pgType: 'TOSS',
    cancelPeriodDays: 7,
    items: orderItems.map((item, index) => ({
      orderItemId: linkage[index].orderItemId,
      productId: item.productId,
      itemName: lineName(item),
      itemAmount: item.unitPrice * item.quantity,
      skuId: item.skuId,
      quantity: item.quantity,
    })),
  }
}

export async function submitOrderPayment(orderItems, paymentApi, orderCache, onOrderCreated) {
  if (!orderCache.current) {
    const createdOrder = await paymentApi.createOrder(buildOrderPayload(orderItems))
    const linkage = normalizeCreatedOrderItems(createdOrder?.items, orderItems.length)
    if (!linkage) throw new Error('주문 상품 연결 정보가 올바르지 않습니다.')
    orderCache.current = linkage
    onOrderCreated?.(linkage)
  }
  return paymentApi.createPayment(buildPaymentPayload(orderItems, orderCache.current))
}
