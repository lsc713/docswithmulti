import { test, expect } from '@playwright/test'
import { openFirstInStockProductDetail } from './helpers/product-detail'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `buyer${Date.now()}@example.com`, password: 'password123', name: '구매자', phone: '010-2222-3333' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('바로구매: 로그인 → 상품상세 수량선택 → 주문 미리보기 → 결제 차단', async ({ page }) => {
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

  // 체크아웃 → 총액 미리보기 → 서버 계약 준비 전 결제 차단
  await expect(page.getByTestId('grand-total')).toContainText('₩')
  await expect(page.getByRole('button', { name: '결제 연동 준비 중' }).first()).toBeDisabled()
  await expect(page.getByRole('alert')).toContainText('서버 재검증 미지원으로 결제 불가')
})
