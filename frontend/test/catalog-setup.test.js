import assert from 'node:assert/strict'
import test from 'node:test'

import * as catalogSetup from '../e2e/helpers/catalog-setup.js'

class FakeResponse {
  constructor(status, body = {}) {
    this.statusCode = status
    this.body = body
  }

  ok() { return this.statusCode >= 200 && this.statusCode < 300 }
  status() { return this.statusCode }
  async text() { return JSON.stringify(this.body) }
}

function fakeRequest(handler, cookies = []) {
  const calls = []
  return {
    calls,
    async get(url, options = {}) {
      calls.push({ method: 'GET', url, ...options })
      return handler('GET', url, options)
    },
    async post(url, options = {}) {
      calls.push({ method: 'POST', url, ...options })
      return handler('POST', url, options)
    },
    async storageState() { return { cookies } },
  }
}

test('rejects invalid endpoint configuration before any API write', async () => {
  const request = fakeRequest(() => {
    throw new Error('API must not be called')
  })

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'invalid-endpoint',
      env: {
        E2E_ADMIN_EMAIL: 'bootstrap@example.test',
        E2E_GATEWAY_BASE_URL: 'https://example.com',
      },
    }),
    /E2E_GATEWAY_BASE_URL must use a local host/,
  )
  assert.equal(request.calls.length, 0)
})

test('uses only overridden gateway and product endpoints for catalog setup', async () => {
  const gateway = 'http://127.0.0.1:9000'
  const product = 'http://127.0.0.1:9084'
  let categoryId = 100
  const request = fakeRequest((method, url, options) => {
    if (method === 'POST' && url === `${gateway}/v1/auth/signup`) return new FakeResponse(200)
    if (method === 'POST' && url === `${gateway}/v1/auth/login`) return new FakeResponse(200)
    if (method === 'GET' && url === `${gateway}/v1/auth/me`) {
      return new FakeResponse(200, { role: 'ADMIN' })
    }
    if (method === 'POST' && url === `${product}/v1/categories`) {
      return new FakeResponse(200, { id: categoryId++ })
    }
    if (method === 'POST' && url === `${gateway}/v1/products`) {
      return new FakeResponse(200, { productId: 55, skus: options.data.skus })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value' }])

  await catalogSetup.setupRunCatalog(request, {
    runKey: 'overridden-endpoints',
    env: {
      E2E_ADMIN_EMAIL: 'bootstrap@example.test',
      E2E_GATEWAY_BASE_URL: `${gateway}/`,
      E2E_PRODUCT_BASE_URL: `${product}/`,
    },
  })

  assert.equal(request.calls.some(call => call.url.startsWith('http://localhost:8000')), false)
  assert.equal(request.calls.some(call => call.url.startsWith('http://localhost:8084')), false)
  assert.equal(request.calls.every(call => call.url.startsWith(gateway) || call.url.startsWith(product)), true)
})

test('rejects a missing admin email before any API write', async () => {
  const request = fakeRequest(() => {
    throw new Error('API must not be called')
  })

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, { runKey: 'missing-email', env: {} }),
    /E2E_ADMIN_EMAIL is required/,
  )
  assert.equal(request.calls.length, 0)
})

test('rejects an invalid admin email before any API write without exposing it', async () => {
  const request = fakeRequest(() => {
    throw new Error('API must not be called')
  })
  const invalidEmail = 'private-invalid-value'

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'invalid-email',
      env: { E2E_ADMIN_EMAIL: invalidEmail },
    }),
    error => error.message === 'E2E_ADMIN_EMAIL must be a valid email address.'
      && !error.message.includes(invalidEmail),
  )
  assert.equal(request.calls.length, 0)
})

test('treats non-409 signup failure as fatal before login without exposing the email', async () => {
  const adminEmail = 'bootstrap@example.test'
  const request = fakeRequest((method, url) => {
    if (url === 'http://localhost:8000/v1/auth/signup') {
      return new FakeResponse(400, { code: 'INVALID_SIGNUP', email: adminEmail })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  })

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'signup-failure',
      env: { E2E_ADMIN_EMAIL: adminEmail },
    }),
    error => /POST gateway \/v1\/auth\/signup failed with HTTP 400/.test(error.message)
      && !error.message.includes(adminEmail),
  )
  assert.equal(request.calls.filter(call => call.url.endsWith('/login')).length, 0)
})

test('continues from signup 409 through login and strict ADMIN verification', async () => {
  let nextCategoryId = 1
  const request = fakeRequest((method, url, options) => {
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(409, { code: 'EMAIL_ALREADY_EXISTS' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200)
    if (url.endsWith('/v1/auth/me')) return new FakeResponse(200, { role: 'ADMIN' })
    if (url === 'http://localhost:8084/v1/categories') return new FakeResponse(200, { id: nextCategoryId++ })
    if (url.endsWith('/v1/products')) return new FakeResponse(200, { productId: 9, skus: options.data.skus })
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value' }])

  const product = await catalogSetup.setupRunCatalog(request, {
    runKey: 'signup-conflict',
    env: { E2E_ADMIN_EMAIL: 'bootstrap@example.test' },
  })

  assert.equal(product.productId, 9)
  assert.deepEqual(
    request.calls.slice(0, 3).map(call => `${call.method} ${new URL(call.url).pathname}`),
    ['POST /v1/auth/signup', 'POST /v1/auth/login', 'GET /v1/auth/me'],
  )
})

test('rejects a logged-in USER before catalog writes', async () => {
  let nextCategoryId = 1
  const request = fakeRequest((method, url) => {
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/me')) {
      return new FakeResponse(200, {
        userId: 7,
        email: 'bootstrap@example.test',
        name: '관리자',
        role: 'USER',
      })
    }
    if (url.endsWith('/v1/categories')) return new FakeResponse(200, { id: nextCategoryId++ })
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value' }])

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'user-role',
      env: { E2E_ADMIN_EMAIL: 'bootstrap@example.test' },
    }),
    /authenticated account must have ADMIN role/,
  )
  assert.equal(request.calls.filter(call => call.url.includes('/categories')).length, 0)
  assert.equal(request.calls.filter(call => call.url.endsWith('/products')).length, 0)
})

test('rejects a login profile with no role before catalog writes', async () => {
  const request = fakeRequest((method, url) => {
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/me')) {
      return new FakeResponse(200, { userId: 7, email: 'bootstrap@example.test', name: '관리자' })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value' }])

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'missing-role',
      env: { E2E_ADMIN_EMAIL: 'bootstrap@example.test' },
    }),
    /authenticated account must have ADMIN role/,
  )
  assert.equal(request.calls.filter(call => call.url.includes('/categories')).length, 0)
  assert.equal(request.calls.filter(call => call.url.endsWith('/products')).length, 0)
})

test('creates one run-specific catalog seed and exposes its exact product name', async () => {
  let nextCategoryId = 100
  const request = fakeRequest((method, url, options) => {
    if (method === 'POST' && url === 'http://localhost:8084/v1/categories') {
      const id = nextCategoryId++
      return new FakeResponse(200, { id, level: id - 99 })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/auth/signup') {
      return new FakeResponse(200, { result: 'OK' })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/auth/login') {
      return new FakeResponse(200, { result: 'OK' })
    }
    if (method === 'GET' && url === 'http://localhost:8000/v1/auth/me') {
      return new FakeResponse(200, {
        userId: 7,
        email: 'bootstrap@example.test',
        name: '관리자',
        role: 'ADMIN',
      })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/products') {
      return new FakeResponse(200, {
        productId: 55,
        skus: [{ skuId: 66, skuCode: options.data.skus[0].skuCode }],
      })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value', domain: 'localhost', path: '/' }])
  const env = { E2E_ADMIN_EMAIL: 'bootstrap@example.test' }

  const product = await catalogSetup.setupRunCatalog(request, { runKey: 'run-42', env })

  assert.deepEqual(product, {
    productId: 55,
    productName: '베이직 티셔츠 run-42',
    skus: [{ skuId: 66, skuCode: 'E2E-BASIC-TEE-run-42' }],
  })
  assert.equal(env.E2E_PRODUCT_NAME, '베이직 티셔츠 run-42')
  assert.deepEqual(
    request.calls.slice(0, 3).map(call => `${call.method} ${new URL(call.url).pathname}`),
    ['POST /v1/auth/signup', 'POST /v1/auth/login', 'GET /v1/auth/me'],
  )
  const categoryPosts = request.calls.filter(call => call.url === 'http://localhost:8084/v1/categories')
  assert.deepEqual(categoryPosts.map(call => call.data), [
    { name: 'E2E 의류 run-42' },
    { name: 'E2E 상의 run-42', parentId: 100 },
    { name: 'E2E 티셔츠 run-42', parentId: 101 },
  ])
  assert.deepEqual(categoryPosts.map(call => call.headers), [
    { 'X-User-Role': 'ADMIN' },
    { 'X-User-Role': 'ADMIN' },
    { 'X-User-Role': 'ADMIN' },
  ])
  const productPost = request.calls.find(call => call.url === 'http://localhost:8000/v1/products')
  assert.deepEqual(productPost.headers, { 'X-CSRF-Token': 'csrf-value' })
  assert.deepEqual(productPost.data, {
    name: '베이직 티셔츠 run-42',
    categoryId: 102,
    skus: [{ skuCode: 'E2E-BASIC-TEE-run-42', optionSummary: '화이트 / M', initialStock: 100, price: 19000 }],
  })
})

test('fails the run-specific seed directly instead of recovering from a uniqueness conflict', async () => {
  let nextCategoryId = 1
  const request = fakeRequest((method, url) => {
    if (method === 'POST' && url === 'http://localhost:8084/v1/categories') {
      return new FakeResponse(200, { id: nextCategoryId, level: nextCategoryId++ })
    }
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/me')) {
      return new FakeResponse(200, {
        userId: 7,
        email: 'bootstrap@example.test',
        name: '관리자',
        role: 'ADMIN',
      })
    }
    if (method === 'POST' && url.endsWith('/v1/products')) {
      return new FakeResponse(409, { code: 'DUPLICATE_SKU' })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'token' }])

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'unique-run',
      env: { E2E_ADMIN_EMAIL: 'bootstrap@example.test' },
    }),
    /POST gateway \/v1\/products failed with HTTP 409: .*DUPLICATE_SKU/,
  )
  assert.deepEqual(
    request.calls.filter(call => call.method === 'GET').map(call => new URL(call.url).pathname),
    ['/v1/auth/me'],
  )
})

test('worker selectors require the exact product name exported by global setup', () => {
  assert.equal(
    catalogSetup.runProductName({ E2E_PRODUCT_NAME: '베이직 티셔츠 exact-run' }),
    '베이직 티셔츠 exact-run',
  )
  assert.throws(
    () => catalogSetup.runProductName({}),
    /E2E_PRODUCT_NAME is missing; Playwright globalSetup did not seed the run catalog/,
  )
})

test('reports the failed operation, status, and response body', async () => {
  const request = fakeRequest((method, url) => {
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200, { result: 'OK' })
    if (url.endsWith('/v1/auth/me')) {
      return new FakeResponse(200, {
        userId: 7,
        email: 'bootstrap@example.test',
        name: '관리자',
        role: 'ADMIN',
      })
    }
    if (url.endsWith('/v1/categories')) return new FakeResponse(503, { code: 'CATALOG_DOWN' })
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value' }])

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, {
      runKey: 'failed-run',
      env: { E2E_ADMIN_EMAIL: 'bootstrap@example.test' },
    }),
    /POST product-service \/v1\/categories \(E2E 의류 failed-run\) failed with HTTP 503: .*CATALOG_DOWN/,
  )
})
