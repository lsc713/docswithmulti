import { test, expect } from '@playwright/test'

const GW = 'http://localhost:8000'
const ARTIFACTS = 'artifacts/t_858a6d74-cart-redesign'
const USER = { userId: 7, email: 'buyer@example.com', name: '구매자', role: 'USER' }
const BASE_ITEMS = [
  { skuId: 11, productId: 1, itemName: '미니멀 울 블레이저', optionSummary: '블랙 / M', unitPrice: 149000, quantity: 1 },
  { skuId: 22, productId: 2, itemName: '소프트 니트 카디건', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2 },
  { skuId: 33, productId: 3, itemName: '계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 셔츠', optionSummary: '화이트 / Extra Long Variant Label', unitPrice: 63000, quantity: 1 },
]

async function mockIdentity(page) {
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ json: USER }))
}

async function cartResponse(page, response) {
  await page.unroute(`${GW}/v1/cart`)
  await page.route(`${GW}/v1/cart`, route => route.fulfill(response))
}

test('visualizes cart states and keeps responsive accessibility constraints', async ({ page }) => {
  const consoleErrors = []
  const unexpectedNetworkErrors = []
  page.on('console', message => {
    if (message.type() === 'error' && !message.text().includes('503')) consoleErrors.push(message.text())
  })
  page.on('requestfailed', request => unexpectedNetworkErrors.push(`${request.method()} ${request.url()}`))
  page.on('response', response => {
    if (response.status() >= 400 && !(response.url() === `${GW}/v1/cart` && response.status() === 503)) {
      unexpectedNetworkErrors.push(`${response.status()} ${response.url()}`)
    }
  })
  await mockIdentity(page)

  let releaseCart
  await page.route(`${GW}/v1/cart`, async route => {
    await new Promise(resolve => { releaseCart = resolve })
    await route.fulfill({ json: { items: BASE_ITEMS } })
  })
  await page.setViewportSize({ width: 1440, height: 1420 })
  await page.goto('/cart')
  await expect(page.getByRole('status')).toContainText('장바구니를 불러오는 중입니다.')
  await expect.poll(() => typeof releaseCart).toBe('function')
  releaseCart()
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(3)
  await page.screenshot({ path: `${ARTIFACTS}/cart-desktop-1440.png`, fullPage: true })

  await cartResponse(page, { json: { items: BASE_ITEMS } })
  await page.setViewportSize({ width: 390, height: 1420 })
  await page.reload()
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(await page.locator('.cart button:visible').evaluateAll(buttons =>
    buttons.every(button => button.getBoundingClientRect().width >= 44 && button.getBoundingClientRect().height >= 44)
  )).toBe(true)
  await page.screenshot({ path: `${ARTIFACTS}/cart-mobile-390.png`, fullPage: true })

  await cartResponse(page, { json: { items: [] } })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.reload()
  await expect(page.getByRole('heading', { name: '장바구니가 비어 있습니다.' })).toBeVisible()
  await page.screenshot({ path: `${ARTIFACTS}/cart-empty-1440.png`, fullPage: true })

  await cartResponse(page, { status: 503, json: { code: 'CART_UNAVAILABLE' } })
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('장바구니를 불러오지 못했어요.')
  await page.screenshot({ path: `${ARTIFACTS}/cart-error-1440.png`, fullPage: true })

  const longItems = Array.from({ length: 20 }, (_, index) => ({
    ...BASE_ITEMS[index % BASE_ITEMS.length],
    skuId: 100 + index,
    itemName: `계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 상품 ${index + 1}`,
    optionSummary: `오트밀 / Extra Long Variant Label ${index + 1}`,
  }))
  await cartResponse(page, { json: { items: longItems } })
  await page.reload()
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(20)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: `${ARTIFACTS}/cart-long-content-1440.png`, fullPage: true })

  expect(consoleErrors).toEqual([])
  expect(unexpectedNetworkErrors).toEqual([])
})

test('unauthenticated direct cart keeps the existing empty-state contract', async ({ page }) => {
  await page.route(`${GW}/v1/auth/me`, route => route.fulfill({ status: 401, json: {} }))
  await page.goto('/cart')

  await expect(page.getByRole('heading', { name: '장바구니가 비어 있습니다.' })).toBeVisible()
  await expect(page.getByRole('status')).toHaveCount(0)
})

test('cart never overflows at tablet and small-laptop widths', async ({ page }) => {
  await mockIdentity(page)
  await cartResponse(page, { json: { items: BASE_ITEMS } })

  for (const width of [1025, 1100, 1240]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/cart')
    await expect(page.locator('.cart-table tbody tr')).toHaveCount(3)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const table = await page.locator('.cart-table').boundingBox()
    const summary = await page.locator('.cart-summary').boundingBox()
    expect(table.x + table.width).toBeLessThanOrEqual(width)
    expect(summary.x + summary.width).toBeLessThanOrEqual(width)
    const separated = table.x + table.width <= summary.x
      || summary.x + summary.width <= table.x
      || table.y + table.height <= summary.y
      || summary.y + summary.height <= table.y
    expect(separated).toBe(true)
  }
})

test('authenticated cart keeps order history and logout reachable without navbar overflow', async ({ page }) => {
  await mockIdentity(page)
  await cartResponse(page, { json: { items: BASE_ITEMS } })

  for (const width of [390, 1025]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/cart')
    const navigation = page.getByRole('navigation', { name: '주요 메뉴' })
    await expect(navigation.getByRole('button', { name: '주문내역' })).toBeVisible()
    await expect(navigation.getByRole('button', { name: '로그아웃' })).toBeVisible()
    await expect(navigation.getByRole('button', { name: '장바구니(4)' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
})
