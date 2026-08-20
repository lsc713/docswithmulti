export const ORDER_FLOW_SESSION_KEY = 'fashion-shop:order-flow'

export function isStockInsufficient(error) {
  return error?.status === 409 && ['STOCK_INSUFFICIENT', 'STOCK_001'].includes(error.code)
}

const optionalFields = ['imageUrl', 'images', 'variant', 'skuCode']

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
  return state
}

export function resolveOrderRouteState(routeState, ownerUserId, session = globalThis.sessionStorage) {
  if (!Number.isInteger(ownerUserId) || ownerUserId <= 0) return null
  if (routeState !== undefined && routeState !== null) return normalizeState(routeState)
  try {
    const stored = JSON.parse(session?.getItem(ORDER_FLOW_SESSION_KEY) ?? 'null')
    if (stored?.ownerUserId !== ownerUserId) {
      clearOrderRouteState(session)
      return null
    }
    return normalizeState(stored.flowState)
  } catch {
    return null
  }
}

export function persistOrderRouteState(state, ownerUserId, session = globalThis.sessionStorage) {
  const normalized = normalizeState(state)
  if (!normalized || !Number.isInteger(ownerUserId) || ownerUserId <= 0) return false
  session?.setItem(ORDER_FLOW_SESSION_KEY, JSON.stringify({ ownerUserId, flowState: normalized }))
  return true
}

export function clearOrderRouteState(session = globalThis.sessionStorage) {
  session?.removeItem?.(ORDER_FLOW_SESSION_KEY)
}
