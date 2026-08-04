import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `cart${Date.now()}@example.com`, password: 'password123', name: '카트유저', phone: '010-4444-5555' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('장바구니: 담기 → 장바구니 → 수량수정 → 주문하기 → 결제 → 완료 + 비움', async ({ page }) => {
  await page.goto(BASE)
  await page.click('.navbar-right button')                 // 로그인 모달
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()

  // 상품 상세(재고 있는 시드 상품을 이름으로 특정 — 다른 E2E가 만든 재고 0 상품과의 충돌 방지) → 수량 1 → 장바구니 담기
  await page.click('.grid .card:has-text("베이직 티셔츠")')
  await page.waitForSelector('.cart-btn')
  // dev(StrictMode) 이중 effect로 인한 2차 fetch가 늦게 도착하며 qty state를 리셋하는 것을 피하기 위해
  // 상세 데이터 fetch가 완전히 안정될 때까지 대기 (테스트 타이밍 조정, 앱 코드 무변경)
  await page.waitForLoadState('networkidle')
  await page.locator('.qty-input').first().fill('1')
  await page.click('.cart-btn')

  // 장바구니 뷰(담기 후 자동 이동) → 개수/합계 확인
  await expect(page.locator('.cart h1')).toHaveText('장바구니')
  await expect(page.locator('.cart-table tbody tr')).toHaveCount(1)

  // 수량 2로 수정
  await page.locator('.cart-table .qty-input').first().fill('2')
  await page.waitForTimeout(300)

  // 주문하기 → 결제 → 완료
  await page.click('.pay-btn')                              // 주문하기(Cart) → checkout
  await expect(page.locator('.checkout h1')).toHaveText('주문하기')
  await page.click('.pay-btn')                              // 결제하기(Checkout)
  await expect(page.locator('.order-success h1')).toContainText('결제 완료', { timeout: 15_000 })

  // 장바구니 비워졌는지 (네비바 개수 0 / 재진입 빈 목록)
  await page.click('text=쇼핑 계속하기')
  await expect(page.locator('.navbar-right')).toContainText('장바구니(0)')
})
