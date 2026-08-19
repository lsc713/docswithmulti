import { useEffect, useState } from 'react'
import { api } from '../../api'
import { orderStatusLabel } from '../management'

const won = value => `₩${Number(value ?? 0).toLocaleString()}`

export default function Orders() {
  const [orders, setOrders] = useState([])
  const [selected, setSelected] = useState(null)
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.adminOrders()
      .then(setOrders)
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false))
  }, [])

  async function open(id) {
    try { setSelected(await api.adminOrder(id)); setErr('') }
    catch (e) { setErr(e.message) }
  }

  return (
    <section className="admin-page">
      <header className="admin-page-head">
        <div><p className="eyebrow">ORDER DESK</p><h1>주문관리</h1></div>
        <span className="record-count">{orders.length}건</span>
      </header>
      {err && <p className="error">{err}</p>}
      {loading ? <p>주문을 불러오는 중...</p> : orders.length === 0 ? (
        <div className="admin-empty">아직 생성된 주문이 없습니다.</div>
      ) : (
        <div className="admin-table-wrap">
          <table className="admin-table operational-table">
            <thead><tr><th>주문번호</th><th>회원 ID</th><th>상품</th><th>결제금액</th><th>상태</th></tr></thead>
            <tbody>{orders.map(order => (
              <tr key={order.id} className={selected?.id === order.id ? 'selected' : ''}>
                <td><button className="table-link mono" onClick={() => open(order.id)}>#{order.id}</button></td><td>{order.userId}</td>
                <td>{order.items.length}개</td><td className="money">{won(order.totalAmount)}</td>
                <td><span className={`ops-badge ${order.status.toLowerCase()}`}>{orderStatusLabel(order.status)}</span></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      {selected && (
        <aside className="admin-detail">
          <div className="detail-title"><div><p className="eyebrow">ORDER #{selected.id}</p><h2>주문 상세</h2></div><button onClick={() => setSelected(null)}>닫기</button></div>
          <div className="detail-summary"><span>회원 {selected.userId}</span><strong>{won(selected.totalAmount)}</strong></div>
          <ul className="ledger-lines">{selected.items.map(item => (
            <li key={item.id}><div><span className="mono">#{item.id}</span><strong>{item.itemName}</strong></div><span>{won(item.price)}</span></li>
          ))}</ul>
        </aside>
      )}
    </section>
  )
}
