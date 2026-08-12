import assert from 'node:assert/strict'
import test from 'node:test'

import { build } from 'vite'

test('keeps E2E configuration and write helpers out of the production bundle', async () => {
  const result = await build({ logLevel: 'silent', build: { write: false } })
  const outputs = (Array.isArray(result) ? result : [result]).flatMap(output => output.output)
  const bundle = outputs.map(output => output.type === 'chunk' ? output.code : String(output.source)).join('\n')

  for (const marker of [
    'E2E_FRONTEND_BASE_URL',
    'E2E_GATEWAY_BASE_URL',
    'E2E_PRODUCT_BASE_URL',
    'setupRunCatalog',
    'E2E-BASIC-TEE',
    'createPaidOrderViaApi',
    'order-flow-visual',
  ]) {
    assert.equal(bundle.includes(marker), false, `${marker} leaked into the production bundle`)
  }
})

test('allows the explicit local Vite API override in generated HTML CSP', async () => {
  const previous = process.env.VITE_API_BASE_URL
  process.env.VITE_API_BASE_URL = 'http://127.0.0.1:18000/'
  try {
    const result = await build({ logLevel: 'silent', build: { write: false } })
    const outputs = (Array.isArray(result) ? result : [result]).flatMap(output => output.output)
    const html = outputs.filter(output => output.fileName.endsWith('.html')).map(output => String(output.source))
    assert.equal(html.length, 2)
    assert.equal(html.every(source => source.includes("connect-src 'self' http://127.0.0.1:18000")), true)
    assert.equal(html.some(source => source.includes("connect-src 'self' http://localhost:8000")), false)
  } finally {
    if (previous === undefined) delete process.env.VITE_API_BASE_URL
    else process.env.VITE_API_BASE_URL = previous
  }
})
