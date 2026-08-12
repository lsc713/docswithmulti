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

export function assertE2EAuthCookieCompatibility(env = process.env) {
  const { gateway } = resolveE2EUrls(env)
  const secure = env.AUTH_COOKIE_SECURE
  if (secure !== undefined && !['true', 'false'].includes(secure)) {
    throw new Error('AUTH_COOKIE_SECURE must be true or false.')
  }

  const http = new URL(gateway).protocol === 'http:'
  if (http && secure !== 'false') {
    throw new Error(
      'HTTP E2E gateway cannot propagate Secure auth cookies; start the local E2E user-service '
      + 'and Playwright with AUTH_COOKIE_SECURE=false.',
    )
  }
  if (!http && secure === 'false') {
    throw new Error('AUTH_COOKIE_SECURE=false is allowed only with a local HTTP E2E gateway.')
  }
}
