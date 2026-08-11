import { useRef, useState } from 'react'
import { api } from '../api'
import { aggregateOrderItems, getOrderItemState, submitOrderPayment } from '../orderFlow'
import { OrderItemCard, OrderTotals } from './Checkout'

const money = value => `₩${value.toLocaleString('ko-KR')}`

export default function Payment({ flowState, onOrderCreated, onPaid, onBack }) {
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const submitLock = useRef(false)
  const createdOrder = useRef(flowState?.createdOrderItems ?? null)
  const orderItems = flowState?.orderItems ?? []
  const totals = aggregateOrderItems(orderItems)
  const changed = orderItems.some(item => getOrderItemState(item).blocked)

  async function submit() {
    if (submitLock.current || changed || orderItems.length === 0) return
    submitLock.current = true
    setSubmitting(true)
    setError('')
    try {
      const payment = await submitOrderPayment(orderItems, api, createdOrder, onOrderCreated)
      onPaid(payment)
    } catch (requestError) {
      setError(requestError.message)
      submitLock.current = false
      setSubmitting(false)
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
    <main className="order-flow payment" aria-busy={submitting}>
      <button type="button" className="flow-back" onClick={onBack}>← 주문서로 돌아가기 · 상태 유지</button>
      <header className="flow-heading">
        <h1>결제 수단을 선택해 주세요</h1>
        <p>결제 요청 중에는 버튼을 잠그고 동일 요청의 중복 제출을 막습니다.</p>
      </header>
      <div className="payment-layout">
        <div className="payment-main">
          <section className="payment-methods" aria-label="결제 수단">
            <h2>결제 수단</h2>
            <label className="payment-method selected">
              <input type="radio" name="payment-method" checked readOnly />
              <span><strong>신용 / 체크카드</strong><small>현재 결제 계약의 TOSS 방식</small></span>
            </label>
          </section>
          <OrderTotals totals={totals} />
          {changed && <p className="flow-blocked" role="alert">재고/가격 변경으로 결제를 진행할 수 없습니다. 주문서에서 확인해 주세요.</p>}
          {error && <div className="payment-error" role="alert"><strong>결제 요청에 실패했어요</strong><p>{error}</p><p>주문 정보와 선택 상태는 유지됩니다. 다시 시도해 주세요.</p></div>}
          <button type="button" className="flow-primary payment-submit" disabled={submitting || changed}
                  onClick={submit}>{submitting ? '결제 요청 중…' : `${money(totals.grandTotal)} 결제하기`}</button>
        </div>
        <aside className="payment-items">
          <h2>주문 상품</h2>
          <div className="payment-item-scroll">
            {orderItems.map(item => <OrderItemCard key={item.skuId} item={item} compact />)}
          </div>
        </aside>
      </div>
      <div className="mobile-flow-cta">
        <strong>최종 금액 {money(totals.grandTotal)}</strong>
        <button type="button" className="flow-primary" disabled={submitting || changed}
                onClick={submit}>{submitting ? '결제 요청 중…' : `${money(totals.grandTotal)} 결제하기`}</button>
      </div>
    </main>
  )
}
