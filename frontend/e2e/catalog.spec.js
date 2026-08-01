import { test, expect } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE_IMAGE = path.join(__dirname, 'fixtures', 'test-image.png')

// ADMIN 계정 주입 지점. user-service의 signup은 UserRole.USER만 발급하고(V1 users.role
// DEFAULT 'USER'), 승격 API가 없어 프론트/E2E만으로는 ADMIN을 만들 수 없다.
// 실행 전 DB에서 `UPDATE users SET role='ADMIN' WHERE email='<E2E_ADMIN_EMAIL>'`로 직접
// 승격한 계정을 env로 주입해야 업로드 테스트가 실행된다. 미설정 시 test.skip.
const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD

function navbar(page) {
  return page.locator('.navbar')
}
function modal(page) {
  return page.locator('.modal')
}

async function loginAs(page, { email, password }) {
  await page.goto('/')
  await navbar(page).getByRole('button', { name: '로그인' }).click()
  const m = modal(page)
  await expect(m.getByRole('heading', { name: '로그인' })).toBeVisible()
  await m.getByPlaceholder('email').fill(email)
  await m.getByPlaceholder('password').fill(password)
  await m.getByRole('button', { name: '로그인' }).click()
  await expect(m).toHaveCount(0) // 성공 시 모달 닫힘
}

test('비로그인 그리드 조회 → 상세', async ({ page }) => {
  await page.goto('/')

  const firstCard = page.locator('.card').first()
  await expect(firstCard).toBeVisible()
  const productName = await firstCard.locator('.name').innerText()

  await firstCard.click()

  await expect(page.getByRole('heading', { name: productName })).toBeVisible()
  await expect(page.locator('.gallery, .gallery-ph')).toBeVisible()
  await expect(page.getByRole('table')).toBeVisible() // SKU 표
  await expect(page.locator('.sku-table thead')).toContainText('SKU')

  // 뒤로 → 그리드 복귀
  await page.getByRole('button', { name: '뒤로' }).click()
  await expect(page.locator('.grid')).toBeVisible()
})

test('ADMIN 이미지 업로드 → 갤러리 반영', async ({ page }) => {
  test.skip(
    !ADMIN_EMAIL || !ADMIN_PASSWORD,
    'ADMIN 계정 미주입: user-service signup은 USER role만 발급하며 승격 API가 없다. ' +
      "DB에서 UPDATE users SET role='ADMIN' WHERE email='...' 로 직접 승격한 뒤 " +
      'E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD 환경변수로 자격을 주입해야 이 테스트가 실행된다.'
  )

  await loginAs(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })

  await page.locator('.card').first().click()
  await expect(page.getByRole('table')).toBeVisible()
  await expect(page.getByRole('heading', { name: '이미지 관리 (ADMIN)' })).toBeVisible()

  const before = await page.locator('.gallery img').count()

  // presign → PUT(MinIO) → confirm 실경로
  await page.getByLabel('이미지 추가').setInputFiles(FIXTURE_IMAGE)

  await expect(page.locator('.gallery img')).toHaveCount(before + 1, { timeout: 15_000 })
})
