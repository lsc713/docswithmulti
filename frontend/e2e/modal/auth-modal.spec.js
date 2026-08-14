import { expect, test } from '@playwright/test'

const user = { userId: 7, email: 'user@fashion.shop', name: '구매자', role: 'USER' }

async function stubStorefront(page) {
  await page.route('**/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/v1/auth/me') return route.fulfill({ status: 401, json: { code: 'TOKEN_MISSING' } })
    if (path === '/v1/categories') return route.fulfill({ json: [] })
    if (path === '/v1/cart') return route.fulfill({ json: { items: [] } })
    if (path.startsWith('/v1/products')) return route.fulfill({ json: { content: [] } })
    return route.fulfill({ json: {} })
  })
}

test.beforeEach(async ({ page }) => {
  await stubStorefront(page)
  const initialMe = page.waitForResponse(response => new URL(response.url()).pathname === '/v1/auth/me')
  await page.goto('/')
  await initialMe
})

test('opens a desktop dialog, focuses email, and restores trigger focus when closed', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1024 })
  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()

  const dialog = page.getByRole('dialog', { name: '로그인' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toHaveAttribute('aria-modal', 'true')
  await expect(dialog).toHaveAccessibleDescription('이메일과 비밀번호를 입력하세요.')
  await expect(dialog.getByRole('textbox', { name: '이메일' })).toBeFocused()
  await expect(dialog).toHaveCSS('width', '480px')
  await expect(dialog.getByRole('textbox', { name: '이메일' })).toHaveCSS('outline-color', 'rgb(52, 104, 192)')
  await expect(dialog.getByText('이메일 · 비밀번호 로그인')).toBeVisible()
  const box = await dialog.boundingBox()
  expect(box.y).toBeGreaterThanOrEqual(170)
  expect(box.y).toBeLessThanOrEqual(178)
  expect(box.height).toBeGreaterThanOrEqual(425)
  expect(box.height).toBeLessThanOrEqual(440)

  await dialog.getByRole('button', { name: '로그인 닫기' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
})

test('uses an edge-to-edge bottom sheet at the 390px mobile breakpoint', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '로그인' }).click()

  const box = await page.getByRole('dialog', { name: '로그인' }).boundingBox()
  expect(box.x).toBe(0)
  expect(box.width).toBe(390)
  expect(box.y + box.height).toBe(844)
  await expect(page.getByRole('dialog')).toHaveCSS('overflow-y', 'auto')
  await expect(page.getByRole('dialog').getByText('이메일 · 비밀번호 로그인')).toBeHidden()
})

test('locks page scroll, traps keyboard focus, and closes on Escape', async ({ page }) => {
  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '로그인' })

  await expect(page.locator('body')).toHaveCSS('overflow', 'hidden')
  await page.keyboard.press('Shift+Tab')
  await expect(dialog.getByRole('button', { name: '로그인 닫기' })).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(dialog.getByRole('textbox', { name: '이메일' })).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
  await expect(page.locator('body')).not.toHaveCSS('overflow', 'hidden')
})

test('announces an email format error without submitting credentials', async ({ page }) => {
  const loginRequests = []
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/v1/auth/login') loginRequests.push(request)
  })
  await page.getByRole('button', { name: '로그인' }).click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  const email = dialog.getByRole('textbox', { name: '이메일' })
  await email.fill('user@')
  await dialog.getByRole('textbox', { name: '비밀번호', exact: true }).fill('sample1234')
  await dialog.getByRole('button', { name: '로그인', exact: true }).click()

  await expect(email).toHaveAttribute('aria-invalid', 'true')
  await expect(email).toHaveAttribute('aria-describedby', 'login-email-error')
  await expect(dialog.getByRole('alert')).toHaveText('이메일 형식을 확인하세요.')
  expect(loginRequests).toHaveLength(0)
})

test('toggles password visibility with an accessible 44px control', async ({ page }) => {
  await page.getByRole('button', { name: '로그인' }).click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  const password = dialog.getByRole('textbox', { name: '비밀번호', exact: true })
  await password.fill('sample1234')

  const show = dialog.getByRole('button', { name: '비밀번호 표시' })
  const box = await show.boundingBox()
  expect(box.width).toBeGreaterThanOrEqual(44)
  expect(box.height).toBeGreaterThanOrEqual(44)
  await show.click()
  await expect(password).toHaveAttribute('type', 'text')
  await expect(password).toHaveValue('sample1234')

  await dialog.getByRole('button', { name: '비밀번호 숨기기' }).click()
  await expect(password).toHaveAttribute('type', 'password')
})

test('submits once with the auth contract, shows loading, and renders identity from me', async ({ page }) => {
  let pendingLogin
  let loginCount = 0
  let loginBody
  let loggedIn = false
  await page.route('**/v1/auth/login', route => {
    loginCount += 1
    loginBody = route.request().postDataJSON()
    pendingLogin = route
  })
  await page.route('**/v1/auth/me', route => route.fulfill(loggedIn
    ? { json: user }
    : { status: 401, json: { code: 'TOKEN_MISSING' } }))

  await page.getByRole('button', { name: '로그인' }).click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  await dialog.getByRole('textbox', { name: '이메일' }).fill('user@fashion.shop')
  await dialog.getByRole('textbox', { name: '비밀번호', exact: true }).fill('sample1234')
  await dialog.locator('form').evaluate(form => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  })

  const submit = dialog.getByRole('button', { name: '로그인 중…' })
  await expect(submit).toBeDisabled()
  await expect.poll(() => loginCount).toBe(1)
  expect(loginBody).toEqual({ email: 'user@fashion.shop', password: 'sample1234' })

  loggedIn = true
  await pendingLogin.fulfill({ json: {} })
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).toContainText('구매자님')
})

test('announces auth failure and associates it with the password field', async ({ page }) => {
  await page.route('**/v1/auth/login', route => route.fulfill({
    status: 401,
    json: { code: 'INVALID_CREDENTIALS', message: 'server detail' },
  }))
  await page.getByRole('button', { name: '로그인' }).click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  await dialog.getByRole('textbox', { name: '이메일' }).fill('user@fashion.shop')
  const password = dialog.getByRole('textbox', { name: '비밀번호', exact: true })
  await password.fill('wrong-password')
  await dialog.getByRole('button', { name: '로그인', exact: true }).click()

  await expect(dialog.getByRole('alert')).toHaveText('이메일 또는 비밀번호를 확인하세요.')
  await expect(password).toHaveAttribute('aria-invalid', 'true')
  await expect(password).toHaveAttribute('aria-describedby', 'login-password-error')
  await expect(dialog.locator('#login-password-error')).toHaveText('입력 내용을 확인하세요.')
  await expect(dialog.getByRole('button', { name: '로그인', exact: true })).toBeEnabled()
})

test('ignores a login response that arrives after the modal closes', async ({ page }) => {
  let pendingLogin
  let meAfterClose = 0
  await page.route('**/v1/auth/login', route => { pendingLogin = route })
  await page.route('**/v1/auth/me', route => {
    meAfterClose += 1
    return route.fulfill({ json: user })
  })

  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  await dialog.getByRole('textbox', { name: '이메일' }).fill('user@fashion.shop')
  await dialog.getByRole('textbox', { name: '비밀번호', exact: true }).fill('sample1234')
  await dialog.getByRole('button', { name: '로그인', exact: true }).click()
  await dialog.getByRole('button', { name: '로그인 닫기' }).click()
  await pendingLogin.fulfill({ json: {} })
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))

  expect(meAfterClose).toBe(0)
  await expect(trigger).toBeVisible()
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).not.toContainText('구매자님')
})

test('ignores an identity response that arrives after the modal closes', async ({ page }) => {
  let pendingMe
  await page.route('**/v1/auth/login', route => route.fulfill({ json: {} }))
  await page.route('**/v1/auth/me', route => { pendingMe = route })

  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  await dialog.getByRole('textbox', { name: '이메일' }).fill('user@fashion.shop')
  await dialog.getByRole('textbox', { name: '비밀번호', exact: true }).fill('sample1234')
  await dialog.getByRole('button', { name: '로그인', exact: true }).click()
  await expect.poll(() => pendingMe !== undefined).toBe(true)
  await dialog.getByRole('button', { name: '로그인 닫기' }).click()
  await pendingMe.fulfill({ json: user })
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))

  await expect(trigger).toBeVisible()
  await expect(page.getByRole('navigation', { name: '주요 메뉴' })).not.toContainText('구매자님')
})

test('closes from the overlay and exposes no unapproved account features', async ({ page }) => {
  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: '로그인' })
  await expect(dialog.getByText(/회원가입|비밀번호 재설정|소셜 로그인/)).toHaveCount(0)

  await page.locator('.modal-overlay').click({ position: { x: 2, y: 2 } })
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
})

test('reopens in the default state without retaining credentials or validation errors', async ({ page }) => {
  const trigger = page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '로그인' })
  await trigger.click()
  let dialog = page.getByRole('dialog', { name: '로그인' })
  const email = dialog.getByRole('textbox', { name: '이메일' })
  const password = dialog.getByRole('textbox', { name: '비밀번호', exact: true })
  await email.fill('user@')
  await password.fill('sample1234')
  await dialog.getByRole('button', { name: '비밀번호 표시' }).click()
  await dialog.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(dialog.getByRole('alert')).toHaveText('이메일 형식을 확인하세요.')
  await dialog.getByRole('button', { name: '로그인 닫기' }).click()

  await trigger.click()
  dialog = page.getByRole('dialog', { name: '로그인' })
  await expect(dialog.getByRole('textbox', { name: '이메일' })).toHaveValue('')
  await expect(dialog.getByRole('textbox', { name: '비밀번호', exact: true })).toHaveValue('')
  await expect(dialog.getByRole('textbox', { name: '비밀번호', exact: true })).toHaveAttribute('type', 'password')
  await expect(dialog.getByRole('alert')).toHaveCount(0)
})
