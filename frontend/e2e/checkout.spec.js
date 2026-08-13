import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup'
import { openFirstInStockProductDetail } from './helpers/product-detail'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const USER = { email: `buyer${Date.now()}@example.com`, password: 'password123', name: '구매자', phone: '010-2222-3333' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('바로구매: 로그인 → 상품상세 수량선택 → 주문하기 → 결제 → 완료', async ({ page }) => {
  await page.addInitScript(() => {
    window.TossPayments = () => ({ payment: () => ({
      requestPayment: ({ successUrl, orderId, amount }) => {
        window.location.href = `${successUrl}?paymentKey=toss_test_key&orderId=${orderId}&amount=${amount.value}`
      },
    }) })
  })
  await page.route('**/v1/payment-attempts/*/confirm', async route => {
    const body = route.request().postDataJSON()
    await route.fulfill({ json: {
      paymentRequestId: body.orderId, paymentKey: body.paymentKey,
      amount: body.amount, status: 'COMPLETED',
    } })
  })
  // 로그인 (스토어프론트 모달)
  await page.goto(BASE)
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' }).click()
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()  // 로그인됨

  // 상품 상세 진입 + 수량 1 선택 (재고 있는 시드 상품을 이름으로 특정 — 다른 E2E가 만든 재고 0 상품과의 충돌 방지)
  const { detail } = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) })
  )
  await detail.getByRole('button', { name: '구매하기' }).click()

  // 체크아웃 → 결제 수단 → 결제
  await expect(page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' })).toBeVisible()
  await expect(page.getByTestId('grand-total')).toContainText('₩')
  await page.getByRole('button', { name: '결제 수단 선택' }).first().click()
  await page.getByRole('button', { name: '결제하기' }).first().click()

  // 완료 화면 (paymentKey 노출) — 결제 생성이 product-service 재고 동기 예약을 거치므로 여유 타임아웃
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })
  await expect(page.locator('.success-key code')).toContainText('toss_test_key')
})

test('결제창 취소: 실패 처리 후 같은 주문으로 다시 결제할 수 있다', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('paymentAttempt', JSON.stringify({
    paymentRequestId: 'request-1', source: 'product',
    orderItems: [{ productId: 1, skuId: 2, itemName: '상품', optionSummary: '', unitPrice: 1000, quantity: 1 }],
    paymentItems: [{ orderItemId: 3, productId: 1, skuId: 2, itemName: '상품', quantity: 1 }],
  })))
  await page.route('**/v1/payment-attempts/request-1/fail', route => route.fulfill({ json: {
    paymentRequestId: 'request-1', paymentKey: null, amount: 1000, status: 'FAILED',
  } }))

  await page.goto(`${BASE}/payment/fail?code=PAY_PROCESS_CANCELED&orderId=request-1`)

  await expect(page.locator('.payment-result h1')).toContainText('완료하지 못했습니다')
  await expect(page.locator('.payment-result')).toContainText('결제가 취소되었습니다')
  await page.click('text=다시 결제')
  await expect(page.getByRole('heading', { name: '결제 수단을 확인해 주세요' })).toBeVisible()
})
