export function resolveApiBaseUrl(override) {
  if (!override) return 'http://localhost:8000'
  let url
  try {
    url = new URL(override)
  } catch {
    throw new Error('VITE_API_BASE_URL must be a valid URL.')
  }
  if (!['http:', 'https:'].includes(url.protocol)) {
    throw new Error('VITE_API_BASE_URL must use http or https.')
  }
  if (url.username || url.password) throw new Error('VITE_API_BASE_URL must not contain credentials.')
  if (url.search || url.hash) throw new Error('VITE_API_BASE_URL must not contain a query or fragment.')
  const local = url.hostname === 'localhost'
    || url.hostname.endsWith('.localhost')
    || /^127(?:\.\d{1,3}){3}$/.test(url.hostname)
    || url.hostname === '[::1]'
  if (!local) throw new Error('VITE_API_BASE_URL must use a local host.')
  return url.href.replace(/\/+$/, '')
}
