function endpoint(value, fallback, name) {
  if (!value) return fallback
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${name} must be a valid URL.`)
  }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error(`${name} must use http or https.`)
  if (url.username || url.password) throw new Error(`${name} must not contain credentials.`)
  if (url.search || url.hash) throw new Error(`${name} must not contain a query or fragment.`)
  const local = url.hostname === 'localhost'
    || url.hostname.endsWith('.localhost')
    || /^127(?:\.\d{1,3}){3}$/.test(url.hostname)
    || url.hostname === '[::1]'
  if (!local) throw new Error(`${name} must use a local host.`)
  return url.href.replace(/\/+$/, '')
}

export function resolveE2EUrls(env = process.env) {
  return {
    frontend: endpoint(env.E2E_FRONTEND_BASE_URL, 'http://localhost:5173', 'E2E_FRONTEND_BASE_URL'),
    gateway: endpoint(env.E2E_GATEWAY_BASE_URL, 'http://localhost:8000', 'E2E_GATEWAY_BASE_URL'),
    product: endpoint(env.E2E_PRODUCT_BASE_URL, 'http://localhost:8084', 'E2E_PRODUCT_BASE_URL'),
  }
}
