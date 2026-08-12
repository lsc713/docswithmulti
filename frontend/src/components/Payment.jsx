import { aggregateOrderItems } from '../orderFlow'
import { OrderItemCard, OrderTotals } from './Checkout'

export default function Payment({ flowState, onBack }) {
  const orderItems = flowState?.orderItems ?? []
  const totals = aggregateOrderItems(orderItems)

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
        <p>상품과 금액은 저장된 미리보기이며 결제 요청은 전송되지 않습니다.</p>
      </header>
      <div className="payment-layout">
        <div className="payment-main">
          <section className="payment-methods" aria-label="결제 수단">
            <h2>예정 결제 수단</h2>
            <label className="payment-method selected">
              <input type="radio" name="payment-method" checked readOnly disabled />
              <span><strong>신용 / 체크카드</strong><small>결제 연동 준비 중</small></span>
            </label>
          </section>
          <OrderTotals totals={totals} />
          <p id="payment-blocked" className="flow-blocked" role="alert">서버 재검증 미지원으로 결제 불가 · 결제 연동 준비 중</p>
          <button type="button" className="flow-primary payment-submit" disabled aria-describedby="payment-blocked">결제 연동 준비 중</button>
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
        <button type="button" className="flow-primary" disabled aria-describedby="payment-blocked">결제 연동 준비 중</button>
      </div>
    </main>
  )
}
