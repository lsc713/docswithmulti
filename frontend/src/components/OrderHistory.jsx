import { useState } from 'react'

const STATUS_KO = { COMPLETED: '결제완료', CANCELLED: '취소됨', PARTIAL_CANCELLED: '부분취소' }
const CRS_KO = { REQUESTED: '취소 요청됨', REJECTED: '취소 반려됨' }

export default function OrderHistory({ payments, onRequestCancel, onBack }) {
  const [busyKey, setBusyKey] = useState(null)
  async function request(p, label) {
    const reason = window.prompt(`${label} 사유를 입력하세요`, '단순 변심')
    if (reason == null) return
    setBusyKey(p.paymentKey)
    try { await onRequestCancel(p.paymentKey, reason) }
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
                {p.status === 'COMPLETED' && p.cancelRequestStatus == null && (
                  <button disabled={busyKey === p.paymentKey} onClick={() => request(p, '취소 요청')}>
                    {busyKey === p.paymentKey ? '요청 중...' : '취소 요청'}
                  </button>
                )}
                {p.status === 'COMPLETED' && p.cancelRequestStatus === 'REQUESTED' && (
                  <span className="crs-badge requested">{CRS_KO.REQUESTED}</span>
                )}
                {p.status === 'COMPLETED' && p.cancelRequestStatus === 'REJECTED' && (
                  <>
                    <span className="crs-badge rejected">{CRS_KO.REJECTED}</span>
                    <button disabled={busyKey === p.paymentKey} onClick={() => request(p, '다시 요청')}>
                      {busyKey === p.paymentKey ? '요청 중...' : '다시 요청'}
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
