const GW = 'http://localhost:8000'
const PRODUCT_SERVICE = 'http://localhost:8084'
const ADMIN = { email: 'admin@example.com', password: 'password123', name: '관리자', phone: '010-0000-0000' }

async function json(response, operation) {
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`${operation} returned HTTP ${response.status()} with invalid JSON: ${text}`)
  }
  if (!response.ok()) {
    throw new Error(`${operation} failed with HTTP ${response.status()}: ${text || '<empty body>'}`)
  }
  return body
}

async function createCategory(request, name, parentId) {
  const data = parentId === undefined ? { name } : { name, parentId }
  return json(
    await request.post(`${PRODUCT_SERVICE}/v1/categories`, { data }),
    `POST product-service /v1/categories (${name})`,
  )
}

async function createCategoryPath(request, runKey) {
  const names = ['E2E 의류', 'E2E 상의', 'E2E 티셔츠'].map(name => `${name} ${runKey}`)
  let parentId
  for (const name of names) {
    const category = await createCategory(request, name, parentId)
    parentId = category.id
  }
  return parentId
}

async function authenticateAdmin(request) {
  const signup = await request.post(`${GW}/v1/auth/signup`, { data: ADMIN })
  if (!signup.ok() && signup.status() !== 409) await json(signup, 'POST gateway /v1/auth/signup')
  await json(
    await request.post(`${GW}/v1/auth/login`, { data: { email: ADMIN.email, password: ADMIN.password } }),
    'POST gateway /v1/auth/login',
  )
  const state = await request.storageState()
  const token = state.cookies.find(cookie => cookie.name === 'csrf_token')?.value
  if (!token) throw new Error('POST gateway /v1/auth/login succeeded without a csrf_token cookie.')
  return { 'X-CSRF-Token': token }
}

export async function setupRunCatalog(request, { runKey, env = process.env }) {
  const productName = `베이직 티셔츠 ${runKey}`
  const categoryId = await createCategoryPath(request, runKey)
  const headers = await authenticateAdmin(request)
  const created = await json(
    await request.post(`${GW}/v1/products`, {
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
