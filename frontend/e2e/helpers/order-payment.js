const GW = 'http://localhost:8000'

async function csrfHeader(page) {
  const cookies = await page.context().cookies(GW)
  const token = cookies.find(cookie => cookie.name === 'csrf_token')?.value
  if (!token) throw new Error('API fixture requires an authenticated browser context with a csrf_token cookie.')
  return { 'X-CSRF-Token': token }
}

async function responseJson(response, operation) {
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`${operation} returned HTTP ${response.status()} with invalid JSON: ${text}`)
  }
  if (!response.ok()) {
    throw new Error(`${operation} failed with HTTP ${response.status()}: ${text || '<empty body>'}`)
  }
  return body
}

function selectedLine({ product, sku, quantity }) {
  if (!Number.isInteger(product?.id) || product.id <= 0 || !product.name) {
    throw new Error('API fixture requires a real product with a positive id and name.')
  }
  if (!Number.isInteger(sku?.skuId) || sku.skuId <= 0 || !Number.isFinite(sku.price) || sku.price <= 0) {
    throw new Error('API fixture requires a real SKU with a positive skuId and price.')
  }
  if (!Number.isInteger(quantity) || quantity <= 0) {
    throw new Error('API fixture quantity must be a positive integer.')
  }
  return {
    productId: product.id,
    skuId: sku.skuId,
    quantity,
    itemName: `${product.name} ${sku.optionSummary ?? ''}`.trim(),
    itemAmount: sku.price * quantity,
  }
}

export async function createPaidOrderViaApi(page, selection) {
  const line = selectedLine(selection)
  const headers = await csrfHeader(page)
  const orderResponse = await page.request.post(`${GW}/v1/orders`, {
    headers,
    data: { items: [{ productId: line.productId, itemName: line.itemName, price: line.itemAmount }] },
  })
  const order = await responseJson(orderResponse, 'POST /v1/orders')
  if (!Number.isInteger(order.orderId) || order.orderId <= 0 || order.items?.length !== 1 ||
      !Number.isInteger(order.items[0]?.orderItemId) || order.items[0].orderItemId <= 0) {
    throw new Error(`POST /v1/orders returned invalid one-item linkage: ${JSON.stringify(order)}`)
  }

  const paymentResponse = await page.request.post(`${GW}/v1/payments`, {
    headers,
    data: {
      merchantId: 1,
      pgType: 'TOSS',
      cancelPeriodDays: 7,
      items: [{
        orderItemId: order.items[0].orderItemId,
        productId: line.productId,
        itemName: line.itemName,
        itemAmount: line.itemAmount,
        skuId: line.skuId,
        quantity: line.quantity,
      }],
    },
  })
  const payment = await responseJson(paymentResponse, 'POST /v1/payments')
  if (typeof payment.paymentKey !== 'string' || !payment.paymentKey || payment.status !== 'COMPLETED' ||
      payment.items?.length !== 1) {
    throw new Error(`POST /v1/payments returned invalid completed payment: ${JSON.stringify(payment)}`)
  }
  return payment.paymentKey
}

export async function clearCartViaApi(page) {
  const response = await page.request.delete(`${GW}/v1/cart`, { headers: await csrfHeader(page) })
  if (!response.ok()) {
    throw new Error(`DELETE /v1/cart failed with HTTP ${response.status()}: ${await response.text() || '<empty body>'}`)
  }
}
