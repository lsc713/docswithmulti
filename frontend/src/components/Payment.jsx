import { useState } from 'react'
import { api } from '../api'
import { aggregateOrderItems } from '../orderFlow'
import { OrderItemCard, OrderTotals } from './Checkout'

const lineName = line => `${line.itemName} ${line.optionSummary ?? ''}`.trim()
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

export default function Payment({ flowState, onBack }) {
  const orderItems = flowState?.orderItems ?? []
  const totals = aggregateOrderItems(orderItems)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function pay() {
    setError('')
    setBusy(true)
    try {
      let paymentItems = flowState.retryItems
      if (!paymentItems) {
        const order = await api.createOrder({
          items: orderItems.map(item => ({
            productId: item.productId,
            itemName: lineName(item),
            price: item.unitPrice * item.quantity,
          })),
        })
        paymentItems = orderItems.map((item, index) => ({
          orderItemId: order.items[index].orderItemId,
          productId: item.productId,
          itemName: lineName(item),
          skuId: item.skuId,
          quantity: item.quantity,
        }))
      }
      const prepared = await api.preparePayment({
        merchantId: 1,
        pgType: 'NORMAL',
        cancelPeriodDays: 7,
        items: paymentItems,
      })
      sessionStorage.setItem('paymentAttempt', JSON.stringify({
        paymentRequestId: prepared.paymentRequestId,
        orderItems,
        source: flowState.source,
        productId: flowState.productId,
        paymentItems,
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
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  if (!orderItems.length) {
    return (
      <main className="order-flow state">
        <section className="flow-state-card" role="status">
          <p className="flow-eyebrow">PAYMENT</p><h1>결제할 주문이 없어요</h1>
          <p>주문서를 다시 확인해 주세요.</p>
          <button type="button" className="flow-primary" onClick={onBack}>주문서로 돌아가기</button>
        </section>
      </main>
    )
  }

  return (
    <main className="order-flow payment">
      <button type="button" className="flow-back" onClick={onBack}>← 주문서로 돌아가기 · 상태 유지</button>
      <header className="flow-heading">
        <h1>결제 수단을 확인해 주세요</h1>
        <p>최종 금액은 서버에서 검증한 뒤 Toss 결제창에 전달됩니다.</p>
      </header>
      <div className="payment-layout">
        <div className="payment-main">
          <section className="payment-methods" aria-label="결제 수단">
            <h2>결제 수단</h2>
            <label className="payment-method selected">
              <input type="radio" name="payment-method" checked readOnly />
              <span><strong>신용 / 체크카드</strong><small>Toss Payments</small></span>
            </label>
          </section>
          <OrderTotals totals={totals} />
          {error && <div className="payment-error" role="alert">{error}</div>}
          <button type="button" className="flow-primary payment-submit" onClick={pay} disabled={busy}>
            {busy ? '결제 준비 중...' : '결제하기'}
          </button>
        </div>
        <aside className="payment-items">
          <h2>주문 상품</h2>
          <div className="payment-item-scroll">
            {orderItems.map(item => <OrderItemCard key={item.skuId} item={item} compact />)}
          </div>
        </aside>
      </div>
      <div className="mobile-flow-cta">
        <strong>미리보기 금액 ₩{totals.grandTotal.toLocaleString('ko-KR')}</strong>
        <button type="button" className="flow-primary" onClick={pay} disabled={busy}>
          {busy ? '결제 준비 중...' : '결제하기'}
        </button>
      </div>
    </main>
  )
}
