import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import ImageManager from './ImageManager'

export default function ProductDetail({ id, me, onBack }) {
  const [product, setProduct] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(() => {
    api.product(id).then(setProduct).catch(e => setError(e.message))
  }, [id])

  useEffect(() => { setProduct(null); setError(null); load() }, [load])

  if (error) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p className="error">{error}</p></main>
  if (!product) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p>불러오는 중...</p></main>

  const categoryPath = product.category?.map(c => c.name).join(' > ')

  return (
    <main className="product-detail">
      <button onClick={onBack}>뒤로</button>

      <div className="gallery">
        {product.images?.length
          ? product.images.map((img, i) => (
              <img key={img.id} src={img.url} alt={`${product.name} ${i + 1}`} />
            ))
          : <div className="gallery-ph">이미지 없음</div>}
      </div>

      {categoryPath && <p className="category-path">{categoryPath}</p>}
      <h1>{product.name}</h1>

      <table className="sku-table">
        <thead>
          <tr><th>SKU</th><th>옵션</th><th>가격</th><th>재고</th></tr>
        </thead>
        <tbody>
          {product.skus?.map(s => (
            <tr key={s.skuCode}>
              <td>{s.skuCode}</td>
              <td>{s.optionSummary}</td>
              <td>₩{s.price.toLocaleString()}</td>
              <td>{s.availableQty}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {me?.role === 'ADMIN' && (
        <ImageManager productId={id} images={product.images} onChanged={load} />
      )}
    </main>
  )
}
