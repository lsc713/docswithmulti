import { test, expect } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const FIXTURE_IMAGE = path.join(__dirname, 'fixtures', 'test-image.png')

// ADMIN 계정 주입 지점. user-service는 app.admin.bootstrap-emails(env:
// APP_ADMIN_BOOTSTRAP_EMAILS)에 있는 이메일로 signup하면 자동으로 ADMIN이 된다(AuthService).
// 이 테스트는 그 이메일로 회원가입만 하면 되므로, 실행 전 user-service를
// `APP_ADMIN_BOOTSTRAP_EMAILS=<E2E_ADMIN_EMAIL>`로 기동해야 한다. 미설정 시 test.skip.
const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'admin-e2e@example.com'
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'pw12345'

function navbar(page) {
  return page.locator('.navbar')
}
function modal(page) {
  return page.locator('.modal')
}

async function signupAs(page, { email, name = '관리자', phone = '010-0000-0000', password }) {
  await page.goto('/')
  await navbar(page).getByRole('button', { name: '로그인' }).click()
  const m = modal(page)
  await m.getByRole('button', { name: '회원가입으로' }).click()
  await expect(m.getByRole('heading', { name: '회원가입' })).toBeVisible()
  await m.getByPlaceholder('email').fill(email)
  await m.getByPlaceholder('password').fill(password)
  await m.getByPlaceholder('name').fill(name)
  await m.getByPlaceholder('phone').fill(phone)
  await m.getByRole('button', { name: '가입' }).click()
  await expect(m).toHaveCount(0) // 성공 시 모달 닫힘
}

test('비로그인 그리드 조회 → 상세', async ({ page }) => {
  await page.goto('/')

  const firstCard = page.locator('.card').first()
  await expect(firstCard).toBeVisible()
  const productName = await firstCard.locator('.name').innerText()

  await firstCard.click()

  await expect(page.getByRole('heading', { name: productName })).toBeVisible()
  await expect(page.locator('.gallery').first()).toBeVisible()
  await expect(page.getByRole('table')).toBeVisible() // SKU 표
  await expect(page.locator('.sku-table thead')).toContainText('SKU')

  // 뒤로 → 그리드 복귀
  await page.getByRole('button', { name: '뒤로' }).click()
  await expect(page.locator('.grid')).toBeVisible()
})

test('ADMIN 이미지 업로드 → 갤러리 반영', async ({ page }) => {
  test.skip(
    !process.env.E2E_ADMIN_EMAIL,
    'ADMIN bootstrap 미설정: 이 테스트는 user-service를 ' +
      `APP_ADMIN_BOOTSTRAP_EMAILS=${ADMIN_EMAIL} 로 기동한 뒤, 그 이메일로 회원가입해 ADMIN을 ` +
      '얻어 업로드를 검증한다. E2E_ADMIN_EMAIL 환경변수로 부트스트랩 이메일을 주입해야 실행된다 ' +
      '(재실행 시 이미 가입된 이메일이면 signup이 실패하므로 매 실행 전 DB를 초기화하거나 ' +
      '새 이메일을 사용할 것).'
  )

  await signupAs(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })

  await page.locator('.card').first().click()
  await expect(page.getByRole('table')).toBeVisible()
  await expect(page.getByRole('heading', { name: '이미지 관리 (ADMIN)' })).toBeVisible()

  const before = await page.locator('.gallery img').count()

  // presign → PUT(MinIO) → confirm 실경로
  await page.getByLabel('이미지 추가').setInputFiles(FIXTURE_IMAGE)

  await expect(page.locator('.gallery img')).toHaveCount(before + 1, { timeout: 15_000 })
})
