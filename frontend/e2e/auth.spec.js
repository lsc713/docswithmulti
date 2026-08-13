import { test, expect } from '@playwright/test'
import { resolveE2EUrls } from './helpers/urls.js'

// 매 실행 유니크 이메일 (uk_users_email 충돌 방지)
const uniqueEmail = () => `pw_${Date.now()}_${Math.floor(Math.random() * 1e4)}@example.com`
const { gateway: GATEWAY } = resolveE2EUrls()

// Task 11 UI 재구성: 로그인/회원가입은 NavBar의 "로그인" 버튼으로 여는 모달(AuthModal).
// 로그인 상태 표시는 전면 헤딩이 아니라 NavBar의 "{name}님" + "로그아웃" 버튼.
function navbar(page) {
  return page.locator('.navbar')
}
function modal(page) {
  return page.locator('.modal')
}

async function openAuthModal(page) {
  await navbar(page).getByRole('button', { name: '로그인' }).click()
}

async function signup(page, { email, name = '홍길동', phone = '010-1234-5678', password = 'pw12345' }) {
  await page.goto('/')
  await openAuthModal(page)
  const m = modal(page)
  await m.getByRole('button', { name: '회원가입으로' }).click()
  await expect(m.getByRole('heading', { name: '회원가입' })).toBeVisible()
  await m.getByPlaceholder('email').fill(email)
  await m.getByPlaceholder('password').fill(password)
  await m.getByPlaceholder('name').fill(name)
  await m.getByPlaceholder('phone').fill(phone)
  await m.getByRole('button', { name: '가입' }).click()
}

test('회원가입 → NavBar 표시 → httpOnly 쿠키 격리 → 로그아웃', async ({ page, context }) => {
  const email = uniqueEmail()
  await signup(page, { email })

  // 로그인 상태는 NavBar에 이름으로 렌더 (전면 헤딩 없음)
  await expect(navbar(page).getByText('홍길동님')).toBeVisible()
  await expect(modal(page)).toHaveCount(0) // 인증 성공 시 모달 닫힘

  // 신원은 /me로만 확인 (토큰 미접근). page.request는 브라우저 쿠키 스토어를 공유하므로
  // httpOnly 쿠키도 실려 나간다 — JS는 못 읽지만 세션은 유효함을 증명.
  const me = await page.request.get(`${GATEWAY}/v1/auth/me`)
  expect(me.ok()).toBeTruthy()
  const meBody = await me.json()
  expect(meBody.email).toBe(email)
  expect(meBody.role).toBe('USER')

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

  // 로그아웃(프론트가 X-CSRF-Token 첨부) → NavBar가 "로그인" 버튼으로 복귀
  await navbar(page).getByRole('button', { name: '로그아웃' }).click()
  await expect(navbar(page).getByText('홍길동님')).toHaveCount(0)
  await expect(navbar(page).getByRole('button', { name: '로그인' })).toBeVisible()

  // 로그아웃 후엔 access 쿠키가 만료되어 브라우저 스토어에서 사라짐
  const after = await context.cookies()
  expect(after.find((c) => c.name === 'access_token')).toBeUndefined()
})

test('로그아웃 후 기존 계정 로그인 → NavBar 재표시', async ({ page }) => {
  const email = uniqueEmail()
  await signup(page, { email, name: '김철수' })
  await expect(navbar(page).getByText('김철수님')).toBeVisible()
  await navbar(page).getByRole('button', { name: '로그아웃' }).click()

  // 모달 컴포넌트는 언마운트되지 않으므로 마지막 모드(회원가입)를 유지 → 로그인 모드로 전환
  await openAuthModal(page)
  const m = modal(page)
  await m.getByRole('button', { name: '로그인으로' }).click()
  await expect(m.getByRole('heading', { name: '로그인' })).toBeVisible()
  await m.getByPlaceholder('email').fill(email)
  await m.getByPlaceholder('password').fill('pw12345')
  await m.getByRole('button', { name: '로그인' }).click()

  await expect(navbar(page).getByText('김철수님')).toBeVisible()
})

test('잘못된 비밀번호 로그인 → 에러 메시지 표시', async ({ page }) => {
  const email = uniqueEmail()
  await signup(page, { email })
  await expect(navbar(page).getByText('홍길동님')).toBeVisible()
  await navbar(page).getByRole('button', { name: '로그아웃' }).click()

  await openAuthModal(page)
  const m = modal(page)
  await m.getByRole('button', { name: '로그인으로' }).click()
  await expect(m.getByRole('heading', { name: '로그인' })).toBeVisible()
  await m.getByPlaceholder('email').fill(email)
  await m.getByPlaceholder('password').fill('wrong-password')
  await m.getByRole('button', { name: '로그인' }).click()

  // 백엔드 {code,message} 가 모달 안에 노출되고 로그인 화면 유지
  await expect(m.getByRole('heading', { name: '로그인' })).toBeVisible()
  await expect(m.locator('p')).toBeVisible()
  await expect(navbar(page).getByText('홍길동님')).toHaveCount(0)
})
