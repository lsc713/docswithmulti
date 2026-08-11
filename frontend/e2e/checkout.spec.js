import { test, expect } from '@playwright/test'
import { openFirstInStockProductDetail } from './helpers/product-detail'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `buyer${Date.now()}@example.com`, password: 'password123', name: '구매자', phone: '010-2222-3333' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('바로구매: 로그인 → 상품상세 수량선택 → 주문하기 → 결제 → 완료', async ({ page }) => {
  // 로그인 (스토어프론트 모달)
  await page.goto(BASE)
  await page.click('.navbar-right button')            // 로그인
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()  // 로그인됨

  // 상품 상세 진입 + 수량 1 선택 (재고 있는 시드 상품을 이름으로 특정 — 다른 E2E가 만든 재고 0 상품과의 충돌 방지)
  const { detail } = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card:has-text("베이직 티셔츠")')
  )
  await detail.getByRole('button', { name: '구매하기' }).click()

  // 체크아웃 → 총액 표시 → 결제
  await expect(page.locator('.checkout h1')).toHaveText('주문하기')
  await expect(page.locator('.checkout-total')).toContainText('₩')
  await page.click('.pay-btn')

  // 완료 화면 (paymentKey 노출) — 결제 생성이 product-service 재고 동기 예약을 거치므로 여유 타임아웃
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })
  await expect(page.locator('.success-key code')).toContainText('pay_')
})
