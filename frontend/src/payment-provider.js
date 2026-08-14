export function createMockPaymentReturnUrl(provider, origin, prepared) {
  if (provider !== 'mock') return null
  const url = new URL('/payment/success', origin)
  url.searchParams.set('paymentKey', `mock_${prepared.paymentRequestId}`)
  url.searchParams.set('orderId', prepared.paymentRequestId)
  url.searchParams.set('amount', prepared.amount)
  return url.href
}
