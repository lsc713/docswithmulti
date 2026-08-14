import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup'
import { openFirstInStockProductDetail } from './helpers/product-detail'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const USER = { email: `cart${Date.now()}@example.com`, password: 'password123', name: '카트유저', phone: '010-4444-5555' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('장바구니: 담기 → 장바구니 → 수량수정 → 주문하기 → 결제 → 완료 + 비움', async ({ page }) => {
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
  await page.goto(BASE)
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' }).click()
  await page.getByRole('textbox', { name: '이메일' }).fill(USER.email)
  await page.getByRole('textbox', { name: '비밀번호', exact: true }).fill(USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 상품 상세(재고 있는 시드 상품을 이름으로 특정 — 다른 E2E가 만든 재고 0 상품과의 충돌 방지) → 수량 1 → 장바구니 담기
  const selection = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) })
  )
  const { detail } = selection
  await detail.getByRole('button', { name: '장바구니 담기' }).click()

  // 장바구니 뷰(담기 후 자동 이동) → 개수/합계 확인
  await expect(page.locator('.cart h1')).toHaveText('장바구니')
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(1)

  // 수량 2로 수정
  await page.locator('.cart-table .qty-input').first().fill('2')
  await page.waitForTimeout(300)

  // 주문하기 → 서버 가격 검증 → Toss 결제 → 완료
  await page.getByRole('button', { name: '주문하기' }).click()
  await expect(page.getByRole('heading', { name: '주문할 상품을 확인해 주세요' })).toBeVisible()
  await page.getByRole('button', { name: '결제 수단 선택' }).first().click()
  await expect(page.getByRole('heading', { name: '결제 수단을 확인해 주세요' })).toBeVisible()
  await page.getByRole('button', { name: '결제하기' }).first().click()
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })

  // 장바구니 비워졌는지 — reload로 App 재마운트 → api.me() → loadCart()가 실제 GET /v1/cart 재조회하도록 강제
  // (클릭만으로는 onPaid의 낙관적 setCart([])만 확인하게 되어 서버 측 실제 clear 여부를 검증 못함)
  await page.getByRole('button', { name: '쇼핑 계속하기' }).click()
  await page.reload()
  await expect(page.locator('.navbar-right span')).toBeVisible()
  await expect(page.locator('.navbar-right')).toContainText('장바구니(0)')
})
