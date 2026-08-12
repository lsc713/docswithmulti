import assert from 'node:assert/strict'
import test from 'node:test'

const apiBase = await import('../src/api-base.js').catch(() => ({}))

test('uses the production gateway default when no Vite override is set', () => {
  assert.equal(typeof apiBase.resolveApiBaseUrl, 'function')
  assert.equal(apiBase.resolveApiBaseUrl(), 'http://localhost:8000')
})

test('uses and normalizes an explicit local Vite API override', () => {
  assert.equal(apiBase.resolveApiBaseUrl('https://127.0.0.1:9443///'), 'https://127.0.0.1:9443')
})

test('rejects a malformed Vite API override', () => {
  assert.throws(() => apiBase.resolveApiBaseUrl('not a URL'), /valid URL/)
})

test('rejects a non-HTTP Vite API override', () => {
  assert.throws(() => apiBase.resolveApiBaseUrl('ftp://localhost:8000'), /http or https/)
})

test('rejects credentials in a Vite API override', () => {
  assert.throws(() => apiBase.resolveApiBaseUrl('http://user:secret@localhost:8000'), /credentials/)
})

test('rejects a nonlocal Vite API override', () => {
  assert.throws(() => apiBase.resolveApiBaseUrl('https://api.example.com'), /local host/)
  assert.throws(() => apiBase.resolveApiBaseUrl('http://127.evil:8000'), /local host/)
})

test('rejects a Vite API base with a query or fragment', () => {
  for (const value of ['http://localhost:8000/?debug=1', 'http://localhost:8000/#debug']) {
    assert.throws(() => apiBase.resolveApiBaseUrl(value), /query or fragment/)
  }
})
