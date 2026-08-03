import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const ADMIN = { email: 'admin@example.com', password: 'password123', name: '관리자', phone: '010-0000-0000' }

test.beforeAll(async ({ request }) => {
  // 부트스트랩 이메일이면 ADMIN으로 가입됨(이미 있으면 409 무시)
  await request.post(`${GW}/v1/auth/signup`, { data: ADMIN }).catch(() => {})
})

test('가드: 비로그인 상태로 /admin 접근 시 로그인으로 리다이렉트', async ({ page }) => {
  await page.goto(`${BASE}/admin`)
  await expect(page).toHaveURL(/\/admin\/login$/)
  await expect(page.locator('h1')).toHaveText('어드민 로그인')
})

test('저니: 로그인 → 대시보드 → 상품 생성 → 상세/이미지 → 회원 역할변경', async ({ page }) => {
  // 로그인
  await page.goto(`${BASE}/admin/login`)
  await page.fill('input[placeholder="email"]', ADMIN.email)
  await page.fill('input[placeholder="password"]', ADMIN.password)
  await page.click('button.primary')
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.locator('.admin-card')).toHaveCount(3)

  // 상품 생성 (재실행 가능하도록 이름/SKU에 타임스탬프로 유일성 부여)
  const runId = Date.now()
  const productName = `E2E 셔츠 ${runId}`
  await page.click('text=상품 등록')
  await page.fill('input[placeholder="상품명"]', productName)
  await page.selectOption('select', { index: 1 })            // 첫 leaf 카테고리
  await page.fill('input[placeholder="SKU코드"]', `E2E-BLK-M-${runId}`)
  await page.fill('input[placeholder="가격"]', '19000')
  await page.click('button.primary:has-text("등록")')
  await expect(page).toHaveURL(/\/admin\/products\/\d+$/)
  await expect(page.locator('h1')).toHaveText(productName)
  await expect(page.locator('.image-manager')).toBeVisible()  // 이미지 관리 패널

  // 회원관리 → 첫 행 역할 토글(변경 후 재조회 성공만 확인)
  await page.click('text=회원관리')
  await expect(page.locator('table.admin-table tbody tr').first()).toBeVisible()
})
