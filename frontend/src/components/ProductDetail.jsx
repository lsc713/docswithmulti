import ProductDetailDraft from './ProductDetailDraft'

export default function ProductDetail({ id, me, onBack, onBuy, onAddToCart }) {
  return (
    <ProductDetailDraft id={id} variant="gallery" production me={me} onBack={onBack}
                        onBuy={onBuy} onAddToCart={onAddToCart} />
  )
}
