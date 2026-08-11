import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { chromium } from '@playwright/test'

const baseURL = 'http://127.0.0.1:5173'
const gateway = 'http://localhost:8000'
const artifactDir = 'artifacts/t_e927b56e-checkout-payment'
const orderItems = [
  { skuId: 11, productId: 1, itemName: '미니멀 울 블레이저', optionSummary: '블랙 / M', unitPrice: 149000, quantity: 1, availableQty: 4 },
  { skuId: 22, productId: 2, itemName: '소프트 니트 카디건', optionSummary: '오트밀 / L', unitPrice: 79000, quantity: 2, availableQty: 5 },
  { skuId: 33, productId: 3, itemName: '오버사이즈 코튼 셔츠', optionSummary: '화이트 / Free', unitPrice: 63000, quantity: 1, availableQty: 2 },
]

const browser = await chromium.launch({
  headless: false,
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  args: ['--disable-crashpad', '--disable-crash-reporter', '--crash-dumps-dir=/tmp/t_e927b56e-crash'],
})

try {
  await mkdir(artifactDir, { recursive: true })
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ viewport: { width, height: width === 1440 ? 1100 : 844 } })
    const page = await context.newPage()
    await page.addInitScript(({ state }) => sessionStorage.setItem('fashion-shop:order-flow', JSON.stringify(state)), {
      state: { orderItems, source: 'cart' },
    })
    await page.route(`${gateway}/v1/auth/me`, route => route.fulfill({ json: { id: 7, name: '구매자', email: 'buyer@example.com', role: 'USER' } }))
    await page.route(`${gateway}/v1/cart`, route => route.fulfill({ json: { items: [] } }))
    let orderCalls = 0
    let paymentCalls = 0
    await page.route(`${gateway}/v1/orders`, route => {
      orderCalls += 1
      return route.fulfill({ status: 201, json: { orderId: 91, status: 'CREATED', items: orderItems.map((item, index) => ({ orderItemId: index + 101, itemName: item.itemName })) } })
    })
    await page.route(`${gateway}/v1/payments`, async route => {
      paymentCalls += 1
      await new Promise(resolve => setTimeout(resolve, 120))
      if (width === 1440 && paymentCalls === 1) return route.fulfill({ status: 503, json: { message: '일시적인 결제 오류' } })
      return route.fulfill({ json: { paymentKey: 'pay_visual', totalAmount: 370000, status: 'COMPLETED', items: [] } })
    })
    await page.goto(`${baseURL}/checkout?case=initial-${width}`)
    await page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' }).waitFor()
    assert.equal(await page.locator('.order-item-card').count(), 3)
    assert.equal(await page.getByTestId('grand-total').first().textContent(), '₩370,000')
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    if (width === 1440) assert.ok((await page.locator('.order-item-card').first().boundingBox()).width >= 780)
    await page.screenshot({ path: `${artifactDir}/checkout-${width}.png`, fullPage: true })

    await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
    await page.getByRole('heading', { name: '결제 수단을 선택해 주세요' }).waitFor()
    assert.equal(new URL(page.url()).pathname, '/payment')
    await page.reload()
    await page.getByRole('heading', { name: '결제 수단을 선택해 주세요' }).waitFor()
    await page.getByRole('button', { name: '← 주문서로 돌아가기 · 상태 유지' }).click()
    await page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' }).waitFor()
    await page.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().click()
    await page.getByRole('heading', { name: '결제 수단을 선택해 주세요' }).waitFor()
    assert.equal(await page.locator('.navbar-brand').textContent(), 'fashion-shop')
    assert.ok((await page.locator('.navbar-brand').boundingBox()).width >= 90)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    if (width === 390) {
      const overlaps = await page.locator('.order-item-card.compact').evaluateAll(cards => cards.some(card => {
        const quantityNode = card.querySelector('.order-item-quantity')
        const range = document.createRange()
        if (quantityNode) range.selectNodeContents(quantityNode)
        const quantity = quantityNode ? range.getBoundingClientRect() : null
        const total = card.querySelector('.order-item-total')?.getBoundingClientRect()
        return quantity && total && !(quantity.right <= total.left || total.right <= quantity.left || quantity.bottom <= total.top || total.bottom <= quantity.top)
      }))
      assert.equal(overlaps, false)
    }
    await page.screenshot({ path: `${artifactDir}/payment-${width}.png`, fullPage: true })
    if (width === 1440) {
      const submit = page.locator('.payment-submit')
      await submit.click()
      assert.equal(await submit.isDisabled(), true)
      await submit.click({ force: true })
      await page.getByRole('alert').waitFor()
      assert.equal(orderCalls, 1)
      assert.equal(paymentCalls, 1)
      await submit.click()
      await page.getByRole('heading', { name: /결제 완료/ }).waitFor()
      assert.equal(orderCalls, 1)
      assert.equal(paymentCalls, 2)
    }

    await context.close()

    const verifyCheckoutState = async (state, caseName, verify) => {
      const stateContext = await browser.newContext({ viewport: { width, height: width === 1440 ? 1100 : 844 } })
      if (state) await stateContext.addInitScript(({ value }) => sessionStorage.setItem('fashion-shop:order-flow', JSON.stringify(value)), { value: state })
      const statePage = await stateContext.newPage()
      await statePage.route(`${gateway}/v1/auth/me`, route => route.fulfill({ json: { id: 7, name: '구매자', email: 'buyer@example.com', role: 'USER' } }))
      await statePage.route(`${gateway}/v1/cart`, route => route.fulfill({ json: { items: [] } }))
      await statePage.goto(`${baseURL}/checkout?case=${caseName}-${width}`)
      await verify(statePage)
      await stateContext.close()
    }

    const many = Array.from({ length: 12 }, (_, index) => ({
      ...orderItems[index % orderItems.length],
      skuId: 500 + index,
      itemName: `계절 전환을 위한 매우 긴 이름의 프리미엄 데일리 컬렉션 상품 ${index + 1}`,
      optionSummary: `오트밀 / Extra Long Variant Label ${index + 1}`,
    }))
    await verifyCheckoutState({ orderItems: many, source: 'cart' }, 'many', async statePage => {
      await statePage.getByRole('heading', { name: '주문할 상품을 확인해 주세요' }).waitFor()
      assert.equal(await statePage.locator('.order-item-card').count(), 12)
      assert.equal(await statePage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
      if (width === 1440) assert.equal(await statePage.locator('.order-item-list').evaluate(element => element.scrollHeight > element.clientHeight), true)
    })
    await verifyCheckoutState({ orderItems: [{ ...orderItems[0], quantity: 5, availableQty: 1, price: 159000 }], source: 'product' }, 'blocked', async statePage => {
      await statePage.getByRole('heading', { name: '주문할 상품을 확인해 주세요' }).waitFor()
      assert.equal(await statePage.getByRole('button', { name: '변경 사항 확인 후 결제하기' }).first().isDisabled(), true)
      assert.match(await statePage.locator('.order-item-card').textContent(), /재고 1개.*가격 변경/s)
    })
    await verifyCheckoutState(null, 'empty', statePage => statePage.getByRole('heading', { name: '주문할 상품이 없어요' }).waitFor())
  }
  console.log(`visual verification passed: ${artifactDir}`)
} finally {
  await browser.close()
}
