export async function loginBuyer(page, user, base, gateway) {
  await page.goto(base)
  const nav = page.getByRole('navigation', { name: '주요 메뉴' })
  const loginButton = nav.getByRole('button', { name: '로그인' })
  const userMarker = nav.getByText(`${user.name}님`, { exact: true })
  if ((await page.request.get(`${gateway}/v1/auth/me`)).ok()) {
    await userMarker.waitFor({ state: 'visible' })
    if (!await loginButton.isHidden()) throw new Error('Authenticated navbar still shows the login button.')
    return
  }
  await loginButton.click()
  await page.fill('#login-email', user.email)
  await page.fill('#login-password', user.password)
  await page.click('.modal button[type="submit"]')
  await userMarker.waitFor({ state: 'visible' })
  const cookies = await page.context().cookies()
  if (!cookies.some(cookie => cookie.name === 'csrf_token' && cookie.value)) {
    throw new Error('Login succeeded without a csrf_token cookie.')
  }
}
