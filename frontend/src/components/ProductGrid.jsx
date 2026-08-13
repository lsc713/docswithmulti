import productShape from '../assets/figma/product-shape.svg'
import productMobile from '../assets/figma/product-mobile.svg'
import productMobileRecommended from '../assets/figma/product-mobile-recommended.svg'

function ProductImage({ product, recommended }) {
  if (product.thumbnailUrl) return <img src={product.thumbnailUrl} alt="" />

  return (
    <>
      <picture className="mobile-product-art">
        <img src={recommended ? productMobileRecommended : productMobile} alt="" />
      </picture>
      <div className="desktop-product-art" aria-hidden="true">
        <img src={productShape} alt="" />
        <span />
      </div>
      <span className="sample-badge">SAMPLE</span>
    </>
  )
}

export default function ProductGrid({ items, onOpen, recommended = false, label = '상품' }) {
  return (
    <div className={recommended ? 'recommended-grid' : 'grid'} role="region" aria-label={label} tabIndex="0">
      {items.map(p => (
        <button key={p.id} className="card" onClick={() => onOpen(p.id)}>
          <div className="product-media">
            <ProductImage product={p} recommended={recommended} />
          </div>
          <div className="name">{p.name}</div>
          <div className="product-note">FASHION-SHOP EDIT</div>
          <div className="price">₩{p.minPrice.toLocaleString()}~</div>
        </button>
      ))}
    </div>
  )
}
