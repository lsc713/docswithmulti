import assert from 'node:assert/strict'
import test from 'node:test'

import { loginBuyer } from '../e2e/helpers/login-buyer.js'

const USER = { email: 'buyer@example.test', password: 'password123', name: '구매자' }

function fakePage({ authenticated = false, csrf = true } = {}) {
  const requests = []
  let loggedIn = false
  const loginButton = {
    async click() {},
    async isHidden() { return loggedIn },
  }
  const userMarker = {
    async isVisible() { return loggedIn },
    async waitFor() { assert.equal(loggedIn, true) },
  }

  return {
    requests,
    request: {
      async get() {
        loggedIn = authenticated
        return { ok: () => authenticated }
      },
    },
    async goto() {},
    locator(selector) {
      if (selector === '.navbar-right span') {
        return { async isVisible() { return true }, async waitFor() {} }
      }
      throw new Error(`Unexpected locator: ${selector}`)
    },
    getByRole(role, options) {
      assert.equal(role, 'navigation')
      assert.deepEqual(options, { name: '주요 메뉴' })
      return { getByRole: () => loginButton, getByText: () => userMarker }
    },
    async fill() {},
    async click(selector) {
      assert.equal(selector, '.modal button[type="submit"]')
      requests.push('POST /v1/auth/login')
      loggedIn = true
    },
    context() {
      return { async cookies() { return loggedIn && csrf ? [{ name: 'csrf_token', value: 'token' }] : [] } }
    },
  }
}

test('visible sr-only search text does not suppress buyer login', async () => {
  const page = fakePage()

  await loginBuyer(page, USER, 'http://localhost:5173', 'http://localhost:8000')

  assert.deepEqual(page.requests, ['POST /v1/auth/login'])
})

test('authenticated buyer is not logged in twice', async () => {
  const page = fakePage({ authenticated: true })

  await loginBuyer(page, USER, 'http://localhost:5173', 'http://localhost:8000')

  assert.deepEqual(page.requests, [])
})

test('successful login requires a csrf_token cookie', async () => {
  const page = fakePage({ csrf: false })

  await assert.rejects(
    loginBuyer(page, USER, 'http://localhost:5173', 'http://localhost:8000'),
    /csrf_token/,
  )
})
