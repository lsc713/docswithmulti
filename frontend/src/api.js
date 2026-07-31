const BASE = 'http://localhost:8000'  // 게이트웨이. 실 cross-origin.

function csrfToken() {
  return document.cookie.split('; ').find(c => c.startsWith('csrf_token='))?.split('=')[1]
}

async function req(path, { method = 'GET', body, csrf = false } = {}) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (csrf) headers['X-CSRF-Token'] = csrfToken() ?? ''
  const res = await fetch(BASE + path, {
    method, headers,
    credentials: 'include',                 // 쿠키 송수신 (httpOnly 토큰은 JS가 못 봄)
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.message || data.code || `HTTP ${res.status}`)
  return data
}

export const api = {
  signup: (b) => req('/v1/auth/signup', { method: 'POST', body: b }),
  login:  (b) => req('/v1/auth/login',  { method: 'POST', body: b }),
  me:     ()  => req('/v1/auth/me'),
  logout: ()  => req('/v1/auth/logout', { method: 'POST', csrf: true }),
}
