import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import ImageManager from './ImageManager'

export default function ProductDetail({ id, me, onBack, onBuy }) {
  const [product, setProduct] = useState(null)
  const [error, setError] = useState(null)
  const [qty, setQty] = useState({}) // skuId -> quantity

  const load = useCallback(() => {
    api.product(id).then(setProduct).catch(e => setError(e.message))
  }, [id])

  useEffect(() => { setProduct(null); setError(null); setQty({}); load() }, [id])

  if (error) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p className="error">{error}</p></main>
  if (!product) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p>불러오는 중...</p></main>

  const categoryPath = product.category?.map(c => c.name).join(' > ')
  const setSkuQty = (skuId, max) => (e) => {
    const v = Math.max(0, Math.min(max, Number(e.target.value) || 0))
    setQty(q => ({ ...q, [skuId]: v }))
  }
  const lines = (product.skus ?? [])
    .filter(s => (qty[s.skuId] ?? 0) > 0)
    .map(s => ({ skuId: s.skuId, productId: product.id, itemName: product.name,
                 optionSummary: s.optionSummary, unitPrice: s.price, quantity: qty[s.skuId] }))

  return (
    <main className="product-detail">
      <button onClick={onBack}>뒤로</button>
      <div className="gallery">
        {product.images?.length
          ? product.images.map((img, i) => <img key={img.id} src={img.url} alt={`${product.name} ${i + 1}`} />)
          : <div className="gallery-ph">이미지 없음</div>}
      </div>
      {categoryPath && <p className="category-path">{categoryPath}</p>}
      <h1>{product.name}</h1>

      <table className="sku-table">
        <thead><tr><th>SKU</th><th>옵션</th><th>가격</th><th>재고</th><th>수량</th></tr></thead>
        <tbody>
          {product.skus?.map(s => (
            <tr key={s.skuId}>
              <td>{s.skuCode}</td>
              <td>{s.optionSummary}</td>
              <td>₩{s.price.toLocaleString()}</td>
              <td>{s.availableQty}</td>
              <td>
                <input className="qty-input" type="number" min="0" max={s.availableQty}
                       value={qty[s.skuId] ?? 0} onChange={setSkuQty(s.skuId, s.availableQty)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <button className="buy-btn" disabled={lines.length === 0} onClick={() => onBuy(lines)}>구매하기</button>

      {me?.role === 'ADMIN' && (
        <ImageManager productId={id} images={product.images} onChanged={load} />
      )}
    </main>
  )
}
