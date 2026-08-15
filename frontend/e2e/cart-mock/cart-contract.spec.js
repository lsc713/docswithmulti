import { test, expect } from '@playwright/test'

const GW = 'http://localhost:8000'
const USER = { userId: 7, email: 'buyer@example.com', name: '구매자', role: 'USER' }
const USER_B = { userId: 8, email: 'buyer-b@example.com', name: '구매자 B', role: 'USER' }
const ITEMS = [
  { skuId: 11, productId: 1, itemName: '미니멀 울 블레이저', optionSummary: '블랙 / M', unitPrice: 149000, quantity: 1 },
  { skuId: 22, productId: 2, itemName: '소프트 니트 카디건', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2 },
  { skuId: 33, productId: 3, itemName: '계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 셔츠', optionSummary: '화이트 / Extra Long Variant Label', unitPrice: 63000, quantity: 1 },
]

async function mockCart(page) {
  let items = structuredClone(ITEMS)
  const writes = []
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ json: { items } }))
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    const skuId = Number(route.request().url().split('/').at(-1))
    if (route.request().method() === 'PATCH') {
      const { quantity } = route.request().postDataJSON()
      writes.push({ method: 'PATCH', skuId, body: { quantity } })
      items = items.map(item => item.skuId === skuId ? { ...item, quantity } : item)
    } else if (route.request().method() === 'DELETE') {
      writes.push({ method: 'DELETE', skuId })
      items = items.filter(item => item.skuId !== skuId)
    }
    await route.fulfill({ json: {} })
  })
  return writes
}

test('renders every cart line and preserves the confirmed quantity, delete, total, and checkout contract', async ({ page }) => {
  const writes = await mockCart(page)
  await page.goto('/cart')

  const cart = page.getByRole('main', { name: '장바구니' })
  await expect(cart.getByRole('heading', { name: '장바구니', level: 1 })).toBeVisible()
  await expect(cart.locator('.cart-table tbody tr')).toHaveCount(3)
  await expect(cart.locator('.cart-product-copy').first().getByText('블랙 / M')).toBeVisible()
  await expect(cart.locator('.cart-product-copy').first().getByText('단가 ₩149,000')).toBeVisible()
  const firstRow = cart.locator('.cart-table tbody tr').first()
  await expect(firstRow.getByRole('cell', { name: '옵션 블랙 / M' })).toBeAttached()
  await expect(firstRow.getByRole('cell', { name: '단가 ₩149,000' })).toBeAttached()
  await expect(cart.getByText('품목 합계 ₩158,000')).toBeVisible()

  const decrement = cart.getByRole('button', { name: '미니멀 울 블레이저 블랙 / M 수량 줄이기' })
  await expect(decrement).toBeDisabled()
  await expect(cart.getByRole('spinbutton', { name: '미니멀 울 블레이저 블랙 / M 수량' })).toHaveAttribute('min', '1')
  await expect(cart.getByRole('spinbutton', { name: '미니멀 울 블레이저 블랙 / M 수량' })).toHaveAttribute('step', '1')
  const increment = cart.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' })
  await increment.click()
  await expect(cart.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('3')
  await expect(increment).toBeFocused()
  await expect(cart.getByText('품목 합계 ₩237,000')).toBeVisible()
  await expect(cart.getByText('합계 ₩449,000')).toBeVisible()
  expect(writes).toEqual([{ method: 'PATCH', skuId: 22, body: { quantity: 3 } }])

  await cart.getByRole('button', { name: '계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 셔츠 삭제' }).click()
  await expect(cart.locator('.cart-table tbody tr')).toHaveCount(2)
  expect(writes).toEqual([
    { method: 'PATCH', skuId: 22, body: { quantity: 3 } },
    { method: 'DELETE', skuId: 33 },
  ])
  await expect(cart.getByRole('checkbox')).toHaveCount(0)
  await cart.getByRole('button', { name: '전체 상품 주문하기' }).click()
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.locator('.order-item-card')).toHaveCount(2)
})

test('rapid stepper clicks submit each successive quantity without losing input', async ({ page }) => {
  let items = structuredClone(ITEMS)
  let releaseFirst
  let activeRequests = 0
  let maxConcurrentRequests = 0
  const writes = []
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ json: { items } }))
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    const skuId = Number(route.request().url().split('/').at(-1))
    const { quantity } = route.request().postDataJSON()
    activeRequests += 1
    maxConcurrentRequests = Math.max(maxConcurrentRequests, activeRequests)
    writes.push({ method: 'PATCH', skuId, body: { quantity } })
    if (writes.length === 1) await new Promise(resolve => { releaseFirst = resolve })
    items = items.map(item => item.skuId === skuId ? { ...item, quantity } : item)
    activeRequests -= 1
    await route.fulfill({ json: {} })
  })
  await page.goto('/cart')

  const increment = page.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' })
  await increment.evaluate(button => {
    button.click()
    button.click()
  })

  await expect.poll(() => typeof releaseFirst).toBe('function')
  await expect.poll(() => writes).toHaveLength(1)
  expect(maxConcurrentRequests).toBe(1)
  releaseFirst()
  await expect.poll(() => writes).toHaveLength(2)
  expect(maxConcurrentRequests).toBe(1)
  expect(writes.slice(0, 2)).toEqual([
    { method: 'PATCH', skuId: 22, body: { quantity: 3 } },
    { method: 'PATCH', skuId: 22, body: { quantity: 4 } },
  ])
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('4')
})

test('logout cancels deferred quantity writes from the previous identity', async ({ page }) => {
  let releaseFirst
  let currentUser = USER
  const writes = []
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: currentUser }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ json: { items: ITEMS } }))
  await page.route(`${GW}/v1/auth/logout`, route => route.fulfill({ json: {} }))
  await page.route(`${GW}/v1/auth/login`, route => {
    currentUser = USER_B
    return route.fulfill({ json: {} })
  })
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    writes.push(route.request().postDataJSON())
    if (writes.length === 1) await new Promise(resolve => { releaseFirst = resolve })
    await route.fulfill({ json: {} })
  })
  await page.goto('/cart')

  const increment = page.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' })
  await increment.evaluate(button => {
    button.click()
    button.click()
  })
  await expect.poll(() => typeof releaseFirst).toBe('function')
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.getByRole('button', { name: '로그인' }).click()
  await page.getByRole('textbox', { name: '이메일' }).fill(USER_B.email)
  await page.getByRole('textbox', { name: '비밀번호', exact: true }).fill('password123')
  await page.locator('.modal button[type="submit"]').click()
  await expect(page.getByText('구매자 B님')).toBeVisible()
  releaseFirst()

  await page.waitForTimeout(200)
  expect(writes).toEqual([{ quantity: 3 }])
})

test('a stale cart reload cannot overwrite a newer optimistic quantity', async ({ page }) => {
  let items = structuredClone(ITEMS)
  let cartGets = 0
  let releaseStaleReload
  let staleReloadNumber = Number.POSITIVE_INFINITY
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, async route => {
    cartGets += 1
    const snapshot = structuredClone(items)
    if (cartGets === staleReloadNumber) await new Promise(resolve => { releaseStaleReload = resolve })
    await route.fulfill({ json: { items: snapshot } })
  })
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    const skuId = Number(route.request().url().split('/').at(-1))
    const { quantity } = route.request().postDataJSON()
    items = items.map(item => item.skuId === skuId ? { ...item, quantity } : item)
    await route.fulfill({ json: {} })
  })
  await page.goto('/cart')
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toBeVisible()
  await page.waitForTimeout(100)
  const initialCartGets = cartGets
  staleReloadNumber = initialCartGets + 1

  const increment = page.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' })
  await increment.click()
  await expect.poll(() => typeof releaseStaleReload).toBe('function')
  await increment.click()
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('4')
  await expect.poll(() => cartGets).toBe(initialCartGets + 2)
  releaseStaleReload()

  await page.waitForTimeout(200)
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('4')
})

test('logout resets a failed cart to the existing unauthenticated empty state', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ status: 503, json: {} }))
  await page.route(`${GW}/v1/auth/logout`, route => route.fulfill({ json: {} }))
  await page.goto('/cart')
  await expect(page.getByRole('alert')).toContainText('장바구니를 불러오지 못했어요.')

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.evaluate(() => {
    window.history.pushState(null, '', '/cart')
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
  })
  await expect(page).toHaveURL(/\/cart$/)

  await expect(page.getByRole('heading', { name: '장바구니가 비어 있습니다.' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('an older delete reload cannot overwrite a newer quantity reconciliation', async ({ page }) => {
  let items = structuredClone(ITEMS)
  let releaseQuantity
  let cartGets = 0
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, async route => {
    cartGets += 1
    const snapshot = structuredClone(items)
    await route.fulfill({ json: { items: snapshot } })
  })
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    const skuId = Number(route.request().url().split('/').at(-1))
    if (route.request().method() === 'PATCH') {
      const { quantity } = route.request().postDataJSON()
      await new Promise(resolve => { releaseQuantity = resolve })
      items = items.map(item => item.skuId === skuId ? { ...item, quantity } : item)
    } else {
      items = items.filter(item => item.skuId !== skuId)
    }
    await route.fulfill({ json: {} })
  })
  await page.goto('/cart')
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toBeVisible()
  const initialCartGets = cartGets

  await page.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' }).click()
  await expect.poll(() => typeof releaseQuantity).toBe('function')
  await page.getByRole('button', { name: '계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 셔츠 삭제' }).click()
  await expect.poll(() => cartGets).toBe(initialCartGets)
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(2)
  await page.waitForTimeout(200)
  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('3')
  releaseQuantity()
  await expect.poll(() => cartGets).toBe(initialCartGets + 1)

  await expect(page.getByRole('spinbutton', { name: '소프트 니트 카디건 오트밀 / L 수량' })).toHaveValue('3')
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(2)
})

test('successful paid-cart clearing resets an invalidated loading state', async ({ page }) => {
  await page.addInitScript(context => sessionStorage.setItem('paymentAttempt', JSON.stringify(context)), {
    paymentRequestId: 'attempt-1', source: 'cart', orderItems: ITEMS,
  })
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, async () => new Promise(() => {}))
  await page.route(`${GW}/v1/payment-attempts/attempt-1/confirm`, route => route.fulfill({
    json: { paymentRequestId: 'attempt-1', paymentKey: 'key-1', amount: 370000, status: 'COMPLETED' },
  }))
  await page.route(`${GW}/v1/cart`, route => {
    if (route.request().method() === 'DELETE') return route.fulfill({ json: {} })
    return new Promise(() => {})
  })
  await page.goto('/payment/success?orderId=attempt-1&paymentKey=key-1&amount=370000')
  await expect(page.getByRole('heading', { name: /결제 완료/ })).toBeVisible()

  await page.getByRole('button', { name: '쇼핑 계속하기' }).click()
  await page.getByRole('button', { name: '장바구니(0)' }).click()
  await expect(page.getByRole('heading', { name: '장바구니가 비어 있습니다.' })).toBeVisible()
  await expect(page.getByRole('status')).toHaveCount(0)
})

test('a repeated same-user auth response preserves queued quantities', async ({ page }) => {
  let authRequests = 0
  let cartGets = 0
  let releaseRepeatedAuth
  let releaseFirstPatch
  const writes = []
  await page.route(`${GW}/v1/auth/me`, async route => {
    authRequests += 1
    if (authRequests === 2) await new Promise(resolve => { releaseRepeatedAuth = resolve })
    await route.fulfill({ json: USER })
  })
  await page.route(`${GW}/v1/cart`, route => {
    cartGets += 1
    return route.fulfill({ json: { items: ITEMS } })
  })
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    writes.push(route.request().postDataJSON())
    if (writes.length === 1) await new Promise(resolve => { releaseFirstPatch = resolve })
    await route.fulfill({ json: {} })
  })
  await page.goto('/cart')

  const increment = page.getByRole('button', { name: '소프트 니트 카디건 오트밀 / L 수량 늘리기' })
  await increment.click()
  await expect.poll(() => typeof releaseFirstPatch).toBe('function')
  await expect.poll(() => typeof releaseRepeatedAuth).toBe('function')
  const initialCartGets = cartGets
  releaseRepeatedAuth()
  await expect.poll(() => authRequests).toBe(2)
  await page.waitForTimeout(100)
  expect(cartGets).toBe(initialCartGets)
  await increment.click()
  releaseFirstPatch()

  await expect.poll(() => writes).toHaveLength(2)
  expect(writes).toEqual([{ quantity: 3 }, { quantity: 4 }])
})

test('logout prevents delayed delete reconciliation from loading another identity cart', async ({ page }) => {
  let releaseDelete
  let cartGets = 0
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, route => {
    cartGets += 1
    return route.fulfill({ json: { items: ITEMS } })
  })
  await page.route(`${GW}/v1/cart/items/*`, async route => {
    await new Promise(resolve => { releaseDelete = resolve })
    await route.fulfill({ json: {} })
  })
  await page.route(`${GW}/v1/auth/logout`, route => route.fulfill({ json: {} }))
  await page.goto('/cart')

  await page.getByRole('button', { name: '미니멀 울 블레이저 삭제' }).click()
  await expect.poll(() => typeof releaseDelete).toBe('function')
  const initialCartGets = cartGets
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/$/)
  releaseDelete()
  await page.waitForTimeout(200)

  expect(cartGets).toBe(initialCartGets)
})

test('quantity controls distinguish variants of the same product', async ({ page }) => {
  const variants = [
    { ...ITEMS[0], skuId: 101, itemName: '기본 셔츠', optionSummary: '블랙 / M' },
    { ...ITEMS[0], skuId: 102, itemName: '기본 셔츠', optionSummary: '블랙 / L' },
  ]
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ json: { items: variants } }))
  await page.goto('/cart')

  await expect(page.getByRole('button', { name: '기본 셔츠 블랙 / M 수량 늘리기' })).toBeVisible()
  await expect(page.getByRole('button', { name: '기본 셔츠 블랙 / L 수량 늘리기' })).toBeVisible()
})
