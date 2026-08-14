import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api'

function levelThreeCategories(tree) {
  const out = []
  ;(function walk(nodes) {
    nodes.forEach(node => {
      if (node.level === 3) out.push(node)
      if (node.children?.length) walk(node.children)
    })
  })(tree)
  return out
}
const emptySku = () => ({ skuCode: '', optionSummary: '', initialStock: 0, price: 0 })

export default function ProductCreate() {
  const [cats, setCats] = useState([])
  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [skus, setSkus] = useState([emptySku()])
  const [err, setErr] = useState('')
  const navigate = useNavigate()

  useEffect(() => { api.categories().then(t => setCats(levelThreeCategories(t))).catch(() => setCats([])) }, [])

  const setSku = (i, k) => (e) => {
    const v = k === 'initialStock' || k === 'price' ? Number(e.target.value) : e.target.value
    setSkus(skus.map((s, j) => (j === i ? { ...s, [k]: v } : s)))
  }

  async function submit(e) {
    e.preventDefault(); setErr('')
    if (!name || !categoryId || skus.some(s => !s.skuCode)) { setErr('이름·카테고리·SKU 코드는 필수입니다.'); return }
    try {
      const res = await api.createProduct({ name, categoryId: Number(categoryId), skus })
      navigate(`/admin/products/${res.productId}`)
    } catch (e) { setErr(e.message) }
  }

  return (
    <>
      <h1>상품 등록</h1>
      <form className="admin-form" onSubmit={submit}>
        <input placeholder="상품명" value={name} onChange={e => setName(e.target.value)} />
        <select value={categoryId} onChange={e => setCategoryId(e.target.value)}>
          <option value="">카테고리 선택</option>
          {cats.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        {skus.map((s, i) => (
          <div className="sku-row" key={i}>
            <input placeholder="SKU코드" value={s.skuCode} onChange={setSku(i, 'skuCode')} />
            <input placeholder="옵션(예: 블랙/M)" value={s.optionSummary} onChange={setSku(i, 'optionSummary')} />
            <input type="number" placeholder="재고" value={s.initialStock} onChange={setSku(i, 'initialStock')} min="0" />
            <input type="number" placeholder="가격" value={s.price} onChange={setSku(i, 'price')} min="0" />
            <button type="button" onClick={() => setSkus(skus.filter((_, j) => j !== i))} disabled={skus.length === 1}>삭제</button>
          </div>
        ))}
        <button type="button" onClick={() => setSkus([...skus, emptySku()])}>+ SKU 추가</button>
        <button className="primary" type="submit">등록</button>
        {err && <p className="error">{err}</p>}
      </form>
    </>
  )
}
