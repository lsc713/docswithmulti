import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup'
import { createPaidOrderViaApi } from './helpers/order-payment'
import { openFirstInStockProductDetail } from './helpers/product-detail'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const USER = { email: `crbuyer${Date.now()}@example.com`, password: 'password123', name: '취소요청구매자', phone: '010-8888-9999' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

async function login(page, user) {
  await page.goto(BASE)
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

  // 유효한 취소 바디를 보내 @Valid 400을 배제 → authz 가 실제로 실행되어 403 이 인가 거부임을 보장.
  // (authz 는 paymentKey/role 만 사용하고 바디는 그 뒤에 쓰이므로 item id 실제 매칭 불필요.)
  const res = await page.request.post(`${GW}/v1/payments/${paymentKey}/cancel`, {
    data: { cancelReason: 'USER 직접취소 시도', cancelItems: [{ paymentItemId: 1 }] },
    headers: { 'X-CSRF-Token': await csrfToken(page) },
  })
  expect(res.status()).toBe(403)
  // CSRF 실패(CSRF_TOKEN_INVALID)와 구분 — 403 이 도메인 인가 거부(FORBIDDEN_PAYMENT)임을 확정.
  expect((await res.json()).code).toBe('FORBIDDEN_PAYMENT')
})
