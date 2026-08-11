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

test('creates one run-specific catalog seed and exposes its exact product name', async () => {
  let nextCategoryId = 100
  const request = fakeRequest((method, url, options) => {
    if (method === 'POST' && url === 'http://localhost:8084/v1/categories') {
      const id = nextCategoryId++
      return new FakeResponse(200, { id, level: id - 99 })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/auth/signup') {
      return new FakeResponse(409, { code: 'EMAIL_ALREADY_EXISTS' })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/auth/login') {
      return new FakeResponse(200, { result: 'OK' })
    }
    if (method === 'POST' && url === 'http://localhost:8000/v1/products') {
      return new FakeResponse(200, {
        productId: 55,
        skus: [{ skuId: 66, skuCode: options.data.skus[0].skuCode }],
      })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'csrf-value', domain: 'localhost', path: '/' }])
  const env = {}

  const product = await catalogSetup.setupRunCatalog(request, { runKey: 'run-42', env })

  assert.deepEqual(product, {
    productId: 55,
    productName: '베이직 티셔츠 run-42',
    skus: [{ skuId: 66, skuCode: 'E2E-BASIC-TEE-run-42' }],
  })
  assert.equal(env.E2E_PRODUCT_NAME, '베이직 티셔츠 run-42')
  assert.equal(request.calls.filter(call => call.method === 'GET').length, 0)
  const categoryPosts = request.calls.filter(call => call.url === 'http://localhost:8084/v1/categories')
  assert.deepEqual(categoryPosts.map(call => call.data), [
    { name: 'E2E 의류 run-42' },
    { name: 'E2E 상의 run-42', parentId: 100 },
    { name: 'E2E 티셔츠 run-42', parentId: 101 },
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
    if (url.endsWith('/v1/auth/signup')) return new FakeResponse(409, { code: 'EMAIL_ALREADY_EXISTS' })
    if (url.endsWith('/v1/auth/login')) return new FakeResponse(200, { result: 'OK' })
    if (method === 'POST' && url.endsWith('/v1/products')) {
      return new FakeResponse(409, { code: 'DUPLICATE_SKU' })
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }, [{ name: 'csrf_token', value: 'token' }])

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, { runKey: 'unique-run', env: {} }),
    /POST gateway \/v1\/products failed with HTTP 409: .*DUPLICATE_SKU/,
  )
  assert.equal(request.calls.filter(call => call.method === 'GET').length, 0)
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
  const request = fakeRequest(() => new FakeResponse(503, { code: 'CATALOG_DOWN' }))

  await assert.rejects(
    catalogSetup.setupRunCatalog(request, { runKey: 'failed-run', env: {} }),
    /POST product-service \/v1\/categories \(E2E 의류 failed-run\) failed with HTTP 503: .*CATALOG_DOWN/,
  )
})
