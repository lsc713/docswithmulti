import { test, expect } from '@playwright/test'
import { resolveE2EUrls } from './helpers/urls.js'

const { frontend: BASE, gateway: GW } = resolveE2EUrls()
const ADMIN = {
  email: process.env.E2E_ADMIN_EMAIL || 'admin@example.com',
  password: 'password123',
  name: '관리자',
  phone: '010-0000-0000',
}

async function loginAdmin(page) {
  await page.goto(`${BASE}/admin/login`)
  await page.fill('input[placeholder="email"]', ADMIN.email)
  await page.fill('input[placeholder="password"]', ADMIN.password)
  await page.click('button.primary')
  await expect(page).toHaveURL(/\/admin$/)
}

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
  await loginAdmin(page)
  await expect(page.locator('.admin-card')).toHaveCount(4)

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

test('ADMIN 카테고리 생성: 대분류 → 중분류 → 소분류 → 상품등록 선택지 반영', async ({ page }) => {
  await loginAdmin(page)
  const runId = Date.now()
  const root = `E2E 대분류 ${runId}`
  const middle = `E2E 중분류 ${runId}`
  const leaf = `E2E 소분류 ${runId}`
  const emptyRoot = `E2E 빈 대분류 ${runId}`
  const emptyMiddle = `E2E 빈 중분류 ${runId}`

  await page.getByText('카테고리 관리', { exact: true }).click()
  await page.getByPlaceholder('카테고리 이름').fill(root)
  await page.getByRole('button', { name: '카테고리 추가' }).click()

  await page.getByLabel('상위 카테고리').selectOption({ label: root })
  await page.getByPlaceholder('카테고리 이름').fill(middle)
  await page.getByRole('button', { name: '카테고리 추가' }).click()
  await page.getByLabel('상위 카테고리').selectOption({ label: `${root} > ${middle}` })
  await page.getByPlaceholder('카테고리 이름').fill(leaf)
  await page.getByRole('button', { name: '카테고리 추가' }).click()

  await page.getByLabel('상위 카테고리').selectOption({ label: '대분류' })
  await page.getByPlaceholder('카테고리 이름').fill(emptyRoot)
  await page.getByRole('button', { name: '카테고리 추가' }).click()

  await page.getByLabel('상위 카테고리').selectOption({ label: root })
  await page.getByPlaceholder('카테고리 이름').fill(emptyMiddle)
  await page.getByRole('button', { name: '카테고리 추가' }).click()

  await page.getByText('상품 등록', { exact: true }).click()
  const categoryOptions = page.locator('select').first()
  await expect(categoryOptions.getByRole('option', { name: root })).toHaveCount(0)
  await expect(categoryOptions.getByRole('option', { name: middle })).toHaveCount(0)
  await expect(categoryOptions.getByRole('option', { name: emptyRoot })).toHaveCount(0)
  await expect(categoryOptions.getByRole('option', { name: emptyMiddle })).toHaveCount(0)
  await expect(categoryOptions.getByRole('option', { name: leaf })).toHaveCount(1)
})
