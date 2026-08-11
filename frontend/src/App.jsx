import { useEffect, useState } from 'react'
import './App.css'
import { api } from './api'
import NavBar from './components/NavBar'
import AuthModal from './components/AuthModal'
import Home from './components/Home'
import ProductDetail from './components/ProductDetail'
import Checkout from './components/Checkout'
import OrderSuccess from './components/OrderSuccess'
import Cart from './components/Cart'
import OrderHistory from './components/OrderHistory'

export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState({ name: 'home' })
  const [authOpen, setAuthOpen] = useState(false)
  const [cart, setCart] = useState([])
  const [payments, setPayments] = useState([])
  const [productQuery, setProductQuery] = useState('')

  useEffect(() => { api.me().then(setMe).catch(() => setMe(null)) }, [])

  const loadCart = () => api.getCart().then(r => setCart(r.items)).catch(() => setCart([]))
  useEffect(() => { if (me) loadCart() }, [me])

  const loadPayments = () => api.getPayments().then(setPayments).catch(() => setPayments([]))

  async function handleRequestCancel(key, reason) {
    try { await api.requestCancel(key, reason) }
    catch (e) { alert(e.message) }
    await loadPayments()   // 성공/실패 무관 서버 상태 반영 (409 중복요청이어도 '취소 요청됨'으로 갱신)
  }

  function handleBuy(lines) {
    if (!me) { setAuthOpen(true); return }
    setView({ name: 'checkout', lines })
  }

  async function handleAddToCart(lines) {
    if (!me) { setAuthOpen(true); return }
    for (const l of lines) {
      await api.addCartItem({ skuId: l.skuId, productId: l.productId, itemName: l.itemName,
        optionSummary: l.optionSummary, unitPrice: l.unitPrice, quantity: l.quantity })
    }
    await loadCart()
    setView({ name: 'cart' })
  }

  const onQty = async (skuId, q) => { await api.updateCartItem(skuId, q); loadCart() }
  const onRemove = async (skuId) => { await api.removeCartItem(skuId); loadCart() }

  return (
    <>
      <NavBar home={view.name === 'home'} me={me} onHome={() => setView({ name: 'home' })}
              productQuery={productQuery} onProductQueryChange={setProductQuery}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => { await api.logout(); setMe(null) }}
              cartCount={cart.reduce((s, i) => s + i.quantity, 0)}
              onCart={() => setView({ name: 'cart' })}
              onHistory={() => { loadPayments(); setView({ name: 'history' }) }} />

      {view.name === 'home' && <Home query={productQuery} onOpen={(id) => setView({ name: 'detail', id })} />}
      {view.name === 'detail' && (
        <ProductDetail id={view.id} me={me} onBack={() => setView({ name: 'home' })} onBuy={handleBuy}
                       onAddToCart={handleAddToCart} />
      )}
      {view.name === 'cart' && (
        <Cart items={cart} onQty={onQty} onRemove={onRemove}
              onOrder={(lines) => setView({ name: 'checkout', lines, fromCart: true })}
              onBack={() => setView({ name: 'home' })} />
      )}
      {view.name === 'checkout' && (
        <Checkout lines={view.lines}
                  onPaid={async (payment) => {
                    if (view.fromCart) { try { await api.clearCart() } catch { /* noop */ } setCart([]) }
                    setView({ name: 'success', payment })
                  }}
                  onBack={() => setView({ name: 'home' })} />
      )}
      {view.name === 'success' && (
        <OrderSuccess payment={view.payment} onHome={() => setView({ name: 'home' })} />
      )}
      {view.name === 'history' && (
        <OrderHistory payments={payments} onRequestCancel={handleRequestCancel} onBack={() => setView({ name: 'home' })} />
      )}

      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => { setMe(u); setAuthOpen(false) }} />
    </>
  )
}
