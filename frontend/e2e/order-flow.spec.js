import { test, expect } from '@playwright/test'

const SESSION_KEY = 'fashion-shop:order-flow'
const USER = { userId: 7, email: 'buyer@example.com', name: '구매자', role: 'USER' }
const ORDER_ITEMS = [
  { skuId: 11, productId: 1, itemName: '미니멀 울 블레이저', optionSummary: '블랙 / M', unitPrice: 149000, quantity: 1, availableQty: 4 },
  { skuId: 22, productId: 2, itemName: '소프트 니트 카디건', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2, availableQty: 5 },
  { skuId: 33, productId: 3, itemName: '오버사이즈 코튼 셔츠', optionSummary: '화이트 / Free', unitPrice: 63000, quantity: 1, availableQty: 2 },
]

async function mockSession(page, route = '/checkout', orderItems = ORDER_ITEMS) {
  await page.addInitScript(({ key, state }) => {
    const seededKey = `${key}:test-seeded`
    if (!sessionStorage.getItem(seededKey)) {
      sessionStorage.setItem(key, JSON.stringify(state))
      sessionStorage.setItem(seededKey, '1')
    }
  }, {
    key: SESSION_KEY,
    state: { orderItems, source: 'cart' },
  })
  await page.route('http://localhost:8000/v1/auth/me', route => route.fulfill({ json: USER }))
  await page.route('http://localhost:8000/v1/cart', route => route.fulfill({ json: { items: [] } }))
  await page.goto(route)
}

async function mockPaymentSuccess(page) {
  await page.route('http://localhost:8000/v1/orders', route => route.fulfill({
    status: 201,
    json: { orderId: 91, status: 'CREATED', items: ORDER_ITEMS.map((item, index) => ({ orderItemId: index + 101, itemName: item.itemName })) },
  }))
  await page.route('http://localhost:8000/v1/payments', route => route.fulfill({
    json: { paymentKey: 'pay_mocked', totalAmount: 370000, status: 'COMPLETED', items: [] },
  }))
}

test('direct /checkout restores three items, exact totals, and reviews before /payment', async ({ page }) => {
  let submissions = 0
  await page.route('http://localhost:8000/v1/orders', route => { submissions += 1; return route.abort() })
  await page.route('http://localhost:8000/v1/payments', route => { submissions += 1; return route.abort() })

  await mockSession(page)

  await expect(page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' })).toBeVisible()
  await expect(page.locator('.order-item-card')).toHaveCount(3)
  await expect(page.getByTestId('grand-total')).toHaveText('₩370,000')
  await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).click()
  await expect(page).toHaveURL(/\/payment$/)
  expect(submissions).toBe(0)
})

test('payment first click locks submission and preserves confirmed API contracts', async ({ page }) => {
  let orderCalls = 0
  let paymentCalls = 0
  let orderBody
  let paymentBody
  await page.route('http://localhost:8000/v1/orders', async route => {
    orderCalls += 1
    orderBody = route.request().postDataJSON()
    await new Promise(resolve => setTimeout(resolve, 150))
    await route.fulfill({ status: 201, json: { orderId: 91, status: 'CREATED', items: ORDER_ITEMS.map((item, index) => ({ orderItemId: index + 101, itemName: item.itemName })) } })
  })
  await page.route('http://localhost:8000/v1/payments', async route => {
    paymentCalls += 1
    paymentBody = route.request().postDataJSON()
    await route.fulfill({ json: { paymentKey: 'pay_mocked', totalAmount: 370000, status: 'COMPLETED', items: [] } })
  })

  await mockSession(page, '/payment')
  const submit = page.locator('.payment-submit')
  await submit.click()
  await expect(submit).toBeDisabled()
  await expect(submit).toHaveText('결제 요청 중…')
  await submit.click({ force: true })
  await expect(page.getByRole('heading', { name: /결제 완료/ })).toBeVisible()
  await expect(page).toHaveURL(/\/order-success$/)
  expect(await page.evaluate(key => sessionStorage.getItem(key), SESSION_KEY)).toBeNull()

  expect(orderCalls).toBe(1)
  expect(paymentCalls).toBe(1)
  expect(orderBody).toEqual({ items: [
    { productId: 1, itemName: '미니멀 울 블레이저 블랙 / M', price: 149000 },
    { productId: 2, itemName: '소프트 니트 카디건 오트밀 / L', price: 158000 },
    { productId: 3, itemName: '오버사이즈 코튼 셔츠 화이트 / Free', price: 63000 },
  ] })
  expect(paymentBody).toEqual({ merchantId: 1, pgType: 'TOSS', cancelPeriodDays: 7, items: [
    { orderItemId: 101, productId: 1, itemName: '미니멀 울 블레이저 블랙 / M', itemAmount: 149000, skuId: 11, quantity: 1 },
    { orderItemId: 102, productId: 2, itemName: '소프트 니트 카디건 오트밀 / L', itemAmount: 158000, skuId: 22, quantity: 2 },
    { orderItemId: 103, productId: 3, itemName: '오버사이즈 코튼 셔츠 화이트 / Free', itemAmount: 63000, skuId: 33, quantity: 1 },
  ] })
})

test('one item renders at 390px with sticky CTA and no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockSession(page, '/checkout', [ORDER_ITEMS[0]])

  await expect(page.locator('.order-item-card')).toHaveCount(1)
  await expect(page.getByTestId('grand-total')).toHaveText('₩149,000')
  await expect(page.locator('.mobile-flow-cta')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('long order cards keep usable copy width and remain unclipped at tablet width', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 1024 })
  await mockSession(page, '/checkout', [{
    ...ORDER_ITEMS[0],
    itemName: '계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 블레이저',
    optionSummary: '블랙 / Extra Long Variant Label / Medium',
  }])

  const metrics = await page.locator('.order-item-card').evaluate(card => {
    const copy = card.querySelector('.order-item-copy')
    const image = card.querySelector('.order-item-image')
    const cardBox = card.getBoundingClientRect()
    const imageBox = image.getBoundingClientRect()
    return {
      copyWidth: copy.getBoundingClientRect().width,
      cardScrollWidth: card.scrollWidth,
      cardClientWidth: card.clientWidth,
      imageBottom: imageBox.bottom,
      cardBottom: cardBox.bottom,
    }
  })
  expect(metrics.copyWidth).toBeGreaterThan(200)
  expect(metrics.cardScrollWidth).toBeLessThanOrEqual(metrics.cardClientWidth)
  expect(metrics.cardBottom).toBeGreaterThan(metrics.imageBottom)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('empty and invalid direct URLs show a recoverable empty state without localStorage', async ({ page }) => {
  await page.addInitScript(key => {
    sessionStorage.setItem(key, '{invalid')
    localStorage.setItem(key, 'must-not-be-read')
  }, SESSION_KEY)
  await page.route('http://localhost:8000/v1/auth/me', route => route.fulfill({ json: USER }))
  await page.route('http://localhost:8000/v1/cart', route => route.fulfill({ json: { items: [] } }))
  await page.goto('/checkout')

  await expect(page.getByRole('heading', { name: '주문할 상품이 없어요' })).toBeVisible()
  await expect(page.getByRole('button', { name: '상품 둘러보기' })).toBeEnabled()
})

test('stock and price changes are item-scoped and block checkout transition', async ({ page }) => {
  const changed = [
    { ...ORDER_ITEMS[0], quantity: 3, availableQty: 1 },
    { ...ORDER_ITEMS[1], price: 89000 },
  ]
  await mockSession(page, '/checkout', changed)

  await expect(page.getByText('재고 1개 · 수량 변경 필요')).toBeVisible()
  await expect(page.getByText('가격 변경 ₩79,000 → ₩89,000')).toBeVisible()
  await expect(page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first()).toBeDisabled()
  await expect(page.locator('.flow-blocked')).toContainText('재고/가격')
})

test('failed payment survives reload, back, and re-entry without creating another order', async ({ page }) => {
  let orderCalls = 0
  let paymentCalls = 0
  await page.route('http://localhost:8000/v1/orders', route => {
    orderCalls += 1
    return route.fulfill({ status: 201, json: { orderId: 91, status: 'CREATED', items: ORDER_ITEMS.map((item, index) => ({ orderItemId: index + 101 })) } })
  })
  await page.route('http://localhost:8000/v1/payments', route => {
    paymentCalls += 1
    return paymentCalls === 1
      ? route.fulfill({ status: 503, json: { message: '일시적인 결제 오류' } })
      : route.fulfill({ json: { paymentKey: 'pay_retry', totalAmount: 370000, status: 'COMPLETED', items: [] } })
  })
  await mockSession(page, '/checkout')
  await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
  await page.getByRole('button', { name: '₩370,000 결제하기' }).first().click()
  await expect(page.getByRole('alert')).toContainText('일시적인 결제 오류')
  await expect.poll(() => page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), SESSION_KEY))
    .toMatchObject({ createdOrderItems: [{ orderItemId: 101 }, { orderItemId: 102 }, { orderItemId: 103 }] })

  await page.reload()
  await expect(page.getByRole('heading', { name: '결제 수단을 선택해 주세요' })).toBeVisible()
  expect(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)).createdOrderItems.length, SESSION_KEY)).toBe(3)
  await page.getByRole('button', { name: '← 주문서로 돌아가기 · 상태 유지' }).click()
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.locator('.order-item-card')).toHaveCount(3)
  await page.reload()
  expect(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)).createdOrderItems.length, SESSION_KEY)).toBe(3)
  await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
  await page.reload()
  expect(await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)).createdOrderItems.length, SESSION_KEY)).toBe(3)

  await page.getByRole('button', { name: '₩370,000 결제하기' }).first().click()
  await expect(page.getByRole('heading', { name: /결제 완료/ })).toBeVisible()
  await expect(page).toHaveURL(/\/order-success$/)
  expect(orderCalls).toBe(1)
  expect(paymentCalls).toBe(2)

  await page.goBack()
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.getByRole('heading', { name: '주문할 상품이 없어요' })).toBeVisible()
  await expect(page.getByRole('button', { name: /결제하기$/ })).toHaveCount(0)
})

test('success, home, cart, and history keep view and URL aligned across reloads', async ({ page }) => {
  await mockPaymentSuccess(page)
  await page.route('http://localhost:8000/v1/payments', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: [] })
    return route.fulfill({ json: { paymentKey: 'pay_mocked', totalAmount: 370000, status: 'COMPLETED', items: [] } })
  })
  await mockSession(page, '/payment')

  await page.getByRole('button', { name: '₩370,000 결제하기' }).first().click()
  await expect(page).toHaveURL(/\/order-success$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: /결제 완료/ })).toBeVisible()

  await page.getByRole('button', { name: '쇼핑 계속하기' }).click()
  await expect(page).toHaveURL('/')
  await page.getByRole('button', { name: '장바구니(0)' }).click()
  await expect(page).toHaveURL(/\/cart$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '장바구니' })).toBeVisible()
  await page.getByRole('button', { name: '주문내역' }).click()
  await expect(page).toHaveURL(/\/history$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '주문내역' })).toBeVisible()
})

test('twelve long-label items scroll within the desktop list and stay overflow-safe on mobile', async ({ page }) => {
  const many = Array.from({ length: 12 }, (_, index) => ({
    ...ORDER_ITEMS[index % ORDER_ITEMS.length],
    skuId: 100 + index,
    itemName: `계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 상품 ${index + 1}`,
    optionSummary: `오트밀 / Extra Long Variant Label ${index + 1}`,
  }))
  await mockSession(page, '/checkout', many)
  await expect(page.locator('.order-item-card')).toHaveCount(12)
  expect(await page.locator('.order-item-list').evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await expect(page.locator('.mobile-flow-cta')).toBeVisible()
})

test('ProductDetail buy normalizes to checkout then payment without changing gallery behavior', async ({ page }) => {
  const product = {
    id: 1, name: '미니멀 울 블레이저', minPrice: 149000,
    category: [{ id: 10, name: '아우터' }], images: [],
    variantOptions: [{ attribute: '색상', values: ['블랙'] }, { attribute: '사이즈', values: ['M'] }],
    skus: [{ ...ORDER_ITEMS[0], skuCode: 'BLZ-101', price: 149000, variant: { 색상: '블랙', 사이즈: 'M' } }],
  }
  await page.route('http://localhost:8000/v1/auth/me', route => route.fulfill({ json: USER }))
  await page.route('http://localhost:8000/v1/cart', route => route.fulfill({ json: { items: [] } }))
  await page.route('http://localhost:8000/v1/categories', route => route.fulfill({ json: [{ id: 10, name: '아우터', children: [] }] }))
  await page.route('http://localhost:8000/v1/categories/*/products*', route => route.fulfill({ json: { content: [{ id: 1, name: product.name, minPrice: 149000 }] } }))
  await page.route('http://localhost:8000/v1/products/1', route => route.fulfill({ json: product }))
  await mockPaymentSuccess(page)
  await page.goto('/')
  await expect(page.locator('.navbar-right')).toContainText('구매자님')
  await page.locator('.grid .card').first().click()
  const detail = page.getByRole('main', { name: '상품 상세' })
  await detail.getByRole('button', { name: '색상 · 선택 안 됨' }).click()
  await detail.getByRole('option', { name: '블랙', exact: true }).click()
  await detail.getByRole('button', { name: '사이즈 · 선택 안 됨' }).click()
  await detail.getByRole('option', { name: 'M', exact: true }).click()
  await detail.getByRole('button', { name: '구매하기' }).click()
  await expect(page).toHaveURL(/\/checkout$/)
  await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
  await expect(page).toHaveURL(/\/payment$/)
})

test('Cart N items normalize to checkout then payment', async ({ page }) => {
  await page.route('http://localhost:8000/v1/auth/me', route => route.fulfill({ json: USER }))
  await page.route('http://localhost:8000/v1/cart', route => route.fulfill({ json: { items: ORDER_ITEMS } }))
  await page.route('http://localhost:8000/v1/categories', route => route.fulfill({ json: [] }))
  await mockPaymentSuccess(page)
  await page.goto('/')
  await page.getByRole('button', { name: '장바구니(4)' }).click()
  await page.getByRole('button', { name: '주문하기' }).click()
  await expect(page.locator('.order-item-card')).toHaveCount(3)
  await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
  await expect(page).toHaveURL(/\/payment$/)
})
