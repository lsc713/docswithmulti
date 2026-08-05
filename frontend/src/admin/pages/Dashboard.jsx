import { useEffect, useState } from 'react'
import { api } from '../../api'

function leaves(tree) {
  const out = []
  ;(function walk(ns) { ns.forEach(n => (n.children?.length ? walk(n.children) : out.push(n))) })(tree)
  return out
}

export default function Dashboard() {
  const [stats, setStats] = useState({ users: '—', categories: '—', products: '—' })
  const [pending, setPending] = useState('—')

  useEffect(() => {
    (async () => {
      const [users, tree] = await Promise.all([api.adminUsers(0, 1), api.categories()])
      const ls = leaves(tree)
      const counts = await Promise.all(ls.map(l => api.productsByCategory(l.id).then(r => r.totalElements)))
      const catCount = (function count(ns) { return ns.reduce((a, n) => a + 1 + count(n.children ?? []), 0) })(tree)
      setStats({
        users: users.totalElements,
        categories: catCount,
        products: counts.reduce((a, b) => a + b, 0),
      })
    })().catch(() => { /* 카드에 — 유지 */ })
    api.cancelRequests('REQUESTED')
      .then(r => setPending((r.items ?? []).length))
      .catch(() => setPending('—'))
  }, [])

  return (
    <>
      <h1>대시보드</h1>
      <div className="admin-cards">
        <div className="admin-card"><div className="num">{stats.users}</div><div className="label">총 회원 수</div></div>
        <div className="admin-card"><div className="num">{stats.categories}</div><div className="label">카테고리 수</div></div>
        <div className="admin-card"><div className="num">{stats.products}</div><div className="label">상품 총수</div></div>
        <div className="admin-card"><div className="num">{pending}</div><div className="label">대기 중 취소요청</div></div>
      </div>
    </>
  )
}
