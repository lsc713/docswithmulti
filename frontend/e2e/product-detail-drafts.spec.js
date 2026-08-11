import { test, expect } from '@playwright/test'

const PRODUCT_ID = 101
const SCREENSHOT_DIR = 'test-results/product-detail-drafts/screenshots'
const GALLERY_ARTIFACT_DIR = 'artifacts/gallery-disclosure'

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
  variantOptions: [
    { attribute: '색상', values: ['블랙', '네이비', '크림'] },
    { attribute: '사이즈', values: ['M'] },
  ],
  skus: [
    {
      skuId: 501,
      skuCode: 'WOOL-BLACK-M',
      optionSummary: '블랙 / M',
      price: 149000,
      availableQty: 7,
      variant: { 색상: '블랙', 사이즈: 'M' },
    },
    {
      skuId: 503,
      skuCode: 'WOOL-NAVY-M',
      optionSummary: '네이비 / M',
      price: 149000,
      availableQty: 4,
      variant: { 색상: '네이비', 사이즈: 'M' },
    },
    {
      skuId: 502,
      skuCode: 'WOOL-CREAM-M',
      optionSummary: '크림 / M',
      price: 149000,
      availableQty: 0,
      variant: { 색상: '크림', 사이즈: 'M' },
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

  if (draft !== 'gallery') {
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
}

test('gallery exposes each variant group as an independent disclosure listbox and closes after selection', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  const colorTrigger = main.getByRole('button', { name: /^색상 ·/ })
  const sizeTrigger = main.getByRole('button', { name: /^사이즈 ·/ })
  await expect(colorTrigger).toHaveAccessibleName('색상 · 선택 안 됨')
  await expect(sizeTrigger).toHaveAccessibleName('사이즈 · 선택 안 됨')
  await expect(colorTrigger).toHaveAttribute('aria-expanded', 'false')
  await expect(sizeTrigger).toHaveAttribute('aria-expanded', 'false')

  await colorTrigger.click()
  await expect(main.getByRole('listbox', { name: '색상 옵션' })).toBeVisible()
  await expect(colorTrigger).toHaveAttribute('aria-expanded', 'true')
  await expect(sizeTrigger).toHaveAttribute('aria-expanded', 'false')

  await main.getByRole('option', { name: '블랙' }).click()
  await expect(colorTrigger).toHaveAccessibleName('색상 · 블랙')
  await expect(colorTrigger).toHaveAttribute('aria-expanded', 'false')
  await expect(main.getByRole('listbox', { name: '색상 옵션' })).toHaveCount(0)
})

test('gallery keeps unavailable variant values disabled and explains that they are sold out', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: /^색상 ·/ }).click()

  const soldOut = main.getByRole('option', { name: '크림 · 품절' })
  await expect(soldOut).toBeDisabled()
})

test('gallery supports keyboard navigation, Escape focus restoration, and independent open groups', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  const colorTrigger = main.getByRole('button', { name: /^색상 ·/ })
  const sizeTrigger = main.getByRole('button', { name: /^사이즈 ·/ })
  await colorTrigger.focus()
  await page.keyboard.press('Enter')
  await expect(main.getByRole('option', { name: '블랙' })).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(main.getByRole('option', { name: '네이비' })).toBeFocused()
  await page.keyboard.press('ArrowUp')
  await expect(main.getByRole('option', { name: '블랙' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(colorTrigger).toBeFocused()
  await expect(colorTrigger).toHaveAttribute('aria-expanded', 'false')

  await colorTrigger.click()
  await sizeTrigger.click()
  await expect(main.getByRole('listbox', { name: '색상 옵션' })).toBeVisible()
  await expect(main.getByRole('listbox', { name: '사이즈 옵션' })).toBeVisible()

  await main.getByRole('option', { name: 'M' }).focus()
  await page.keyboard.press('Enter')
  await expect(sizeTrigger).toHaveAccessibleName('사이즈 · M')
  await expect(sizeTrigger).toBeFocused()
})

test('gallery resolves the selected variant combination to the existing purchase flow', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: /^색상 ·/ }).click()
  await main.getByRole('option', { name: '블랙' }).click()
  await main.getByRole('button', { name: /^사이즈 ·/ }).click()
  await main.getByRole('option', { name: 'M' }).click()

  await expect(main.getByRole('status')).toContainText('블랙 / M, 1개 선택됨')
  await expect(main.getByRole('button', { name: '구매하기' })).toBeEnabled()
  await main.getByRole('button', { name: '구매하기' }).click()
  await expect(page.getByRole('heading', { name: '로그인', level: 1 })).toBeVisible()
})

test('gallery scrolls a 20-value listbox without overflowing a 390px viewport', async ({ page }) => {
  const values = Array.from({ length: 20 }, (_, index) => `아주 긴 색상 옵션 ${String(index + 1).padStart(2, '0')}`)
  const manyOptionProduct = {
    ...product,
    variantOptions: [{ attribute: '색상', values }],
    skus: values.map((value, index) => ({
      skuId: 700 + index,
      skuCode: `COLOR-${index}`,
      optionSummary: value,
      price: 149000,
      availableQty: 3,
      variant: { 색상: value },
    })),
  }
  await mockStorefront(page, {
    productHandler: route => route.fulfill({ contentType: 'application/json', json: manyOptionProduct }),
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: /^색상 ·/ }).click()
  const listbox = main.getByRole('listbox', { name: '색상 옵션' })
  const dimensions = await listbox.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }))
  expect(dimensions.scrollHeight).toBeGreaterThan(dimensions.clientHeight)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
})

test('gallery falls back to a single SKU disclosure when structured variant groups are absent', async ({ page }) => {
  const legacyProduct = { ...product, variantOptions: undefined }
  await mockStorefront(page, {
    productHandler: route => route.fulfill({ contentType: 'application/json', json: legacyProduct }),
  })
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: '옵션 · 선택 안 됨' }).click()
  await main.getByRole('option', { name: '블랙 / M' }).click()

  await expect(main.getByRole('button', { name: '옵션 · 블랙 / M' })).toHaveAttribute('aria-expanded', 'false')
  await expect(main.getByRole('button', { name: '구매하기' })).toBeEnabled()
})

test('gallery can switch between sparse variant combinations without trapping prior selections', async ({ page }) => {
  const sparseProduct = {
    ...product,
    variantOptions: [
      { attribute: '색상', values: ['레드', '블루'] },
      { attribute: '사이즈', values: ['S', 'M'] },
    ],
    skus: [
      { skuId: 801, skuCode: 'RED-S', optionSummary: '레드 / S', price: 149000, availableQty: 2,
        variant: { 색상: '레드', 사이즈: 'S' } },
      { skuId: 802, skuCode: 'BLUE-M', optionSummary: '블루 / M', price: 149000, availableQty: 2,
        variant: { 색상: '블루', 사이즈: 'M' } },
    ],
  }
  await mockStorefront(page, {
    productHandler: route => route.fulfill({ contentType: 'application/json', json: sparseProduct }),
  })
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: /^색상 ·/ }).click()
  await main.getByRole('option', { name: '레드' }).click()
  await main.getByRole('button', { name: /^사이즈 ·/ }).click()
  await main.getByRole('option', { name: 'S' }).click()
  await expect(main.getByRole('button', { name: '구매하기' })).toBeEnabled()

  await main.getByRole('button', { name: /^색상 ·/ }).click()
  await expect(main.getByRole('option', { name: '블루' })).toBeEnabled()
  await main.getByRole('option', { name: '블루' }).click()
  await expect(main.getByRole('button', { name: '사이즈 · 선택 안 됨' })).toBeVisible()
  await expect(main.getByRole('button', { name: '구매하기' })).toBeDisabled()

  await main.getByRole('button', { name: /^사이즈 ·/ }).click()
  await main.getByRole('option', { name: 'M' }).click()
  await expect(main.getByRole('status')).toContainText('블루 / M, 1개 선택됨')
})

test('gallery clamps quantity when switching to a lower-stock SKU', async ({ page }) => {
  await mockStorefront(page)
  await page.goto(draftUrl('gallery'))

  const main = draftMain(page, 'gallery')
  await main.getByRole('button', { name: /^색상 ·/ }).click()
  await main.getByRole('option', { name: '블랙' }).click()
  await main.getByRole('button', { name: /^사이즈 ·/ }).click()
  await main.getByRole('option', { name: 'M' }).click()
  await main.getByRole('spinbutton', { name: '수량' }).fill('7')

  await main.getByRole('button', { name: /^색상 ·/ }).click()
  await main.getByRole('option', { name: '네이비' }).click()
  await expect(main.getByRole('spinbutton', { name: '수량' })).toHaveValue('4')
  await expect(main.getByRole('status')).toContainText('네이비 / M, 4개 선택됨')
})

test('gallery closes an all-disabled disclosure with Escape and restores trigger focus', async ({ page }) => {
  const soldOutProduct = {
    ...product,
    skus: product.skus.map(sku => ({ ...sku, availableQty: 0 })),
  }
  await mockStorefront(page, {
    productHandler: route => route.fulfill({ contentType: 'application/json', json: soldOutProduct }),
  })
  await page.goto(draftUrl('gallery'))

  const trigger = draftMain(page, 'gallery').getByRole('button', { name: /^색상 ·/ })
  await trigger.focus()
  await page.keyboard.press('Enter')
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Escape')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(trigger).toBeFocused()
})

for (const viewport of [
  { name: '1440', width: 1440, height: 1000 },
  { name: '390', width: 390, height: 844 },
]) {
  test(`gallery captures closed, open sold-out, and multi-open states at ${viewport.name}px`, async ({ page }) => {
    await mockStorefront(page)
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.goto(draftUrl('gallery'))
    const main = draftMain(page, 'gallery')

    await page.screenshot({ path: `${GALLERY_ARTIFACT_DIR}/gallery-closed-${viewport.name}.png`, fullPage: true })
    await main.getByRole('button', { name: /^색상 ·/ }).click()
    await expect(main.getByRole('option', { name: '크림 · 품절' })).toBeDisabled()
    await page.screenshot({ path: `${GALLERY_ARTIFACT_DIR}/gallery-open-sold-out-${viewport.name}.png`, fullPage: true })
    await main.getByRole('button', { name: /^사이즈 ·/ }).click()
    await expect(main.getByRole('listbox')).toHaveCount(2)
    await page.screenshot({ path: `${GALLERY_ARTIFACT_DIR}/gallery-multi-open-${viewport.name}.png`, fullPage: true })

    if (viewport.width === 390) {
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    }
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
  await page.goto(draftUrl('editorial'))

  const option = draftMain(page, 'editorial').getByRole('radio', { name: /블랙 \/ M/ })
  await option.focus()
  await page.keyboard.press('Space')

  await expect(option).toBeChecked()
  await expect(draftMain(page, 'editorial').getByRole('spinbutton', { name: '수량' })).toBeEnabled()
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
