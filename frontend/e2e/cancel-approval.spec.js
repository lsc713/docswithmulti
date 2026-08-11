import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup'
import { createPaidOrderViaApi } from './helpers/order-payment'
import { openFirstInStockProductDetail } from './helpers/product-detail'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const BUYER = { email: `apprbuyer${Date.now()}@example.com`, password: 'password123', name: '승인테스트구매자', phone: '010-7777-8888' }
const ADMIN = { email: 'admin@example.com', password: 'password123', name: '관리자', phone: '010-0000-0000' }
const OUTSIDER = { email: `apprsider${Date.now()}@example.com`, password: 'password123', name: '일반유저', phone: '010-9999-0000' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: BUYER }).catch(() => {})
  await request.post(`${GW}/v1/auth/signup`, { data: ADMIN }).catch(() => {})    // 부트스트랩 이메일이면 ADMIN으로 가입됨(admin.spec.js와 동일 관행)
  await request.post(`${GW}/v1/auth/signup`, { data: OUTSIDER }).catch(() => {})
})

async function loginBuyer(page, user) {
  await page.goto(BASE)
  if (await page.locator('.navbar-right span').isVisible().catch(() => false)) return
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' }).click()
  await page.fill('input[placeholder="email"]', user.email)
  await page.fill('input[placeholder="password"]', user.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()
}

// 실 상품 선택 데이터로 API 결제 1건 생성 → paymentKey 반환 후 홈으로 복귀 (재호출 가능하도록)
async function buyOneItem(page) {
  const selection = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) })
  )
  const paymentKey = await createPaidOrderViaApi(page, selection)
  await page.goto(BASE)
  return paymentKey
}

async function csrfToken(page) {
  const cookies = await page.context().cookies()
  return cookies.find(c => c.name === 'csrf_token')?.value ?? ''
}

// P2엔 프론트 요청 UI가 없으므로(그건 P3) 구매자 쿠키로 API 직접 호출해 REQUESTED 건을 만든다.
// page.request는 브라우저 컨텍스트의 쿠키(httpOnly 포함)를 공유한다.
async function submitCancelRequest(page, paymentKey, reason) {
  const res = await page.request.post(`${GW}/v1/payments/${paymentKey}/cancel-requests`, {
    data: { reason },
    headers: { 'X-CSRF-Token': await csrfToken(page) },
  })
  expect(res.ok()).toBeTruthy()
}

async function loginAdmin(page) {
  await page.goto(`${BASE}/admin/login`)
  await page.fill('input[placeholder="email"]', ADMIN.email)
  await page.fill('input[placeholder="password"]', ADMIN.password)
  await page.click('button.primary')
  await expect(page).toHaveURL(/\/admin$/)
}

test('ADMIN 승인/반려: 구매자 취소요청 제출(API) → 콘솔에서 승인 → 반려', async ({ page, browser }) => {
  await loginBuyer(page, BUYER)

  // 승인 변형
  const paymentKey1 = await buyOneItem(page)
  await submitCancelRequest(page, paymentKey1, 'E2E 승인 사유')

  // 어드민은 별도 브라우저 컨텍스트(별도 세션)로 로그인
  const adminContext = await browser.newContext()
  const adminPage = await adminContext.newPage()
  await loginAdmin(adminPage)
  await adminPage.click('text=취소 요청')
  await expect(adminPage).toHaveURL(/\/admin\/cancel-requests$/)

  const row1 = adminPage.locator('tr', { hasText: paymentKey1 })
  await expect(row1).toBeVisible()
  await expect(row1).toContainText('E2E 승인 사유')
  await row1.locator('button:has-text("승인")').click()
  await expect(adminPage.locator('tr', { hasText: paymentKey1 })).toHaveCount(0)

  // 반려 변형: 새 요청 → '반려' → window.prompt 사유 자동 수락 → 사라짐
  const paymentKey2 = await buyOneItem(page)
  await submitCancelRequest(page, paymentKey2, 'E2E 반려 사유')

  adminPage.on('dialog', d => d.accept('반려사유'))
  await adminPage.reload()
  const row2 = adminPage.locator('tr', { hasText: paymentKey2 })
  await expect(row2).toBeVisible()
  await row2.locator('button:has-text("반려")').click()
  await expect(adminPage.locator('tr', { hasText: paymentKey2 })).toHaveCount(0)

  await adminContext.close()
})

test('MERCHANT 사이드바: route 인터셉트로 role 스텁 → 취소요청만 노출, 상품관리/회원관리 없음', async ({ page }) => {
  const fixtureRequest = {
    id: 999001,
    paymentKey: 'pay_fixture_merchant',
    requesterUserId: 'u123',
    reason: '고객 변심',
    createdAt: new Date().toISOString(),
  }
  // 실 MERCHANT 유저 시드가 없으므로 프론트 role-conditional UX만 스텁으로 검증(실제 스코프 인가는 백엔드 P1 테스트가 증명)
  await page.route('**/v1/auth/me', route =>
    route.fulfill({ json: { userId: 'm1', email: 'merchant@example.com', role: 'MERCHANT', name: '가맹점담당자', merchantId: 'm1' } })
  )
  await page.route('**/v1/cancel-requests*', route =>
    route.fulfill({ json: { items: [fixtureRequest] } })
  )

  await page.goto(`${BASE}/admin/cancel-requests`)
  await expect(page).toHaveURL(/\/admin\/cancel-requests$/)

  await expect(page.locator('.admin-sidebar a:has-text("취소 요청")')).toBeVisible()
  await expect(page.locator('.admin-sidebar a:has-text("상품관리")')).not.toBeVisible()
  await expect(page.locator('.admin-sidebar a:has-text("회원관리")')).not.toBeVisible()
  await expect(page.locator('.admin-sidebar a:has-text("대시보드")')).not.toBeVisible()

  await expect(page.locator('tr', { hasText: fixtureRequest.paymentKey })).toBeVisible()
})

test('USER 리다이렉트: 일반 구매자 세션으로 /admin/cancel-requests 접근 시 /admin/login으로', async ({ page }) => {
  await loginBuyer(page, OUTSIDER)
  await page.goto(`${BASE}/admin/cancel-requests`)
  await expect(page).toHaveURL(/\/admin\/login$/)
})
