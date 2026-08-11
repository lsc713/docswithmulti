import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api'
import abstractArt from '../assets/detail-draft-art.svg'
import './ProductDetailDraft.css'

const VARIANT_NAMES = {
  editorial: '에디토리얼',
  gallery: '갤러리',
  compact: '컴팩트',
}

function formatPrice(value) {
  return `₩${Number(value ?? 0).toLocaleString('ko-KR')}`
}

function ProductVisual({ product, gallery = false }) {
  const images = product.images ?? []
  const [activeImage, setActiveImage] = useState(0)
  const image = images[activeImage]

  useEffect(() => setActiveImage(0), [product.id])

  return (
    <section className={`detail-draft-visual${gallery ? ' detail-draft-visual-gallery' : ''}`}
             aria-label="상품 이미지">
      <div className="detail-draft-image-stage">
        <img className="detail-draft-abstract-art" src={abstractArt} alt="" />
        {image && <img className="detail-draft-api-image" src={image.url} alt={`${product.name} ${activeImage + 1}`}
                       onError={event => { event.currentTarget.hidden = true }} />}
        <span className="detail-draft-image-note">LOCAL ART · SAMPLE</span>
      </div>
      {gallery && images.length > 1 && (
        <div className="detail-draft-thumbnails" aria-label="상품 이미지 선택">
          {images.map((item, index) => (
            <button key={item.id} type="button" aria-label={`상품 이미지 ${index + 1}`}
                    aria-current={activeImage === index ? 'true' : undefined}
                    onClick={() => setActiveImage(index)}>
              <img src={abstractArt} alt="" />
              <img className="detail-draft-thumbnail-api" src={item.url} alt=""
                   onError={event => { event.currentTarget.hidden = true }} />
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

function ProductSummary({ product, kicker }) {
  const category = product.category?.map(item => item.name).join(' · ')
  const prices = (product.skus ?? []).map(item => item.price)
  const displayPrice = prices.length ? Math.min(...prices) : product.minPrice

  return (
    <header className="detail-draft-summary">
      <p className="detail-draft-kicker">{kicker}</p>
      {category && <p className="detail-draft-category">{category}</p>}
      <h1>{product.name}</h1>
      <p className="detail-draft-price">{formatPrice(displayPrice)}</p>
      <p className="detail-draft-sample-copy">
        일상의 움직임을 따라 유연하게 정돈되는 균형 잡힌 실루엣입니다.
        <span>디자인 제안 · 샘플</span>
      </p>
    </header>
  )
}

function DraftPurchasePanel({ product, onBuy, onAddToCart, compact = false }) {
  const skus = product.skus ?? []
  const [selectedSkuId, setSelectedSkuId] = useState(null)
  const [quantity, setQuantity] = useState(1)
  const selectedSku = skus.find(sku => sku.skuId === selectedSkuId)
  const allSoldOut = skus.length === 0 || skus.every(sku => sku.availableQty <= 0)

  const line = selectedSku ? [{
    skuId: selectedSku.skuId,
    productId: product.id,
    itemName: product.name,
    optionSummary: selectedSku.optionSummary,
    unitPrice: selectedSku.price,
    quantity,
  }] : []

  const selectSku = (sku) => {
    setSelectedSkuId(sku.skuId)
    setQuantity(current => Math.min(Math.max(current, 1), sku.availableQty))
  }

  const updateQuantity = (event) => {
    const next = Math.floor(Number(event.target.value) || 1)
    setQuantity(Math.min(Math.max(next, 1), selectedSku?.availableQty ?? 1))
  }

  return (
    <section className={`detail-draft-purchase${compact ? ' detail-draft-purchase-compact' : ''}`}
             aria-label="구매 정보">
      <fieldset className="detail-draft-options">
        <legend>옵션 선택</legend>
        {skus.length === 0 && <p className="detail-draft-stock-message">등록된 옵션이 없습니다.</p>}
        {skus.map(sku => {
          const soldOut = sku.availableQty <= 0
          return (
            <label key={sku.skuId} className={`detail-draft-option${soldOut ? ' detail-draft-option-disabled' : ''}`}>
              <input type="radio" name={`detail-draft-sku-${product.id}`} value={sku.skuId}
                     checked={selectedSkuId === sku.skuId} disabled={soldOut}
                     onChange={() => selectSku(sku)} />
              <span className="detail-draft-option-copy">
                <strong>{sku.optionSummary}</strong>
                <small>{formatPrice(sku.price)} · 재고 {sku.availableQty}</small>
              </span>
              {soldOut && <span className="detail-draft-sold-out">품절</span>}
            </label>
          )
        })}
      </fieldset>

      <label className="detail-draft-quantity">
        <span>수량</span>
        <input type="number" aria-label="수량" min="1" max={selectedSku?.availableQty ?? 1}
               step="1" value={quantity} disabled={!selectedSku} onChange={updateQuantity} />
      </label>

      <div className="detail-draft-selection-status" role="status" aria-live="polite">
        {allSoldOut
          ? '현재 모든 옵션이 품절되었습니다.'
          : selectedSku
            ? `${selectedSku.optionSummary}, ${quantity}개 선택됨`
            : '구매할 옵션을 선택해 주세요.'}
      </div>

      <div className="detail-draft-actions">
        <button type="button" className="detail-draft-buy" disabled={!selectedSku} onClick={() => onBuy(line)}>구매하기</button>
        <button type="button" className="detail-draft-cart" disabled={!selectedSku} onClick={() => onAddToCart(line)}>장바구니 담기</button>
      </div>
    </section>
  )
}

function SampleCard({ title, children }) {
  return (
    <article className="detail-draft-info-card">
      <p><strong>{title}</strong><span>샘플</span></p>
      <div>{children}</div>
    </article>
  )
}

function EditorialLayout(props) {
  const { product } = props
  return (
    <div className="detail-draft-editorial">
      <ProductVisual product={product} />
      <aside className="detail-draft-editorial-aside">
        <ProductSummary product={product} kicker="EDITORIAL SPLIT · 디자인 제안 · 샘플" />
        <DraftPurchasePanel {...props} />
        <SampleCard title="EDITOR'S NOTE">절제된 레이어와 부드러운 대비를 제안합니다.</SampleCard>
      </aside>
    </div>
  )
}

function GalleryLayout(props) {
  const { product } = props
  return (
    <div className="detail-draft-gallery-layout">
      <ProductSummary product={product} kicker="GALLERY FOCUS · 디자인 제안 · 샘플" />
      <ProductVisual product={product} gallery />
      <div className="detail-draft-gallery-buy">
        <DraftPurchasePanel {...props} />
        <div className="detail-draft-card-grid">
          <SampleCard title="배송">영업일 기준 2–3일 내 출고 제안</SampleCard>
          <SampleCard title="리뷰">부드러운 소재감 · 4.8 / 5 제안</SampleCard>
        </div>
      </div>
    </div>
  )
}

function CompactLayout(props) {
  const { product } = props
  return (
    <div className="detail-draft-compact">
      <ProductSummary product={product} kicker="MOBILE-FIRST COMPACT · 디자인 제안 · 샘플" />
      <DraftPurchasePanel {...props} compact />
      <ProductVisual product={product} />
      <SampleCard title="간결한 구매 경험">옵션과 수량을 먼저 확인하는 모바일 우선 제안입니다.</SampleCard>
    </div>
  )
}

const LAYOUTS = {
  editorial: EditorialLayout,
  gallery: GalleryLayout,
  compact: CompactLayout,
}

export default function ProductDetailDraft({ id, variant, onBack, onBuy, onAddToCart }) {
  const [requestKey, setRequestKey] = useState(0)
  const [state, setState] = useState({ status: 'loading', product: null, error: null })
  const requestRef = useRef(null)

  useEffect(() => {
    let ignore = false
    setState({ status: 'loading', product: null, error: null })
    const key = `${id}:${requestKey}`
    if (requestRef.current?.key !== key) requestRef.current = { key, promise: api.product(id) }
    requestRef.current.promise.then(product => {
      if (!ignore) setState({ status: 'ready', product, error: null })
    }).catch(error => {
      if (ignore) return
      setState({ status: error.status === 404 || error.code === 'PRODUCT_NOT_FOUND' ? 'not-found' : 'error',
        product: null, error })
    })
    return () => { ignore = true }
  }, [id, requestKey])

  const Layout = useMemo(() => LAYOUTS[variant], [variant])
  const label = `${VARIANT_NAMES[variant]} 상품 상세`

  if (state.status === 'loading') {
    return (
      <main className={`detail-draft-main detail-draft-main--${variant}`} aria-label={label}>
        <div className="detail-draft-state" role="status" aria-label="상품 상세 불러오는 중">
          <span className="detail-draft-loader" aria-hidden="true" />
          <p>상품 상세를 불러오고 있어요.</p>
        </div>
      </main>
    )
  }

  if (state.status === 'not-found') {
    return (
      <main className={`detail-draft-main detail-draft-main--${variant}`} aria-label={label}>
        <div className="detail-draft-state">
          <p className="detail-draft-kicker">404 · PRODUCT NOT FOUND</p>
          <h1>상품을 찾을 수 없어요</h1>
          <button type="button" className="detail-draft-back" onClick={onBack}>홈으로 돌아가기</button>
        </div>
      </main>
    )
  }

  if (state.status === 'error') {
    return (
      <main className={`detail-draft-main detail-draft-main--${variant}`} aria-label={label}>
        <div className="detail-draft-state" role="alert">
          <p className="detail-draft-kicker">CONNECTION ERROR</p>
          <h1>상품을 불러오지 못했어요</h1>
          <p>{state.error?.message}</p>
          <button type="button" className="detail-draft-back" onClick={() => setRequestKey(key => key + 1)}>다시 시도</button>
        </div>
      </main>
    )
  }

  return (
    <main className={`detail-draft-main detail-draft-main--${variant}`} aria-label={label}>
      <button type="button" className="detail-draft-back" onClick={onBack}>← 컬렉션으로</button>
      <Layout product={state.product} onBuy={onBuy} onAddToCart={onAddToCart} />
    </main>
  )
}
