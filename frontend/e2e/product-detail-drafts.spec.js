import { test, expect } from '@playwright/test'

const PRODUCT_ID = 101
const SCREENSHOT_DIR = 'test-results/product-detail-drafts/screenshots'

const draftNames = {
  editorial: '에디토리얼',
  gallery: '갤러리',
  compact: '컴팩트',
}

const categories = [
  { id: 10, name: '아우터', children: [] },
]

const productSummary = {
  id: PRODUCT_ID,
  name: '미니멀 울 블레이저',
  minPrice: 149000,
  thumbnailUrl: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
}

const product = {
  ...productSummary,
  category: [
    { id: 1, name: '여성' },
    { id: 10, name: '아우터' },
  ],
  images: [
    {
      id: 1001,
      url: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
      sortOrder: 0,
    },
    {
      id: 1002,
      url: 'data:image/gif;base64,R0lGODlhAQABAAAAACw=',
      sortOrder: 1,
    },
  ],
  skus: [
    {
      skuId: 501,
      skuCode: 'WOOL-BLACK-M',
      optionSummary: '블랙 / M',
      price: 149000,
      availableQty: 7,
    },
    {
      skuId: 502,
      skuCode: 'WOOL-CREAM-M',
      optionSummary: '크림 / M',
      price: 149000,
      availableQty: 0,
    },
  ],
}

async function mockStorefront(page, { productHandler } = {}) {
  await page.route('http://localhost:8000/v1/auth/me', route =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{}' }))
  await page.route('http://localhost:8000/v1/categories', route =>
    route.fulfill({ contentType: 'application/json', json: categories }))
  await page.route('http://localhost:8000/v1/categories/*/products*', route =>
    route.fulfill({ contentType: 'application/json', json: { content: [productSummary] } }))
  await page.route(`http://localhost:8000/v1/products/${PRODUCT_ID}`, route => {
    if (productHandler) return productHandler(route)
    return route.fulfill({ contentType: 'application/json', json: product })
  })
}

function draftUrl(draft) {
  return `/?detailDraft=${draft}&product=${PRODUCT_ID}`
}

function draftMain(page, draft) {
  return page.getByRole('main', { name: `${draftNames[draft]} 상품 상세` })
}

for (const [draft, accessibleName] of Object.entries(draftNames)) {
  test(`${draft} query renders the requested draft with an accessible draft name`, async ({ page }) => {
    await mockStorefront(page)
    await page.goto(draftUrl(draft))

    const main = draftMain(page, draft)
    await expect(main).toBeVisible()
    await expect(main.getByRole('heading', { name: product.name, level: 1 })).toBeVisible()
    await expect(main).toHaveAccessibleName(`${accessibleName} 상품 상세`)
  })

  test(`${draft} option selection enables quantity and the existing purchase callbacks`, async ({ page }) => {
    await mockStorefront(page)
    await page.goto(draftUrl(draft))

    const main = draftMain(page, draft)
    await main.getByRole('radio', { name: /블랙 \/ M/ }).click()

    const quantity = main.getByRole('spinbutton', { name: '수량' })
    await expect(quantity).toBeEnabled()
    await quantity.fill('2')

    const buy = main.getByRole('button', { name: '구매하기' })
    const addToCart = main.getByRole('button', { name: '장바구니 담기' })
    await expect(buy).toBeEnabled()
    await expect(addToCart).toBeEnabled()

    await buy.click()
    await expect(page.getByRole('heading', { name: '로그인', level: 1 })).toBeVisible()
    await page.getByRole('button', { name: '닫기' }).click()

    await addToCart.click()
    await expect(page.getByRole('heading', { name: '로그인', level: 1 })).toBeVisible()
  })
}

test('a zero-stock option is disabled and announced as sold out', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('editorial'))

  const soldOut = draftMain(page, 'editorial').getByRole('radio', { name: /크림 \/ M.*품절/ })
  await expect(soldOut).toBeDisabled()
  await expect(draftMain(page, 'editorial').getByText('품절', { exact: true })).toBeVisible()
})

test('a draft exposes a loading state while GET /v1/products/{id} is pending', async ({ page }) => {
  let finishRequest
  await mockStorefront(page, {
    productHandler: route => new Promise(resolve => {
      finishRequest = () => {
        route.fulfill({ contentType: 'application/json', json: product }).then(resolve)
      }
    }),
  })

  try {
    await page.goto(draftUrl('gallery'))
    await expect(page.getByRole('status', { name: '상품 상세 불러오는 중' })).toBeVisible()
  } finally {
    await finishRequest?.()
  }
})

test('a 500 error offers retry and renders the product after retrying', async ({ page }) => {
  let attempts = 0
  await mockStorefront(page, {
    productHandler: route => {
      attempts += 1
      if (attempts === 1) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          json: { message: '상품을 불러오지 못했어요' },
        })
      }
      return route.fulfill({ contentType: 'application/json', json: product })
    },
  })
  await page.goto(draftUrl('compact'))

  await expect(page.getByRole('alert')).toContainText('상품을 불러오지 못했어요')
  await page.getByRole('button', { name: '다시 시도' }).click()

  await expect(draftMain(page, 'compact').getByRole('heading', { name: product.name })).toBeVisible()
})

test('a 404 response renders the product not-found state', async ({ page }) => {
  await mockStorefront(page, {
    productHandler: route => route.fulfill({
      status: 404,
      contentType: 'application/json',
      json: { code: 'PRODUCT_NOT_FOUND', message: '상품을 찾을 수 없습니다' },
    }),
  })
  await page.goto(draftUrl('editorial'))

  await expect(page.getByRole('heading', { name: '상품을 찾을 수 없어요' })).toBeVisible()
})

test('an in-stock option can be selected using only the keyboard', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const option = draftMain(page, 'gallery').getByRole('radio', { name: /블랙 \/ M/ })
  await option.focus()
  await page.keyboard.press('Space')

  await expect(option).toBeChecked()
  await expect(draftMain(page, 'gallery').getByRole('spinbutton', { name: '수량' })).toBeEnabled()
})

test('leaving a draft removes only draft parameters and preserves unrelated URL state', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(`${draftUrl('editorial')}&campaign=review#details`)

  await draftMain(page, 'editorial').getByRole('button', { name: /컬렉션으로/ }).click()

  await expect(page.getByRole('heading', { name: /새로운 균형/ })).toBeVisible()
  const url = new URL(page.url())
  expect(url.searchParams.get('campaign')).toBe('review')
  expect(url.searchParams.has('detailDraft')).toBe(false)
  expect(url.searchParams.has('product')).toBe(false)
  expect(url.hash).toBe('#details')
})

for (const [draft] of Object.entries(draftNames)) {
  for (const viewport of [
    { name: '1440', width: 1440, height: 1000 },
    { name: '390', width: 390, height: 844 },
  ]) {
    test(`${draft} produces the ${viewport.name}px draft screenshot`, async ({ page }) => {
      await mockStorefront(page)
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await page.goto(draftUrl(draft))
      await page.waitForLoadState('networkidle')

      await page.screenshot({
        path: `${SCREENSHOT_DIR}/${draft}-${viewport.name}.png`,
        fullPage: true,
      })

      await expect(draftMain(page, draft)).toBeVisible()
      if (viewport.width === 390) {
        expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
      }
    })
  }
}

test('the production home-to-detail route keeps the existing ProductDetail', async ({ page }) => {
  await mockStorefront(page)
  await page.goto('/')
  await page.locator('.grid .card').filter({ hasText: product.name }).click()

  const detail = page.locator('.product-detail')
  await expect(detail.getByRole('heading', { name: product.name, level: 1 })).toBeVisible()
  await expect(detail.locator('.sku-table')).toBeVisible()
  await expect(detail.locator('.qty-input')).toHaveCount(product.skus.length)
  await expect(detail.getByRole('button', { name: '구매하기' })).toBeDisabled()
  await expect(page.locator('main[aria-label$="상품 상세"]')).toHaveCount(0)
  expect(new URL(page.url()).searchParams.has('detailDraft')).toBe(false)
})
