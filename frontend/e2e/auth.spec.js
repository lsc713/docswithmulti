import { test, expect } from '@playwright/test'

// 매 실행 유니크 이메일 (uk_users_email 충돌 방지)
const uniqueEmail = () => `pw_${Date.now()}_${Math.floor(Math.random() * 1e4)}@example.com`

async function signup(page, { email, name = '홍길동', phone = '010-1234-5678', password = 'pw12345' }) {
  await page.goto('/')
  await page.getByRole('button', { name: '회원가입으로' }).click()
  await expect(page.getByRole('heading', { name: '회원가입' })).toBeVisible()
  await page.getByPlaceholder('email').fill(email)
  await page.getByPlaceholder('password').fill(password)
  await page.getByPlaceholder('name').fill(name)
  await page.getByPlaceholder('phone').fill(phone)
  await page.getByRole('button', { name: '가입' }).click()
}

test('회원가입 → /me 표시 → httpOnly 쿠키 격리 → 로그아웃', async ({ page, context }) => {
  const email = uniqueEmail()
  await signup(page, { email })

  // /me 결과가 화면에 렌더 (신원은 /me로만, 토큰 미접근)
  await expect(page.getByRole('heading', { name: /안녕하세요, 홍길동님/ })).toBeVisible()
  await expect(page.getByText(email)).toBeVisible()
  await expect(page.getByText(/USER/)).toBeVisible()

  // XSS 방어: JS는 access/refresh 토큰을 못 읽음(httpOnly), csrf만 읽힘
  const jsCookies = await page.evaluate(() => document.cookie)
  expect(jsCookies).not.toContain('access_token')
  expect(jsCookies).not.toContain('refresh_token')
  expect(jsCookies).toContain('csrf_token')

  // 브라우저 쿠키 스토어의 httpOnly 플래그 직접 확인
  const cookies = await context.cookies()
  const byName = (n) => cookies.find((c) => c.name === n)
  expect(byName('access_token')?.httpOnly).toBe(true)
  expect(byName('refresh_token')?.httpOnly).toBe(true)
  expect(byName('csrf_token')?.httpOnly).toBe(false)

  // 로그아웃(프론트가 X-CSRF-Token 첨부) → 로그인/폼 화면으로 복귀(인증 상태 해제)
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('heading', { name: /안녕하세요/ })).toHaveCount(0)
  await expect(page.getByPlaceholder('email')).toBeVisible()

  // 로그아웃 후엔 access 쿠키가 만료되어 브라우저 스토어에서 사라짐
  const after = await context.cookies()
  expect(after.find((c) => c.name === 'access_token')).toBeUndefined()
})

test('로그아웃 후 기존 계정 로그인 → /me 재표시', async ({ page }) => {
  const email = uniqueEmail()
  await signup(page, { email, name: '김철수' })
  await expect(page.getByRole('heading', { name: /안녕하세요/ })).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()

  // 로그아웃 후 폼은 마지막 모드(회원가입)를 유지 → 로그인 모드로 전환
  await page.getByRole('button', { name: '로그인으로' }).click()
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
  await page.getByPlaceholder('email').fill(email)
  await page.getByPlaceholder('password').fill('pw12345')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page.getByRole('heading', { name: /안녕하세요, 김철수님/ })).toBeVisible()
  await expect(page.getByText(email)).toBeVisible()
})

test('잘못된 비밀번호 로그인 → 에러 메시지 표시', async ({ page }) => {
  const email = uniqueEmail()
  await signup(page, { email })
  await expect(page.getByRole('heading', { name: /안녕하세요/ })).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()

  await page.getByRole('button', { name: '로그인으로' }).click()
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
  await page.getByPlaceholder('email').fill(email)
  await page.getByPlaceholder('password').fill('wrong-password')
  await page.getByRole('button', { name: '로그인' }).click()

  // 백엔드 {code,message} 가 화면에 노출되고 로그인 화면 유지
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
  await expect(page.locator('p')).toBeVisible()
  await expect(page.getByRole('heading', { name: /안녕하세요/ })).toHaveCount(0)
})
