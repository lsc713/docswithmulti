import { useEffect, useState } from 'react'
import { api } from '../../api'
import { settlementStatusLabel } from '../management'

const won = value => `₩${Number(value ?? 0).toLocaleString()}`

export default function Settlements() {
  const [merchantId, setMerchantId] = useState('1')
  const [status, setStatus] = useState('')
  const [items, setItems] = useState([])
  const [selected, setSelected] = useState(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(false)
  const [paying, setPaying] = useState(false)

  async function load(event) {
    event?.preventDefault()
    setLoading(true)
    try { setItems(await api.adminSettlements(merchantId, status)); setSelected(null); setErr('') }
    catch (e) { setErr(e.message) }
    finally { setLoading(false) }
  }
  useEffect(() => {
    setLoading(true)
    api.adminSettlements(1)
      .then(setItems)
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false))
  }, [])

  async function open(id) {
    try { setSelected(await api.adminSettlement(id)); setErr('') }
    catch (e) { setErr(e.message) }
  }

  async function approvePayout() {
    if (!window.confirm('이 정산의 지급을 승인할까요?')) return
    setPaying(true)
    try { await api.approvePayout(selected.settlement.id); await load(); setErr('') }
    catch (e) { setErr(e.message) }
    finally { setPaying(false) }
  }

  return (
    <section className="admin-page">
      <header className="admin-page-head"><div><p className="eyebrow">SETTLEMENT LEDGER</p><h1>정산관리</h1></div></header>
      <form className="admin-toolbar" onSubmit={load}>
        <label>가맹점 ID<input type="number" min="1" value={merchantId} onChange={e => setMerchantId(e.target.value)} required /></label>
        <label>상태<select value={status} onChange={e => setStatus(e.target.value)}><option value="">전체</option><option value="OPEN">집계 중</option><option value="FINALIZED">정산 확정</option></select></label>
        <button className="primary" disabled={loading}>{loading ? '조회 중...' : '조회'}</button>
      </form>
      {err && <p className="error">{err}</p>}
      {!loading && items.length === 0 ? <div className="admin-empty">조회된 정산 내역이 없습니다.</div> : (
        <div className="admin-table-wrap"><table className="admin-table operational-table">
          <thead><tr><th>기간</th><th>매출</th><th>취소</th><th>수수료·VAT</th><th>정산액</th><th>상태</th></tr></thead>
          <tbody>{items.map(item => (
            <tr key={item.id} className={selected?.settlement.id === item.id ? 'selected' : ''}>
              <td><button className="table-link" onClick={() => open(item.id)}>{item.periodStart}<br/><span className="muted">~ {item.periodEnd}</span></button></td>
              <td className="money">{won(item.grossAmount)}</td><td className="money negative">-{won(item.cancelAmount)}</td>
              <td className="money">{won(Number(item.feeAmount) + Number(item.vatAmount))}</td><td className="money strong">{won(item.netAmount)}</td>
              <td><span className={`ops-badge ${item.status.toLowerCase()}`}>{settlementStatusLabel(item.status)}</span></td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {selected && (
        <aside className="admin-detail">
          <div className="detail-title"><div><p className="eyebrow">LEDGER #{selected.settlement.id}</p><h2>정산 상세</h2></div><button onClick={() => setSelected(null)}>닫기</button></div>
          <div className="detail-summary"><span>{selected.settlement.periodStart} — {selected.settlement.periodEnd}</span><strong>{won(selected.settlement.netAmount)}</strong></div>
          <ul className="ledger-lines">{selected.lines.map(line => (
            <li key={line.id}><div><span className={`line-type ${line.type.toLowerCase()}`}>{line.type}</span><strong className="mono">{line.paymentKey}</strong><small>{new Date(line.occurredAt).toLocaleString()}</small></div><span className={line.type === 'CANCEL' ? 'negative' : ''}>{line.type === 'CANCEL' ? '-' : '+'}{won(line.amount)}</span></li>
          ))}</ul>
          {selected.settlement.status === 'FINALIZED' && <button className="primary payout-btn" disabled={paying} onClick={approvePayout}>{paying ? '승인 중...' : '지급 승인'}</button>}
        </aside>
      )}
    </section>
  )
}
