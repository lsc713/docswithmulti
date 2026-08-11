import { test, expect } from '@playwright/test'
import { openFirstInStockProductDetail } from './helpers/product-detail'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `hist${Date.now()}@example.com`, password: 'password123', name: '내역유저', phone: '010-6666-7777' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('주문내역: 구매 → 내역 → 취소 요청 → 취소 요청됨', async ({ page }) => {
  page.on('dialog', d => d.accept('E2E 취소 요청'))       // window.prompt(사유) 자동 수락

  await page.goto(BASE)
  await page.click('.navbar-right button')               // 로그인
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 바로구매로 결제 1건 생성
  const { detail } = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card:has-text("베이직 티셔츠")')
  )
  await detail.getByRole('button', { name: '구매하기' }).click()
  await page.click('.checkout .pay-btn')
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })

  // 주문내역 → 취소 요청 → 취소 요청됨 (P3: 즉시취소 대신 승인 요청 제출)
  await page.click('text=쇼핑 계속하기')
  await page.click('text=주문내역')
  await expect(page.locator('.history-item').first()).toBeVisible()
  await expect(page.locator('.history-item .badge').first()).toHaveText('결제완료')
  await page.locator('.history-item button:has-text("취소 요청")').first().click()
  await expect(page.locator('.history-item .crs-badge').first()).toHaveText('취소 요청됨', { timeout: 15_000 })
})
