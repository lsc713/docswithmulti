import { useState } from 'react'
import { api } from '../api'

const lineName = (l) => `${l.itemName} ${l.optionSummary ?? ''}`.trim()
let tossScript

function loadToss() {
  if (window.TossPayments) return Promise.resolve(window.TossPayments)
  if (!tossScript) tossScript = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://js.tosspayments.com/v2/standard'
    script.onload = () => resolve(window.TossPayments)
    script.onerror = () => reject(new Error('결제창을 불러오지 못했습니다.'))
    document.head.appendChild(script)
  })
  return tossScript
}

export default function Checkout({ lines, fromCart, retryItems, onBack }) {
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const total = lines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0)

  async function pay() {
    setErr(''); setBusy(true)
    try {
      let paymentItems = retryItems
      if (!paymentItems) {
        const order = await api.createOrder({
          items: lines.map(l => ({ productId: l.productId, itemName: lineName(l), price: l.unitPrice * l.quantity })),
        })
        paymentItems = lines.map((l, i) => ({
          orderItemId: order.items[i].orderItemId,
          productId: l.productId,
          itemName: lineName(l),
          skuId: l.skuId,
          quantity: l.quantity,
        }))
      }
      const prepared = await api.preparePayment({
        merchantId: 1, pgType: 'NORMAL', cancelPeriodDays: 7, items: paymentItems,
      })
      sessionStorage.setItem('paymentAttempt', JSON.stringify({
        paymentRequestId: prepared.paymentRequestId, lines, fromCart, paymentItems,
      }))
      const TossPayments = await loadToss()
      await TossPayments(prepared.clientKey).payment({ customerKey: prepared.customerKey })
        .requestPayment({
          method: 'CARD',
          amount: { currency: 'KRW', value: Number(prepared.amount) },
          orderId: prepared.paymentRequestId,
          orderName: prepared.orderName,
          successUrl: `${window.location.origin}/payment/success`,
          failUrl: `${window.location.origin}/payment/fail`,
        })
    } catch (e) {
      setErr(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="checkout">
      <button onClick={onBack}>뒤로</button>
      <h1>주문하기</h1>
      <table className="checkout-table">
        <thead><tr><th>상품</th><th>옵션</th><th>수량</th><th>단가</th><th>합계</th></tr></thead>
        <tbody>
          {lines.map((l, i) => (
            <tr key={i}>
              <td>{l.itemName}</td><td>{l.optionSummary}</td><td>{l.quantity}</td>
              <td>₩{l.unitPrice.toLocaleString()}</td>
              <td>₩{(l.unitPrice * l.quantity).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="checkout-total">총 결제금액 <strong>₩{total.toLocaleString()}</strong></p>
      <button className="pay-btn" onClick={pay} disabled={busy || lines.length === 0}>
        {busy ? '결제 중...' : '결제하기'}
      </button>
      {err && <p className="error">{err}</p>}
    </main>
  )
}
