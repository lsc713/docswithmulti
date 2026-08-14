import { test, expect } from '@playwright/test'
import { runProductName } from './helpers/catalog-setup.js'
import { loginBuyer } from './helpers/login-buyer.js'
import { openFirstInStockProductDetail } from './helpers/product-detail.js'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const BUYER = { email: `mock-pg-${Date.now()}@example.com`, password: 'password123', name: 'Mock PG 구매자', phone: '010-5555-6666' }
const ADMIN = { email: process.env.E2E_ADMIN_EMAIL, password: 'password123' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: BUYER }).catch(() => {})
})

test('Mock PG: 구매 → 결제완료 → 취소요청 → ADMIN 승인 → 취소완료', async ({ page, browser }) => {
  test.skip(process.env.VITE_PAYMENT_PROVIDER !== 'mock', 'Mock PG 프론트에서만 실행')
  const tossRequests = []
  page.on('request', request => {
    if (new URL(request.url()).hostname.endsWith('tosspayments.com')) tossRequests.push(request.url())
  })

  await loginBuyer(page, BUYER, BASE, GW)
  const { detail } = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) }),
  )
  await detail.getByRole('button', { name: '구매하기' }).click()
  await page.getByRole('button', { name: '결제 수단 선택' }).first().click()
  await page.getByRole('button', { name: '결제하기' }).first().click()
  await expect(page).toHaveURL(/\/order-success$/)
  expect(tossRequests).toEqual([])

  await page.getByRole('button', { name: '쇼핑 계속하기' }).click()
  await page.getByRole('button', { name: '주문내역' }).click()
  const paidRow = page.locator('.history-item').first()
  const paymentKey = (await paidRow.locator('.history-key').textContent()).trim()
  page.on('dialog', dialog => dialog.accept('Mock PG 취소 E2E'))
  await paidRow.getByRole('button', { name: '취소 요청' }).click()
  await expect(paidRow.locator('.crs-badge')).toHaveText('취소 요청됨')

  const adminContext = await browser.newContext()
  const adminPage = await adminContext.newPage()
  await adminPage.goto(`${BASE}/admin/login`)
  await adminPage.getByPlaceholder('email').fill(ADMIN.email)
  await adminPage.getByPlaceholder('password').fill(ADMIN.password)
  await adminPage.getByRole('button', { name: '로그인' }).click()
  await adminPage.locator('.admin-sidebar').getByText('취소 요청', { exact: true }).click()
  const requestRow = adminPage.locator('tr', { hasText: paymentKey })
  await requestRow.getByRole('button', { name: '승인' }).click()
  await expect(requestRow).toHaveCount(0)

  await page.reload()
  await expect(page.locator('.history-item', { hasText: paymentKey }).locator('.badge'))
    .toHaveText('취소됨')
  await adminContext.close()
})
