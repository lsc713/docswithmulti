import { useEffect, useRef } from 'react'

const won = value => `₩${value.toLocaleString()}`

export default function Cart({ items, status, onQty, onRemove, onOrder, onBack }) {
  const quantityDrafts = useRef(new Map())
  const total = items.reduce((s, it) => s + it.unitPrice * it.quantity, 0)
  const lines = items.map(it => ({
    skuId: it.skuId, productId: it.productId, itemName: it.itemName,
    optionSummary: it.optionSummary, unitPrice: it.unitPrice, quantity: it.quantity,
  }))

  useEffect(() => {
    quantityDrafts.current = new Map(items.map(item => [item.skuId, item.quantity]))
  }, [items])

  function updateQuantity(item, quantity) {
    const next = Math.max(1, Math.floor(Number(quantity) || 1))
    quantityDrafts.current.set(item.skuId, next)
    onQty(item.skuId, next)
  }

  function stepQuantity(item, delta) {
    const current = quantityDrafts.current.get(item.skuId) ?? item.quantity
    updateQuantity(item, current + delta)
  }

  return (
    <main className="cart" aria-labelledby="cart-title">
      <header className="cart-heading">
        <p className="cart-eyebrow">CART · {items.length} ITEMS</p>
        <h1 id="cart-title">장바구니</h1>
        {items.length > 0 && <p>상품 {items.length}종 · 총 수량 {items.reduce((sum, item) => sum + item.quantity, 0)}개</p>}
      </header>
      {status === 'loading' ? (
        <section className="cart-state" role="status" aria-live="polite">
          <h2>장바구니를 불러오는 중입니다.</h2>
          <p>잠시만 기다려 주세요.</p>
        </section>
      ) : status === 'error' ? (
        <section className="cart-state cart-error" role="alert">
          <h2>장바구니를 불러오지 못했어요.</h2>
          <p>잠시 후 다시 방문해 주세요.</p>
          <button type="button" onClick={onBack}>쇼핑 계속하기</button>
        </section>
      ) : items.length === 0 ? (
        <section className="cart-state">
          <h2>장바구니가 비어 있습니다.</h2>
          <p>마음에 드는 상품을 담아 보세요.</p>
          <button type="button" onClick={onBack}>쇼핑 계속하기</button>
        </section>
      ) : (
        <div className="cart-layout">
          <table className="cart-table">
            <thead className="sr-only"><tr><th>상품</th><th>옵션</th><th>단가</th><th>수량</th><th>합계</th><th>삭제</th></tr></thead>
            <tbody>
              {items.map(it => (
                <tr key={it.skuId}>
                  <td className="cart-product">
                    <div className="cart-image" aria-hidden="true"><strong>상품 이미지</strong><small>현재 Cart UI 미제공</small></div>
                    <div className="cart-product-copy">
                      <strong>{it.itemName}</strong>
                      <span aria-hidden="true">{it.optionSummary}</span>
                      <span aria-hidden="true">단가 {won(it.unitPrice)}</span>
                      <small>skuId 기준 장바구니 항목</small>
                    </div>
                  </td>
                  <td className="cart-option sr-only" aria-label={`옵션 ${it.optionSummary}`}>{it.optionSummary}</td>
                  <td className="cart-unit sr-only" aria-label={`단가 ${won(it.unitPrice)}`}>{won(it.unitPrice)}</td>
                  <td className="cart-quantity">
                    <div className="quantity-stepper">
                      <button type="button" aria-label={`${it.itemName} ${it.optionSummary} 수량 줄이기`} disabled={it.quantity === 1}
                              onClick={() => stepQuantity(it, -1)}>−</button>
                      <input className="qty-input" type="number" min="1" step="1" value={it.quantity}
                             aria-label={`${it.itemName} ${it.optionSummary} 수량`} aria-live="polite"
                             onChange={event => updateQuantity(it, event.target.value)} />
                      <button type="button" aria-label={`${it.itemName} ${it.optionSummary} 수량 늘리기`}
                              onClick={() => stepQuantity(it, 1)}>＋</button>
                    </div>
                  </td>
                  <td className="cart-line-total" aria-live="polite">품목 합계 <strong>{won(it.unitPrice * it.quantity)}</strong></td>
                  <td className="cart-delete"><button type="button" aria-label={`${it.itemName} 삭제`} onClick={() => onRemove(it.skuId)}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <aside className="cart-summary" aria-labelledby="cart-summary-title">
            <h2 id="cart-summary-title">주문 요약</h2>
            <p>상품 {items.length}종 · 총 수량 {items.reduce((sum, item) => sum + item.quantity, 0)}개</p>
            <dl>
              <div><dt>상품 금액</dt><dd>{won(total)}</dd></div>
              <div><dt>배송비</dt><dd>₩0</dd></div>
            </dl>
            <p className="cart-total" aria-live="polite">합계 <strong>{won(total)}</strong></p>
            <button className="cart-checkout" type="button" onClick={() => onOrder(lines)}>전체 상품 주문하기</button>
            <button className="cart-continue" type="button" onClick={onBack}>쇼핑 계속하기</button>
          </aside>
        </div>
      )}
    </main>
  )
}
