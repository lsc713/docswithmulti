import { useEffect, useState } from 'react'
import { api } from '../../api'

export default function CancelRequests() {
  const [items, setItems] = useState([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)

  function load() {
    setLoading(true)
    api.cancelRequests('REQUESTED')
      .then(r => { setItems(r.items ?? []); setErr('') })
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(load, [])

  async function approve(id) {
    try { await api.approveCancel(id); load() } catch (e) { setErr(e.message) }
  }
  async function reject(id) {
    const reason = window.prompt('반려 사유를 입력하세요')
    if (!reason) return
    try { await api.rejectCancel(id, reason); load() } catch (e) { setErr(e.message) }
  }

  return (
    <>
      <h1>취소 요청</h1>
      {err && <p className="error">{err}</p>}
      {loading ? (
        <p>불러오는 중...</p>
      ) : items.length === 0 ? (
        <p>대기 중인 취소 요청이 없습니다.</p>
      ) : (
        <table className="admin-table">
          <thead><tr><th>결제키</th><th>요청자</th><th>사유</th><th>요청시각</th><th>액션</th></tr></thead>
          <tbody>
            {items.map(it => (
              <tr key={it.id}>
                <td>{it.paymentKey}</td>
                <td>{it.requesterUserId}</td>
                <td>{it.reason}</td>
                <td>{new Date(it.createdAt).toLocaleString()}</td>
                <td>
                  <button onClick={() => approve(it.id)}>승인</button>
                  <button onClick={() => reject(it.id)}>반려</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
