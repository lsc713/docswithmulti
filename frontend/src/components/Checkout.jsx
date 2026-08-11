import { aggregateOrderItems, getOrderItemState } from '../orderFlow'

const money = value => `₩${value.toLocaleString('ko-KR')}`

export function OrderItemCard({ item, compact = false }) {
  const state = getOrderItemState(item)
  return (
    <article className={`order-item-card${compact ? ' compact' : ''}${state.blocked ? ' changed' : ''}`}>
      <div className="order-item-image">
        {item.imageUrl ? <img src={item.imageUrl} alt="" /> : <span aria-hidden="true" />}
      </div>
      <div className="order-item-copy">
        <h2>{item.itemName}</h2>
        <p className="order-item-option">{item.optionSummary}{item.skuCode ? ` · ${item.skuCode}` : ''}</p>
        <p className="order-item-quantity">{compact ? `${item.quantity}개` : `수량 ${item.quantity} · 단가 ${money(item.unitPrice)}`}</p>
        {!compact && (state.reasons.length
          ? state.reasons.map(reason => <p className="item-state error" key={reason}>{reason}</p>)
          : <p className="item-state available">구매 가능</p>)}
      </div>
      <strong className="order-item-total">{money(item.unitPrice * item.quantity)}</strong>
    </article>
  )
}

export function OrderTotals({ totals }) {
  return (
    <section className="order-totals" aria-label="결제 금액">
      <h2>결제 금액</h2>
      <dl>
        <div><dt>상품 금액</dt><dd>{money(totals.subtotal)}</dd></div>
        <div><dt>배송비</dt><dd>{money(totals.shipping)}</dd></div>
        <div><dt>할인</dt><dd>적용 안 됨</dd></div>
      </dl>
      <div className="grand-total"><span>최종 결제 금액</span><strong data-testid="grand-total">{money(totals.grandTotal)}</strong></div>
    </section>
  )
}

function FlowState({ kind, onBack, onRetry }) {
  const content = {
    loading: ['주문 정보를 확인하고 있어요', '재고와 가격 정보를 불러오는 중입니다.'],
    error: ['주문 정보를 불러오지 못했어요', '현재 화면을 유지한 채 다시 시도할 수 있습니다.'],
    empty: ['주문할 상품이 없어요', '상품 또는 장바구니에서 주문할 상품을 선택해 주세요.'],
  }[kind]
  return (
    <main className="order-flow state" aria-busy={kind === 'loading'}>
      <section className="flow-state-card" role={kind === 'error' ? 'alert' : 'status'}>
        <p className="flow-eyebrow">CHECKOUT</p>
        <h1>{content[0]}</h1><p>{content[1]}</p>
        {kind === 'error' && <button type="button" className="flow-primary" onClick={onRetry}>다시 시도</button>}
        {kind !== 'loading' && <button type="button" className="flow-secondary" onClick={onBack}>상품 둘러보기</button>}
      </section>
    </main>
  )
}

export default function Checkout({ flowState, me, onContinue, onBack, onRetry }) {
  if (flowState?.status === 'loading') return <FlowState kind="loading" onBack={onBack} onRetry={onRetry} />
  if (flowState?.status === 'error') return <FlowState kind="error" onBack={onBack} onRetry={onRetry} />
  if (!flowState?.orderItems?.length) return <FlowState kind="empty" onBack={onBack} onRetry={onRetry} />

  const orderItems = flowState.orderItems
  const totals = aggregateOrderItems(orderItems)
  const changed = orderItems.some(item => getOrderItemState(item).blocked)
  return (
    <main className="order-flow checkout">
      <header className="flow-heading">
        <p className="flow-eyebrow">주문서 확인 · {totals.itemCount}개 상품</p>
        <h1>주문할 상품을 확인해 주세요</h1>
        <p>상품 목록이 주문서의 중심입니다. 재고와 가격은 결제 전 다시 확인됩니다.</p>
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
          {changed && <p className="flow-blocked" role="alert">재고/가격이 변경된 상품을 확인해 주세요.</p>}
          <button type="button" className="flow-primary" disabled={changed} onClick={onContinue}>변경 사항 확인 후 결제하기</button>
          <button type="button" className="flow-secondary" onClick={onBack}>상품 또는 장바구니로 돌아가기</button>
        </aside>
      </div>
      <div className="mobile-flow-cta">
        <strong>최종 금액 {money(totals.grandTotal)}</strong>
        <button type="button" className="flow-primary" disabled={changed} onClick={onContinue}>변경 사항 확인 후 결제하기</button>
      </div>
    </main>
  )
}
