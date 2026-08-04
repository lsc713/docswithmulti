export default function OrderSuccess({ payment, onHome }) {
  return (
    <main className="order-success">
      <h1>결제 완료 🎉</h1>
      <p className="success-key">주문번호(paymentKey): <code>{payment.paymentKey}</code></p>
      <p>결제금액: <strong>₩{Number(payment.totalAmount).toLocaleString()}</strong></p>
      <p>상태: {payment.status}</p>
      <ul className="success-items">
        {payment.items?.map(it => (
          <li key={it.paymentItemId}>{it.itemName} — ₩{Number(it.itemAmount).toLocaleString()}</li>
        ))}
      </ul>
      <button onClick={onHome}>쇼핑 계속하기</button>
    </main>
  )
}
