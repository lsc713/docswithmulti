export default function Cart({ items, onQty, onRemove, onOrder, onBack }) {
  const total = items.reduce((s, it) => s + it.unitPrice * it.quantity, 0)
  const lines = items.map(it => ({
    skuId: it.skuId, productId: it.productId, itemName: it.itemName,
    optionSummary: it.optionSummary, unitPrice: it.unitPrice, quantity: it.quantity,
  }))
  return (
    <main className="cart">
      <button onClick={onBack}>뒤로</button>
      <h1>장바구니</h1>
      {items.length === 0 ? <p>장바구니가 비어 있습니다.</p> : (
        <>
          <table className="cart-table">
            <thead><tr><th>상품</th><th>옵션</th><th>단가</th><th>수량</th><th>합계</th><th></th></tr></thead>
            <tbody>
              {items.map(it => (
                <tr key={it.skuId}>
                  <td>{it.itemName}</td><td>{it.optionSummary}</td>
                  <td>₩{it.unitPrice.toLocaleString()}</td>
                  <td>
                    <input className="qty-input" type="number" min="1" step="1" value={it.quantity}
                           onChange={e => onQty(it.skuId, Math.max(1, Math.floor(Number(e.target.value) || 1)))} />
                  </td>
                  <td>₩{(it.unitPrice * it.quantity).toLocaleString()}</td>
                  <td><button onClick={() => onRemove(it.skuId)}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="cart-total">합계 <strong>₩{total.toLocaleString()}</strong></p>
          <button className="pay-btn" onClick={() => onOrder(lines)}>주문하기</button>
        </>
      )}
    </main>
  )
}
