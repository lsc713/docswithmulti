import assert from 'node:assert/strict'
import test from 'node:test'

const e2eUrls = await import('../e2e/helpers/urls.js').catch(() => ({}))

test('uses the required frontend, gateway, and product E2E defaults', () => {
  assert.equal(typeof e2eUrls.resolveE2EUrls, 'function')
  assert.deepEqual(e2eUrls.resolveE2EUrls({}), {
    frontend: 'http://localhost:5173',
    gateway: 'http://localhost:8000',
    product: 'http://localhost:8084',
  })
})

test('uses and normalizes all explicit E2E endpoint overrides', () => {
  assert.deepEqual(e2eUrls.resolveE2EUrls({
    E2E_FRONTEND_BASE_URL: 'https://127.0.0.1:6173/',
    E2E_GATEWAY_BASE_URL: 'http://api.localhost:9000//',
    E2E_PRODUCT_BASE_URL: 'http://[::1]:9084///',
  }), {
    frontend: 'https://127.0.0.1:6173',
    gateway: 'http://api.localhost:9000',
    product: 'http://[::1]:9084',
  })
})

test('rejects a malformed E2E endpoint', () => {
  assert.throws(
    () => e2eUrls.resolveE2EUrls({ E2E_GATEWAY_BASE_URL: 'not a URL' }),
    /E2E_GATEWAY_BASE_URL must be a valid URL/,
  )
})

test('rejects credentials in an E2E endpoint', () => {
  assert.throws(
    () => e2eUrls.resolveE2EUrls({ E2E_PRODUCT_BASE_URL: 'http://user:secret@localhost:8084' }),
    /E2E_PRODUCT_BASE_URL must not contain credentials/,
  )
})

test('rejects a non-HTTP E2E endpoint', () => {
  assert.throws(
    () => e2eUrls.resolveE2EUrls({ E2E_FRONTEND_BASE_URL: 'file:///tmp/frontend' }),
    /E2E_FRONTEND_BASE_URL must use http or https/,
  )
})

test('rejects nonlocal E2E endpoints', () => {
  for (const value of ['https://example.com', 'http://127.evil:8000']) {
    assert.throws(
      () => e2eUrls.resolveE2EUrls({ E2E_GATEWAY_BASE_URL: value }),
      /E2E_GATEWAY_BASE_URL must use a local host/,
    )
  }
})

test('rejects an E2E base with a query or fragment', () => {
  assert.throws(
    () => e2eUrls.resolveE2EUrls({ E2E_PRODUCT_BASE_URL: 'http://localhost:8084/?debug=1' }),
    /E2E_PRODUCT_BASE_URL must not contain a query or fragment/,
  )
})

test('binds Playwright baseURL to the frontend E2E override', async () => {
  const previous = process.env.E2E_FRONTEND_BASE_URL
  process.env.E2E_FRONTEND_BASE_URL = 'http://127.0.0.1:6173/'
  try {
    const config = await import(`../playwright.config.js?test=${Date.now()}`)
    assert.equal(config.default.use.baseURL, 'http://127.0.0.1:6173')
  } finally {
    if (previous === undefined) delete process.env.E2E_FRONTEND_BASE_URL
    else process.env.E2E_FRONTEND_BASE_URL = previous
  }
})
