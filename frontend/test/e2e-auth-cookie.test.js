import assert from 'node:assert/strict'
import http from 'node:http'
import test from 'node:test'

import { request } from '@playwright/test'
import { assertE2EAuthCookieCompatibility } from '../e2e/helpers/urls.js'

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return `http://127.0.0.1:${server.address().port}`
}

async function close(server) {
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
}

test('local HTTP E2E override lets one APIRequestContext carry login cookie to /v1/auth/me', async () => {
  const calls = []
  const server = http.createServer((req, res) => {
    calls.push({ method: req.method, url: req.url, cookie: req.headers.cookie })
    if (req.method === 'POST' && req.url === '/v1/auth/login') {
      res.writeHead(200, {
        'content-type': 'application/json',
        'set-cookie': 'access_token=local-e2e-token; HttpOnly; Path=/; SameSite=Lax',
      })
      res.end('{}')
      return
    }
    if (req.method === 'GET' && req.url === '/v1/auth/me') {
      const authenticated = req.headers.cookie?.includes('access_token=local-e2e-token')
      res.writeHead(authenticated ? 200 : 401, { 'content-type': 'application/json' })
      res.end(JSON.stringify(authenticated ? { role: 'ADMIN' } : { code: 'TOKEN_MISSING' }))
      return
    }
    res.writeHead(404).end()
  })
  const gateway = await listen(server)
  const context = await request.newContext()
  try {
    assert.doesNotThrow(() => assertE2EAuthCookieCompatibility({
      E2E_GATEWAY_BASE_URL: gateway,
      AUTH_COOKIE_SECURE: 'false',
    }))
    assert.equal((await context.post(`${gateway}/v1/auth/login`)).status(), 200)
    assert.equal((await context.get(`${gateway}/v1/auth/me`)).status(), 200)
    assert.match(calls[1].cookie, /access_token=local-e2e-token/)
  } finally {
    await context.dispose()
    await close(server)
  }
})

test('HTTP loopback with default or secure cookies fails before any API request', async () => {
  for (const secure of [undefined, 'true']) {
    assert.throws(
      () => assertE2EAuthCookieCompatibility({
        E2E_GATEWAY_BASE_URL: 'http://127.0.0.1:8000',
        ...(secure === undefined ? {} : { AUTH_COOKIE_SECURE: secure }),
      }),
      /HTTP E2E gateway cannot propagate Secure auth cookies.*AUTH_COOKIE_SECURE=false/,
    )
  }
})

test('rejects insecure-cookie override outside the local HTTP E2E combination', () => {
  assert.throws(
    () => assertE2EAuthCookieCompatibility({
      E2E_GATEWAY_BASE_URL: 'https://127.0.0.1:8443',
      AUTH_COOKIE_SECURE: 'false',
    }),
    /AUTH_COOKIE_SECURE=false is allowed only with a local HTTP E2E gateway/,
  )
  assert.throws(
    () => assertE2EAuthCookieCompatibility({
      E2E_GATEWAY_BASE_URL: 'https://127.0.0.1:8443',
      AUTH_COOKIE_SECURE: 'invalid',
    }),
    /AUTH_COOKIE_SECURE must be true or false/,
  )
})

test('keeps secure cookies for HTTPS E2E by default and explicitly', () => {
  for (const secure of [undefined, 'true']) {
    assert.doesNotThrow(() => assertE2EAuthCookieCompatibility({
      E2E_GATEWAY_BASE_URL: 'https://127.0.0.1:8443',
      ...(secure === undefined ? {} : { AUTH_COOKIE_SECURE: secure }),
    }))
  }
})
