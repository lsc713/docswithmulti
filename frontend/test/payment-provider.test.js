import assert from 'node:assert/strict'
import test from 'node:test'

import { createMockPaymentReturnUrl } from '../src/payment-provider.js'

test('mock provider creates the same success callback contract without Toss', () => {
  assert.equal(
    createMockPaymentReturnUrl('mock', 'http://localhost:5173', {
      paymentRequestId: 'request-1', amount: 29000,
    }),
    'http://localhost:5173/payment/success?paymentKey=mock_request-1&orderId=request-1&amount=29000',
  )
})

test('real provider does not create a mock callback', () => {
  assert.equal(createMockPaymentReturnUrl('toss', 'http://localhost:5173', {}), null)
})
