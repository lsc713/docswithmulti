import { test, expect } from '@playwright/test'

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
  await page.click('.grid .card:has-text("베이직 티셔츠")')
  await page.waitForSelector('.buy-btn')
  // dev(StrictMode) 이중 effect로 인한 2차 fetch가 qty state를 리셋하는 것을 피하기 위해
  // 상세 데이터 fetch가 안정될 때까지 대기 (checkout.spec.js와 동일한 타이밍 조정, 앱 코드 무변경)
  await page.waitForLoadState('networkidle')
  await page.locator('.qty-input').first().fill('1')
  await page.click('.buy-btn')
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
