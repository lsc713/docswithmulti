import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api'

function leaves(tree) {
  const out = []
  ;(function walk(ns) { ns.forEach(n => (n.children?.length ? walk(n.children) : out.push(n))) })(tree)
  return out
}

export default function ProductList() {
  const [cats, setCats] = useState([])
  const [active, setActive] = useState(null)
  const [items, setItems] = useState([])

  useEffect(() => {
    api.categories().then(tree => {
      const ls = leaves(tree)
      setCats(ls)
      if (ls[0]) setActive(ls[0].id)
    }).catch(() => setCats([]))
  }, [])

  useEffect(() => {
    if (active == null) return
    api.productsByCategory(active).then(r => setItems(r.content)).catch(() => setItems([]))
  }, [active])

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>상품관리</h1>
        <Link className="primary" to="/admin/products/new" style={{ textDecoration: 'none' }}>새 상품 등록</Link>
      </div>
      <nav style={{ display: 'flex', gap: 8, margin: '12px 0' }}>
        {cats.map(c => (
          <button key={c.id} className={c.id === active ? 'primary' : ''} onClick={() => setActive(c.id)}>{c.name}</button>
        ))}
      </nav>
      <div className="admin-grid">
        {items.map(p => (
          <Link key={p.id} className="card" to={`/admin/products/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
            {p.thumbnailUrl ? <img src={p.thumbnailUrl} alt={p.name} /> : <div className="ph" />}
            <div>{p.name}</div>
            <div>₩{p.minPrice.toLocaleString()}~</div>
          </Link>
        ))}
      </div>
    </>
  )
}
