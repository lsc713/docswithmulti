import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `crbuyer${Date.now()}@example.com`, password: 'password123', name: '취소요청구매자', phone: '010-8888-9999' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

async function login(page, user) {
  await page.goto(BASE)
  await page.click('.navbar-right button')               // 로그인
  await page.fill('input[placeholder="email"]', user.email)
  await page.fill('input[placeholder="password"]', user.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()
}

// 바로구매로 결제 1건 생성 → paymentKey 반환 후 홈으로 복귀 (재호출 가능하도록)
async function buyOneItem(page) {
  await page.click('.grid .card:has-text("베이직 티셔츠")')
  await page.waitForSelector('.buy-btn')
  // dev(StrictMode) 이중 effect로 인한 2차 fetch가 qty state를 리셋하는 것을 피하기 위해
  // 상세 데이터 fetch가 안정될 때까지 대기 (checkout.spec.js/history.spec.js와 동일한 타이밍 조정, 앱 코드 무변경)
  await page.waitForLoadState('networkidle')
  await page.locator('.qty-input').first().fill('1')
  await page.click('.buy-btn')
  await page.click('.checkout .pay-btn')
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })
  const paymentKey = (await page.locator('.success-key code').textContent()).trim()
  await page.click('text=쇼핑 계속하기')
  return paymentKey
}

async function csrfToken(page) {
  const cookies = await page.context().cookies()
  return cookies.find(c => c.name === 'csrf_token')?.value ?? ''
}

test('취소 요청 제출 + 상태: 구매 → 주문내역 → 취소 요청 클릭 → 취소 요청됨 뱃지 → 재조회에도 유지', async ({ page }) => {
  page.on('dialog', d => d.accept('단순 변심'))            // window.prompt 자동 수락

  await login(page, USER)
  const paymentKey = await buyOneItem(page)

  await page.click('text=주문내역')
  const row = page.locator('.history-item', { hasText: paymentKey })
  await expect(row).toBeVisible()
  await expect(row.locator('button:has-text("취소 요청")')).toBeVisible()
  await row.locator('button:has-text("취소 요청")').click()

  await expect(row.locator('.crs-badge')).toHaveText('취소 요청됨', { timeout: 15_000 })
  await expect(row.locator('button:has-text("취소 요청")')).toHaveCount(0)

  // 재조회(주문내역 재진입)에도 유지 — cancelRequestStatus는 서버 반영(Task 2)
  await page.click('text=뒤로')
  await page.click('text=주문내역')
  const row2 = page.locator('.history-item', { hasText: paymentKey })
  await expect(row2.locator('.crs-badge')).toHaveText('취소 요청됨')
  await expect(row2.locator('button:has-text("취소 요청")')).toHaveCount(0)
})

test('USER 직접취소 403 회귀: 로그인 상태로 POST /v1/payments/{key}/cancel 직접 호출 시 403', async ({ page }) => {
  await login(page, USER)
  const paymentKey = await buyOneItem(page)

  const res = await page.request.post(`${GW}/v1/payments/${paymentKey}/cancel`, {
    data: {},
    headers: { 'X-CSRF-Token': await csrfToken(page) },
  })
  expect(res.status()).toBe(403)
})
