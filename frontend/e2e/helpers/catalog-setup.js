import { resolveE2EUrls } from './urls.js'

const ADMIN = { password: 'password123', name: '관리자', phone: '010-0000-0000' }

async function json(response, operation, redactions = []) {
  const text = await response.text()
  const safeText = redactions.reduce((value, secret) => value.replaceAll(secret, '<redacted>'), text)
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`${operation} returned HTTP ${response.status()} with invalid JSON: ${safeText}`)
  }
  if (!response.ok()) {
    throw new Error(`${operation} failed with HTTP ${response.status()}: ${safeText || '<empty body>'}`)
  }
  return body
}

async function createCategory(request, productBase, name, parentId) {
  const data = parentId === undefined ? { name } : { name, parentId }
  return json(
    await request.post(`${productBase}/v1/categories`, { data, headers: { 'X-User-Role': 'ADMIN' } }),
    `POST product-service /v1/categories (${name})`,
  )
}

async function createCategoryPath(request, productBase, runKey) {
  const names = ['E2E 의류', 'E2E 상의', 'E2E 티셔츠'].map(name => `${name} ${runKey}`)
  let parentId
  for (const name of names) {
    const category = await createCategory(request, productBase, name, parentId)
    parentId = category.id
  }
  return parentId
}

async function authenticateAdmin(request, gatewayBase, email) {
  const signup = await request.post(`${gatewayBase}/v1/auth/signup`, { data: { ...ADMIN, email } })
  await json(signup, 'POST gateway /v1/auth/signup', [email])
  await json(
    await request.post(`${gatewayBase}/v1/auth/login`, { data: { email, password: ADMIN.password } }),
    'POST gateway /v1/auth/login',
    [email],
  )
  const profile = await json(
    await request.get(`${gatewayBase}/v1/auth/me`),
    'GET gateway /v1/auth/me',
    [email],
  )
  if (profile.role !== 'ADMIN') throw new Error('The authenticated account must have ADMIN role.')
  const state = await request.storageState()
  const token = state.cookies.find(cookie => cookie.name === 'csrf_token')?.value
  if (!token) throw new Error('POST gateway /v1/auth/login succeeded without a csrf_token cookie.')
  return { 'X-CSRF-Token': token }
}

export async function setupRunCatalog(request, { runKey, env = process.env }) {
  const { gateway, product } = resolveE2EUrls(env)
  const email = env.E2E_ADMIN_EMAIL
  if (!email) throw new Error('E2E_ADMIN_EMAIL is required.')
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new Error('E2E_ADMIN_EMAIL must be a valid email address.')
  }
  const productName = `베이직 티셔츠 ${runKey}`
  const headers = await authenticateAdmin(request, gateway, email)
  const categoryId = await createCategoryPath(request, product, runKey)
  const created = await json(
    await request.post(`${gateway}/v1/products`, {
      headers,
      data: {
        name: productName,
        categoryId,
        skus: [{
          skuCode: `E2E-BASIC-TEE-${runKey}`,
          optionSummary: '화이트 / M',
          initialStock: 100,
          price: 19000,
        }],
      },
    }),
    'POST gateway /v1/products',
  )
  env.E2E_PRODUCT_NAME = productName
  return { productId: created.productId, productName, skus: created.skus }
}

export function runProductName(env = process.env) {
  const productName = env.E2E_PRODUCT_NAME
  if (!productName) {
    throw new Error('E2E_PRODUCT_NAME is missing; Playwright globalSetup did not seed the run catalog.')
  }
  return productName
}
