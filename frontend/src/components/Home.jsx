import { useEffect, useState } from 'react'
import { api } from '../api'
import ProductGrid from './ProductGrid'

export default function Home({ onOpen }) {
  const [leaves, setLeaves] = useState([])
  const [active, setActive] = useState(null)
  const [items, setItems] = useState([])

  useEffect(() => {
    api.categories().then(tree => {
      const ls = []
      ;(function walk(nodes) {
        nodes.forEach(n => (n.children?.length ? walk(n.children) : ls.push(n)))
      })(tree)
      setLeaves(ls)
      if (ls[0]) setActive(ls[0].id)
    }).catch(() => setLeaves([]))
  }, [])

  useEffect(() => {
    if (active == null) return
    api.productsByCategory(active).then(r => setItems(r.content)).catch(() => setItems([]))
  }, [active])

  return (
    <main>
      <nav className="cat-tabs">
        {leaves.map(l => (
          <button key={l.id} className={l.id === active ? 'active' : ''} onClick={() => setActive(l.id)}>
            {l.name}
          </button>
        ))}
      </nav>
      <ProductGrid items={items} onOpen={onOpen} />
    </main>
  )
}
