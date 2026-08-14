import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup'
import { createPaidOrderViaApi } from './helpers/order-payment'
import { openFirstInStockProductDetail } from './helpers/product-detail'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const USER = { email: `hist${Date.now()}@example.com`, password: 'password123', name: '내역유저', phone: '010-6666-7777' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('주문내역: 구매 → 내역 → 취소 요청 → 취소 요청됨', async ({ page }) => {
  page.on('dialog', d => d.accept('E2E 취소 요청'))       // window.prompt(사유) 자동 수락

  await page.goto(BASE)
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' }).click()
  await page.getByRole('textbox', { name: '이메일' }).fill(USER.email)
  await page.getByRole('textbox', { name: '비밀번호', exact: true }).fill(USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 실 상품 선택 데이터로 API 결제 1건 생성
  const selection = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) })
  )
  const paymentKey = await createPaidOrderViaApi(page, selection)

  // 주문내역 → 취소 요청 → 취소 요청됨 (P3: 즉시취소 대신 승인 요청 제출)
  await page.click('text=주문내역')
  const row = page.locator('.history-item', { hasText: paymentKey })
  await expect(row).toBeVisible()
  await expect(row.locator('.badge')).toHaveText('결제완료')
  await row.locator('button:has-text("취소 요청")').click()
  await expect(row.locator('.crs-badge')).toHaveText('취소 요청됨', { timeout: 15_000 })
})
