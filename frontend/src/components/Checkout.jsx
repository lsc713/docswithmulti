import { useState } from 'react'
import { api } from '../api'

const lineName = (l) => `${l.itemName} ${l.optionSummary ?? ''}`.trim()

export default function Checkout({ lines, onPaid, onBack }) {
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const total = lines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0)

  async function pay() {
    setErr(''); setBusy(true)
    try {
      const order = await api.createOrder({
        items: lines.map(l => ({ productId: l.productId, itemName: lineName(l), price: l.unitPrice * l.quantity })),
      })
      const payment = await api.createPayment({
        merchantId: 1, pgType: 'TOSS', cancelPeriodDays: 7,
        items: lines.map((l, i) => ({
          orderItemId: order.items[i].orderItemId,
          productId: l.productId,
          itemName: lineName(l),
          itemAmount: l.unitPrice * l.quantity,
          skuId: l.skuId,
          quantity: l.quantity,
        })),
      })
      onPaid(payment)
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
