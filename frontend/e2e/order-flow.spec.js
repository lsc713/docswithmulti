import { test, expect } from '@playwright/test'
import { resolveE2EUrls } from './helpers/urls.js'

const { gateway: GW } = resolveE2EUrls()
const SESSION_KEY = 'fashion-shop:order-flow'
const USER_A = { userId: 7, email: 'buyer-a@example.com', name: '구매자 A', role: 'USER' }
const USER_B = { userId: 8, email: 'buyer-b@example.com', name: '구매자 B', role: 'USER' }
const ORDER_ITEMS = [
  { skuId: 11, productId: 1, itemName: '미니멀 울 블레이저', optionSummary: '블랙 / M', unitPrice: 149000, quantity: 1 },
  { skuId: 22, productId: 2, itemName: '소프트 니트 카디건', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2 },
  { skuId: 33, productId: 3, itemName: '오버사이즈 코튼 셔츠', optionSummary: '화이트 / Free', unitPrice: 63000, quantity: 1 },
]

function storedFlow(orderItems = ORDER_ITEMS, ownerUserId = USER_A.userId, source = 'cart') {
  return { ownerUserId, flowState: { orderItems, source } }
}

async function seedSession(page, value) {
  await page.addInitScript(({ key, stored }) => {
    sessionStorage.setItem(key, JSON.stringify(stored))
  }, { key: SESSION_KEY, stored: value })
}

async function mockIdentity(page, user = USER_A) {
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: user }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({ json: { items: [] } }))
}

async function countUnsafePosts(context) {
  const calls = { orders: 0, payments: 0 }
  await context.route(`${GW}/v1/orders`, route => {
    calls.orders += 1
    return route.abort()
  })
  await context.route(`${GW}/v1/payments`, route => {
    calls.payments += 1
    return route.abort()
  })
  return calls
}

test('tampered preview stays blocked on checkout and payment with zero order/payment calls', async ({ page, context }) => {
  const calls = await countUnsafePosts(context)
  await seedSession(page, storedFlow([{ ...ORDER_ITEMS[0], unitPrice: 1 }]))
  await mockIdentity(page)

  await page.goto('/checkout')
  await expect(page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' })).toBeVisible()
  await expect(page.getByTestId('grand-total')).toHaveText('₩1')
  await expect(page.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()
  await expect(page.getByRole('alert')).toContainText('서버 재검증 미지원으로 결제 불가')

  await page.goto('/payment')
  await expect(page.getByRole('heading', { name: '결제 수단을 확인해 주세요' })).toBeVisible()
  await expect(page.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()
  await expect(page.getByRole('alert')).toContainText('서버 재검증 미지원으로 결제 불가')
  expect(calls).toEqual({ orders: 0, payments: 0 })
})

test('timeout/retry/remount and multi-tab-like attempts can never submit payment', async ({ page, context }) => {
  const calls = await countUnsafePosts(context)
  await seedSession(page, storedFlow())
  await mockIdentity(page)
  await page.goto('/payment')

  const submit = page.getByRole('button', { name: '결제 연동 준비 중' }).first()
  await expect(submit).toBeDisabled()
  await submit.evaluate(button => button.click())
  await page.reload()
  await expect(page.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()

  const secondTab = await context.newPage()
  await seedSession(secondTab, storedFlow())
  await mockIdentity(secondTab)
  await secondTab.goto('/payment')
  await expect(secondTab.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()
  await secondTab.getByRole('button', { name: '결제 연동 준비 중' }).first().evaluate(button => button.click())
  expect(calls).toEqual({ orders: 0, payments: 0 })
})

test('user B and unauthenticated direct URLs cannot restore user A state', async ({ page }) => {
  await seedSession(page, storedFlow())
  await mockIdentity(page, USER_B)
  await page.goto('/checkout')
  await expect(page.getByRole('heading', { name: '주문할 상품이 없어요' })).toBeVisible()
  await expect(page.locator('.order-item-card')).toHaveCount(0)

  await page.unroute(`${GW}/v1/auth/me`)
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ status: 401, json: {} }))
  await page.evaluate(({ key, stored }) => sessionStorage.setItem(key, JSON.stringify(stored)), {
    key: SESSION_KEY,
    stored: storedFlow(),
  })
  await page.goto('/payment')
  await expect(page.getByRole('heading', { name: '결제할 주문이 없어요' })).toBeVisible()
  await expect(page.locator('.order-item-card')).toHaveCount(0)
})

test('transient identity failure hides but preserves the owned preview for a later verified retry', async ({ page }) => {
  await seedSession(page, storedFlow())
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ status: 503, json: {} }))
  await page.goto('/checkout')

  await expect(page.getByRole('heading', { name: '주문할 상품이 없어요' })).toBeVisible()
  await expect.poll(() => page.evaluate(key => sessionStorage.getItem(key), SESSION_KEY)).not.toBeNull()
})

test('logout clears owned order state, order view state, and cart before another identity', async ({ page }) => {
  let currentUser = USER_A
  await seedSession(page, storedFlow())
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: currentUser }))
  await page.route(`${GW}/v1/cart`, route => route.fulfill({
    json: { items: currentUser.userId === USER_A.userId ? ORDER_ITEMS : [] },
  }))
  await page.route(`${GW}/v1/auth/logout`, route => route.fulfill({ json: {} }))
  await page.goto('/checkout')
  await expect(page.locator('.order-item-card')).toHaveCount(3)

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
  await expect.poll(() => page.evaluate(key => sessionStorage.getItem(key), SESSION_KEY)).toBeNull()
  currentUser = USER_B
  await page.goto('/checkout')
  await expect(page.getByRole('heading', { name: '주문할 상품이 없어요' })).toBeVisible()
  await expect(page.locator('.navbar-right')).toContainText('구매자 B님')
  await expect(page.getByRole('button', { name: '장바구니(0)' })).toBeVisible()
})

test('failed logout keeps the authenticated UI and owned order state intact', async ({ page }) => {
  await seedSession(page, storedFlow())
  await mockIdentity(page)
  await page.route(`${GW}/v1/auth/logout`, route => route.fulfill({ status: 503, json: {} }))
  await page.goto('/checkout')

  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.locator('.navbar-right')).toContainText('구매자 A님')
  await expect.poll(() => page.evaluate(key => sessionStorage.getItem(key), SESSION_KEY)).not.toBeNull()
})

test('one, three, and twelve-item previews remain responsive and payment back returns to checkout', async ({ page }) => {
  await mockIdentity(page)
  await page.goto('/checkout')
  for (const orderItems of [
    [ORDER_ITEMS[0]],
    ORDER_ITEMS,
    Array.from({ length: 12 }, (_, index) => ({
      ...ORDER_ITEMS[index % ORDER_ITEMS.length],
      skuId: 100 + index,
      itemName: `계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 상품 ${index + 1}`,
      optionSummary: `오트밀 / Extra Long Variant Label ${index + 1}`,
    })),
  ]) {
    await page.evaluate(({ key, stored }) => sessionStorage.setItem(key, JSON.stringify(stored)), {
      key: SESSION_KEY,
      stored: storedFlow(orderItems),
    })
    await page.goto('/checkout')
    await expect(page.locator('.order-item-card')).toHaveCount(orderItems.length)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }

  await page.goto('/payment')
  await page.getByRole('button', { name: '← 주문서로 돌아가기 · 상태 유지' }).click()
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.locator('.order-item-card')).toHaveCount(12)
})

test('ProductDetail buy still opens an owned checkout preview and back returns to detail', async ({ page }) => {
  const product = {
    id: 1, name: '미니멀 울 블레이저', minPrice: 149000,
    category: [{ id: 10, name: '아우터' }], images: [],
    variantOptions: [{ attribute: '색상', values: ['블랙'] }, { attribute: '사이즈', values: ['M'] }],
    skus: [{ ...ORDER_ITEMS[0], skuCode: 'BLZ-101', price: 149000, availableQty: 4, variant: { 색상: '블랙', 사이즈: 'M' } }],
  }
  await mockIdentity(page)
  await page.route(`${GW}/v1/categories`, route => route.fulfill({ json: [{ id: 10, name: '아우터', children: [] }] }))
  await page.route(`${GW}/v1/categories/*/products*`, route => route.fulfill({ json: { content: [{ id: 1, name: product.name, minPrice: 149000 }] } }))
  await page.route(`${GW}/v1/products/1`, route => route.fulfill({ json: product }))
  await page.goto('/')
  await page.locator('.grid .card').first().click()
  const detail = page.getByRole('main', { name: '상품 상세' })
  await detail.getByRole('button', { name: '색상 · 선택 안 됨' }).click()
  await detail.getByRole('option', { name: '블랙', exact: true }).click()
  await detail.getByRole('button', { name: '사이즈 · 선택 안 됨' }).click()
  await detail.getByRole('option', { name: 'M', exact: true }).click()
  await detail.getByRole('button', { name: '구매하기' }).click()
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()
  await page.getByRole('button', { name: '상품 또는 장바구니로 돌아가기' }).click()
  await expect(page.getByRole('main', { name: '상품 상세' })).toBeVisible()
})
