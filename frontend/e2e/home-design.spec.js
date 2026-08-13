import { test, expect } from '@playwright/test'
import { resolveE2EUrls } from './helpers/urls.js'

const { gateway: GW } = resolveE2EUrls()
const categories = [
  { id: 10, name: '아우터', children: [] },
  { id: 20, name: '니트', children: [] },
]

const products = [
  { id: 101, name: '미니멀 울 블레이저', minPrice: 149000, thumbnailUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=' },
  { id: 102, name: '리브드 카라 니트', minPrice: 79000, thumbnailUrl: null },
  { id: 103, name: '소프트 집업 니트', minPrice: 89000, thumbnailUrl: null },
  { id: 104, name: '텍스처 니트 베스트', minPrice: 69000, thumbnailUrl: null },
  { id: 105, name: '울 니트 카디건', minPrice: 99000, thumbnailUrl: null },
  { id: 106, name: '브이넥 니트 풀오버', minPrice: 72000, thumbnailUrl: null },
]

const defaultProductsByCategory = {
  10: [products[0]],
  20: [products[1]],
}

async function mockStorefront(page, {
  productsByCategory = defaultProductsByCategory,
  onProductsRequest = () => {},
} = {}) {
  await page.route(`${GW}/v1/auth/me`, route =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }))
  await page.route(`${GW}/v1/categories`, route =>
    route.fulfill({ contentType: 'application/json', json: categories }))
  await page.route(`${GW}/v1/categories/*/products*`, route => {
    const categoryId = Number(new URL(route.request().url()).pathname.split('/')[3])
    onProductsRequest(categoryId)
    return route.fulfill({
      contentType: 'application/json',
      json: { content: productsByCategory[categoryId] ?? [] },
    })
  })
  await page.route(`${GW}/v1/products/*`, route => {
    const id = Number(new URL(route.request().url()).pathname.split('/').at(-1))
    const product = products.find(item => item.id === id) ?? products[0]
    return route.fulfill({
      contentType: 'application/json',
      json: {
        ...product,
        category: [{ id: 10, name: '아우터' }],
        images: [],
        skus: [],
      },
    })
  })
}

test('desktop home aggregates every leaf category into the all-products view', async ({ page }) => {
  const requestedCategories = []
  await mockStorefront(page, { onProductsRequest: id => requestedCategories.push(id) })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/')

  await expect(page.getByRole('heading', { name: /새로운 균형/ })).toBeVisible()
  await expect(page.getByRole('heading', { name: '이번 주 신상품' })).toBeVisible()
  await expect(page.locator('.grid .card')).toHaveCount(2)
  expect(requestedCategories.sort((a, b) => a - b)).toEqual([10, 20])
  await expect(page.getByRole('button', { name: '전체' })).toHaveAttribute('aria-current', 'true')
  await expect(page.getByRole('heading', { name: '지금 추천하는 스타일' })).toBeVisible()
  await expect(page.getByRole('contentinfo')).toContainText('fashion-shop')
})

test('all-empty composition keeps its action and retries every leaf category', async ({ page }) => {
  const requestCounts = new Map()
  await mockStorefront(page, {
    productsByCategory: { 10: [], 20: [] },
    onProductsRequest: id => requestCounts.set(id, (requestCounts.get(id) ?? 0) + 1),
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '아직 상품이 없어요' })).toBeVisible()
  await expect(page.getByText('다른 카테고리를 둘러보거나 검색어를 바꿔보세요.')).toBeVisible()
  await page.getByRole('button', { name: '전체 상품 보기' }).click()
  await expect.poll(() => requestCounts.get(10)).toBe(2)
  await expect.poll(() => requestCounts.get(20)).toBe(2)
})

test('category loading error retries the category tree before products', async ({ page }) => {
  let categoryRequests = 0
  await page.route(`${GW}/v1/auth/me`, route =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }))
  await page.route(`${GW}/v1/categories`, route => {
    categoryRequests += 1
    if (categoryRequests <= 2) {
      return route.fulfill({ status: 503, contentType: 'application/json', json: { message: 'unavailable' } })
    }
    return route.fulfill({ contentType: 'application/json', json: categories })
  })
  await page.route(`${GW}/v1/categories/*/products*`, route => {
    const categoryId = Number(new URL(route.request().url()).pathname.split('/')[3])
    return route.fulfill({ contentType: 'application/json', json: { content: defaultProductsByCategory[categoryId] ?? [] } })
  })
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '상품을 불러오지 못했어요' })).toBeVisible()
  await page.getByRole('button', { name: '다시 시도' }).click()

  await expect(page.getByRole('heading', { name: '이번 주 신상품' })).toBeVisible()
  await expect(page.locator('.grid .card')).toHaveCount(2)
  expect(categoryRequests).toBe(3)
})

test('desktop product search filters the loaded aggregate by product name', async ({ page }) => {
  await mockStorefront(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/')

  const search = page.getByRole('searchbox', { name: '상품 검색' })
  await search.fill('카라 니트')

  await expect(page.locator('.grid .card')).toHaveCount(1)
  await expect(page.locator('.grid .card')).toContainText('리브드 카라 니트')
})

test('mobile search is keyboard accessible, reveals the field, and keeps the 390 layout contained', async ({ page }) => {
  await mockStorefront(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  const searchButton = page.getByRole('button', { name: '상품 검색' })
  await searchButton.focus()
  await page.keyboard.press('Enter')
  const search = page.getByRole('searchbox', { name: '상품 검색' })
  await expect(search).toBeVisible()
  await expect(search).toBeFocused()
  await search.fill('블레이저')

  await expect(page.locator('.grid .card')).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('mobile recommended cards are reachable by horizontal touch and keyboard scrolling without body overflow', async ({ page }) => {
  await mockStorefront(page, { productsByCategory: { 10: products.slice(0, 3), 20: products.slice(3) } })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  const rail = page.locator('.recommended-grid')
  const cards = rail.locator('.card')
  await expect(cards).toHaveCount(4)
  await expect(rail).toHaveCSS('overflow-x', 'auto')
  await expect(rail).toHaveAttribute('tabindex', '0')

  await rail.focus()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => rail.evaluate(element => element.scrollLeft)).toBeGreaterThan(0)
  await cards.last().focus()
  await expect(cards.last()).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('product buttons do not repeat visible product names through image alt text', async ({ page }) => {
  await mockStorefront(page)
  await page.goto('/')

  const firstCard = page.locator('.grid .card').first()
  await expect(firstCard.locator('img')).toHaveAttribute('alt', '')
})

test('opening a product applies the adopted Gallery typography and keeps the non-home header treatment', async ({ page }) => {
  await mockStorefront(page)
  await page.goto('/')
  await page.locator('.grid .card').first().click()

  const detailHeading = page.getByRole('main', { name: '상품 상세' }).getByRole('heading', { level: 1 })
  await expect(detailHeading).toHaveText('미니멀 울 블레이저')
  await expect(detailHeading).toHaveCSS('font-weight', '600')
  await expect(page.locator('.announcement')).toHaveCount(0)
  await expect(page.locator('.navbar')).not.toHaveCSS('height', '96px')
})
