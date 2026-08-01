export default function ProductGrid({ items, onOpen }) {
  return (
    <div className="grid">
      {items.map(p => (
        <button key={p.id} className="card" onClick={() => onOpen(p.id)}>
          {p.thumbnailUrl ? <img src={p.thumbnailUrl} alt={p.name} /> : <div className="ph" />}
          <div className="name">{p.name}</div>
          <div className="price">₩{p.minPrice.toLocaleString()}~</div>
        </button>
      ))}
    </div>
  )
}
