import { useState } from 'react'

const STATUS_KO = { COMPLETED: '결제완료', CANCELLED: '취소됨', PARTIAL_CANCELLED: '부분취소' }

export default function OrderHistory({ payments, onCancel, onBack }) {
  const [busyKey, setBusyKey] = useState(null)
  async function cancel(p) {
    const reason = window.prompt('취소 사유를 입력하세요', '단순 변심')
    if (reason == null) return
    setBusyKey(p.paymentKey)
    try { await onCancel(p.paymentKey, p.items.map(it => ({ paymentItemId: it.paymentItemId })), reason) }
    finally { setBusyKey(null) }
  }
  return (
    <main className="history">
      <button onClick={onBack}>뒤로</button>
      <h1>주문내역</h1>
      {payments.length === 0 ? <p>구매 내역이 없습니다.</p> : (
        <ul className="history-list">
          {payments.map(p => (
            <li key={p.paymentKey} className="history-item">
              <div className="history-head">
                <span className="history-date">{new Date(p.createdAt).toLocaleString()}</span>
                <span className={`badge ${p.status}`}>{STATUS_KO[p.status] ?? p.status}</span>
              </div>
              <div className="history-key">{p.paymentKey}</div>
              <ul className="history-items">
                {p.items.map(it => <li key={it.paymentItemId}>{it.itemName} — ₩{Number(it.itemAmount).toLocaleString()}</li>)}
              </ul>
              <div className="history-foot">
                <strong>₩{Number(p.totalAmount).toLocaleString()}</strong>
                {p.status === 'COMPLETED' && (
                  <button disabled={busyKey === p.paymentKey} onClick={() => cancel(p)}>
                    {busyKey === p.paymentKey ? '취소 중...' : '취소하기'}
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
