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

  categories:         ()          => req('/v1/categories'),
  productsByCategory: (id, page = 0) => req(`/v1/categories/${id}/products?page=${page}`),
  product:            (id)        => req(`/v1/products/${id}`),
  presignImage:       (id, contentType) =>
    req(`/v1/products/${id}/images/presign`, { method: 'POST', body: { contentType }, csrf: true }),
  confirmImage:       (id, key, sortOrder) =>
    req(`/v1/products/${id}/images`, { method: 'POST', body: { key, sortOrder }, csrf: true }),
  deleteImage:        (id, imageId) =>
    req(`/v1/products/${id}/images/${imageId}`, { method: 'DELETE', csrf: true }),
  reorderImages:      (id, imageIds) =>
    req(`/v1/products/${id}/images/order`, { method: 'PUT', body: { imageIds }, csrf: true }),

  adminUsers:   (page = 0, size = 20) => req(`/v1/admin/users?page=${page}&size=${size}`),
  changeRole:   (userId, role) =>
    req(`/v1/admin/users/${userId}/role`, { method: 'PATCH', body: { role }, csrf: true }),
  createProduct: (body) => req('/v1/products', { method: 'POST', body, csrf: true }),
  createOrder:   (b) => req('/v1/orders',   { method: 'POST', body: b, csrf: true }),
  createPayment: (b) => req('/v1/payments', { method: 'POST', body: b, csrf: true }),
  getPayments:   () => req('/v1/payments'),
  getPayment:    (key) => req(`/v1/payments/${key}`),
  cancelPayment: (key, body) => req(`/v1/payments/${key}/cancel`, { method: 'POST', body, csrf: true }),
  requestCancel: (key, reason) =>
    req(`/v1/payments/${key}/cancel-requests`, { method: 'POST', body: { reason }, csrf: true }),

  getCart:        ()             => req('/v1/cart'),
  addCartItem:    (b)            => req('/v1/cart/items', { method: 'POST', body: b, csrf: true }),
  updateCartItem: (skuId, quantity) => req(`/v1/cart/items/${skuId}`, { method: 'PATCH', body: { quantity }, csrf: true }),
  removeCartItem: (skuId)        => req(`/v1/cart/items/${skuId}`, { method: 'DELETE', csrf: true }),
  clearCart:      ()             => req('/v1/cart', { method: 'DELETE', csrf: true }),

  cancelRequests: (status = 'REQUESTED') => req(`/v1/cancel-requests?status=${status}`),
  approveCancel:  (id) => req(`/v1/cancel-requests/${id}/approve`, { method: 'POST', csrf: true }),
  rejectCancel:   (id, decisionReason) =>
    req(`/v1/cancel-requests/${id}/reject`, { method: 'POST', body: { decisionReason }, csrf: true }),
}

export async function putToS3(uploadUrl, file) {
  const res = await fetch(uploadUrl, {
    method: 'PUT', body: file,
    headers: { 'Content-Type': file.type },
    credentials: 'omit',                    // S3엔 쿠키 안 보냄
  })
  if (!res.ok) throw new Error(`업로드 실패 HTTP ${res.status}`)
}
