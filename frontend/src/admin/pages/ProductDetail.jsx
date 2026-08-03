import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { api } from '../../api'
import ImageManager from '../../components/ImageManager'

export default function ProductDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [product, setProduct] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(() => {
    api.product(id).then(setProduct).catch(e => setError(e.message))
  }, [id])

  useEffect(() => { setProduct(null); setError(null); load() }, [load])

  if (error) return <><button onClick={() => navigate('/admin/products')}>뒤로</button><p className="error">{error}</p></>
  if (!product) return <p>불러오는 중...</p>

  const categoryPath = product.category?.map(c => c.name).join(' > ')

  return (
    <>
      <button onClick={() => navigate('/admin/products')}>뒤로</button>
      {categoryPath && <p>{categoryPath}</p>}
      <h1>{product.name}</h1>
      <div className="admin-grid" style={{ maxWidth: 480 }}>
        {product.images?.length
          ? product.images.map((img, i) => <img key={img.id} src={img.url} alt={`${product.name} ${i + 1}`} />)
          : <div className="ph">이미지 없음</div>}
      </div>
      <table className="admin-table" style={{ maxWidth: 560, marginTop: 16 }}>
        <thead><tr><th>SKU</th><th>옵션</th><th>가격</th><th>재고</th></tr></thead>
        <tbody>
          {product.skus?.map(s => (
            <tr key={s.skuCode}>
              <td>{s.skuCode}</td><td>{s.optionSummary}</td>
              <td>₩{s.price.toLocaleString()}</td><td>{s.availableQty}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ImageManager productId={id} images={product.images} onChanged={load} />
    </>
  )
}
