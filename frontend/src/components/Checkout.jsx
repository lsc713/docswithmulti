import { aggregateOrderItems } from '../orderFlow'

const money = value => `₩${value.toLocaleString('ko-KR')}`

export function OrderItemCard({ item, compact = false }) {
  return (
    <article className={`order-item-card${compact ? ' compact' : ''}`}>
      <div className="order-item-image">
        {item.imageUrl ? <img src={item.imageUrl} alt="" /> : <span aria-hidden="true" />}
      </div>
      <div className="order-item-copy">
        <h2>{item.itemName}</h2>
        <p className="order-item-option">{item.optionSummary}{item.skuCode ? ` · ${item.skuCode}` : ''}</p>
        <p className="order-item-quantity">{compact ? `${item.quantity}개` : `수량 ${item.quantity} · 단가 ${money(item.unitPrice)}`}</p>
        {!compact && <p className="item-state">주문 정보 미리보기</p>}
      </div>
      <strong className="order-item-total">{money(item.unitPrice * item.quantity)}</strong>
    </article>
  )
}

export function OrderTotals({ totals }) {
  return (
    <section className="order-totals" aria-label="주문 금액 미리보기">
      <h2>주문 금액 미리보기</h2>
      <dl>
        <div><dt>상품 금액</dt><dd>{money(totals.subtotal)}</dd></div>
        <div><dt>배송비</dt><dd>{money(totals.shipping)}</dd></div>
        <div><dt>할인</dt><dd>적용 안 됨</dd></div>
      </dl>
      <div className="grand-total"><span>미리보기 합계</span><strong data-testid="grand-total">{money(totals.grandTotal)}</strong></div>
    </section>
  )
}

function EmptyState({ onBack }) {
  return (
    <main className="order-flow state">
      <section className="flow-state-card" role="status">
        <p className="flow-eyebrow">CHECKOUT</p>
        <h1>주문할 상품이 없어요</h1><p>상품 또는 장바구니에서 주문할 상품을 선택해 주세요.</p>
        <button type="button" className="flow-secondary" onClick={onBack}>상품 둘러보기</button>
      </section>
    </main>
  )
}

export default function Checkout({ flowState, me, onBack }) {
  if (!flowState?.orderItems?.length) return <EmptyState onBack={onBack} />

  const orderItems = flowState.orderItems
  const totals = aggregateOrderItems(orderItems)
  return (
    <main className="order-flow checkout">
      <header className="flow-heading">
        <p className="flow-eyebrow">주문서 확인 · {totals.itemCount}개 상품</p>
        <h1>주문할 상품을 확인해 주세요</h1>
        <p>상품과 금액은 저장된 주문 미리보기이며 현재 서버 검증 결과가 아닙니다.</p>
      </header>
      <div className="checkout-layout">
        <div className="checkout-main">
          <section className="order-item-list" aria-label="주문 상품">
            {orderItems.map(item => <OrderItemCard key={item.skuId} item={item} />)}
          </section>
          <section className="orderer-card">
            <h2>주문자</h2>
            <p>{me?.name ?? '로그인 사용자'}{me?.email ? ` · ${me.email}` : ''}</p>
            <p className="order-note">배송 정보는 현재 주문 계약에 포함되지 않아 별도 금액을 적용하지 않습니다.</p>
          </section>
        </div>
        <aside className="checkout-summary">
          <OrderTotals totals={totals} />
          <p id="checkout-blocked" className="flow-blocked" role="alert">서버 재검증 미지원으로 결제 불가</p>
          <button type="button" className="flow-primary" disabled aria-describedby="checkout-blocked">결제 연동 준비 중</button>
          <button type="button" className="flow-secondary" onClick={onBack}>상품 또는 장바구니로 돌아가기</button>
        </aside>
      </div>
      <div className="mobile-flow-cta">
        <strong>미리보기 합계 {money(totals.grandTotal)}</strong>
        <button type="button" className="flow-primary" disabled aria-describedby="checkout-blocked">결제 연동 준비 중</button>
      </div>
    </main>
  )
}
