import { useEffect, useState } from 'react'
import './App.css'
import { api } from './api'
import NavBar from './components/NavBar'
import AuthModal from './components/AuthModal'
import Home from './components/Home'
import ProductDetail from './components/ProductDetail'
import ProductDetailDraft from './components/ProductDetailDraft'
import Checkout from './components/Checkout'
import Payment from './components/Payment'
import OrderSuccess from './components/OrderSuccess'
import Cart from './components/Cart'
import OrderHistory from './components/OrderHistory'
import PaymentReturn from './components/PaymentReturn'
import { clearOrderRouteState, normalizeOrderItems, persistOrderRouteState, resolveOrderRouteState } from './orderFlow'

const DETAIL_DRAFTS = new Set(['editorial', 'gallery', 'compact'])
const STORE_PATHS = { home: '/', cart: '/cart', history: '/history', success: '/order-success', detail: '/' }

function savedPaymentAttempt() {
  try { return JSON.parse(sessionStorage.getItem('paymentAttempt')) }
  catch { return null }
}

function getDetailDraftRequest(search) {
  const params = new URLSearchParams(search)
  const variant = params.get('detailDraft')
  const product = params.get('product')
  if (!DETAIL_DRAFTS.has(variant) || !/^\d+$/.test(product ?? '')) return null
  return { variant, productId: Number(product) }
}

function getInitialView() {
  const path = window.location.pathname
  if (path === '/payment/success') return { name: 'payment-return', kind: 'success' }
  if (path === '/payment/fail') return { name: 'payment-return', kind: 'fail' }
  if (path === '/checkout' || path === '/payment') {
    return { name: path.slice(1), flowState: null }
  }
  if (path === '/cart' || path === '/history') return { name: path.slice(1) }
  const storedView = window.history.state?.storeView
  if (path === '/order-success' && storedView?.name === 'success' && storedView.payment) return storedView
  return { name: 'home' }
}

export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState(getInitialView)
  const [authOpen, setAuthOpen] = useState(false)
  const [cart, setCart] = useState([])
  const [payments, setPayments] = useState([])
  const [paymentContext, setPaymentContext] = useState(savedPaymentAttempt)
  const [productQuery, setProductQuery] = useState('')
  const draftRequest = getDetailDraftRequest(window.location.search)
  const draftOpen = view.name === 'home' && draftRequest !== null

  function hideOrderFlowClientState() {
    setCart([])
    setView(current => current.name === 'checkout' || current.name === 'payment'
      ? { name: current.name, flowState: null }
      : current)
  }

  function clearOrderFlowClientState() {
    clearOrderRouteState()
    sessionStorage.removeItem('paymentAttempt')
    setPaymentContext(null)
    hideOrderFlowClientState()
  }

  useEffect(() => {
    api.me().then(user => {
      setMe(user)
      const restoredFlowState = resolveOrderRouteState(undefined, user.userId)
      const name = window.location.pathname === '/checkout'
        ? 'checkout'
        : window.location.pathname === '/payment' ? 'payment' : null
      if (name) setView({ name, flowState: restoredFlowState })
    }).catch(error => {
      setMe(null)
      if (error.status === 401) clearOrderFlowClientState()
      else hideOrderFlowClientState()
    })
  }, [])

  useEffect(() => {
    const onPopState = event => {
      const name = window.location.pathname === '/checkout' ? 'checkout' : window.location.pathname === '/payment' ? 'payment' : null
      if (name) setView({ name, flowState: resolveOrderRouteState(undefined, me?.userId) })
      else setView(event.state?.storeView ?? getInitialView())
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [me?.userId])

  const loadCart = () => api.getCart().then(r => setCart(r.items)).catch(() => setCart([]))
  useEffect(() => { if (me) loadCart() }, [me])

  const loadPayments = () => api.getPayments().then(setPayments).catch(() => setPayments([]))
  useEffect(() => { if (view.name === 'history') loadPayments() }, [view.name])

  async function handleRequestCancel(key, reason) {
    try { await api.requestCancel(key, reason) }
    catch (e) { alert(e.message) }
    await loadPayments()   // 성공/실패 무관 서버 상태 반영 (409 중복요청이어도 '취소 요청됨'으로 갱신)
  }

  function openOrderRoute(name, flowState) {
    if (!persistOrderRouteState(flowState, me?.userId)) return
    window.history.replaceState({ ...(window.history.state ?? {}), storeView: view }, '', window.location.href)
    window.history.pushState(null, '', `/${name}`)
    setView({ name, flowState })
  }

  function openStoreView(nextView, replace = false) {
    window.history[replace ? 'replaceState' : 'pushState']({ storeView: nextView }, '', STORE_PATHS[nextView.name] ?? '/')
    setView(nextView)
  }

  function handleBuy(lines) {
    if (!me) { setAuthOpen(true); return }
    const orderItems = normalizeOrderItems(lines)
    openOrderRoute('checkout', { orderItems, source: 'product', productId: orderItems[0]?.productId })
  }

  async function handleAddToCart(lines) {
    if (!me) { setAuthOpen(true); return }
    for (const l of lines) {
      await api.addCartItem({ skuId: l.skuId, productId: l.productId, itemName: l.itemName,
        optionSummary: l.optionSummary, unitPrice: l.unitPrice, quantity: l.quantity })
    }
    await loadCart()
    openStoreView({ name: 'cart' })
  }

  const onQty = async (skuId, q) => { await api.updateCartItem(skuId, q); loadCart() }
  const onRemove = async (skuId) => { await api.removeCartItem(skuId); loadCart() }

  const handleHome = () => {
    if (draftRequest) {
      const url = new URL(window.location.href)
      url.searchParams.delete('detailDraft')
      url.searchParams.delete('product')
      window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`)
    }
    if (window.location.pathname === '/' && view.name === 'home') setView({ name: 'home' })
    else openStoreView({ name: 'home' })
  }

  return (
    <>
      <NavBar home={view.name === 'home' && !draftOpen} me={me} onHome={handleHome}
              productQuery={productQuery} onProductQueryChange={setProductQuery}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => {
                await api.logout()
                clearOrderFlowClientState()
                setMe(null)
                openStoreView({ name: 'home' }, true)
              }}
              cartCount={cart.reduce((s, i) => s + i.quantity, 0)}
              onCart={() => openStoreView({ name: 'cart' })}
              onHistory={() => openStoreView({ name: 'history' })} />

      {view.name === 'home' && !draftOpen && <Home query={productQuery} onOpen={(id) => setView({ name: 'detail', id })} />}
      {draftOpen && (
        <ProductDetailDraft id={draftRequest.productId} variant={draftRequest.variant}
                            onBack={handleHome} onBuy={handleBuy} onAddToCart={handleAddToCart} />
      )}
      {view.name === 'detail' && (
        <ProductDetail id={view.id} me={me} onBack={handleHome} onBuy={handleBuy}
                       onAddToCart={handleAddToCart} />
      )}
      {view.name === 'cart' && (
        <Cart items={cart} onQty={onQty} onRemove={onRemove}
              onOrder={(lines) => openOrderRoute('checkout', { orderItems: normalizeOrderItems(lines), source: 'cart' })}
              onBack={handleHome} />
      )}
      {view.name === 'checkout' && (
        <Checkout flowState={view.flowState} me={me}
                  onContinue={() => openOrderRoute('payment', view.flowState)}
                  onBack={() => {
                    const source = view.flowState?.source
                    const returnView = source === 'cart'
                      ? { name: 'cart' }
                      : source === 'product' && view.flowState?.productId
                        ? { name: 'detail', id: view.flowState.productId }
                        : { name: 'home' }
                    openStoreView(returnView)
                  }} />
      )}
      {view.name === 'payment' && (
        <Payment flowState={view.flowState}
                 onBack={() => window.history.back()} />
      )}
      {view.name === 'payment-return' && (
        <PaymentReturn kind={view.kind} context={paymentContext}
          onCompleted={async (payment) => {
            if (paymentContext?.source === 'cart' || paymentContext?.fromCart) {
              try { await api.clearCart(); setCart([]) } catch { loadCart() }
            }
            sessionStorage.removeItem('paymentAttempt')
            clearOrderRouteState()
            const completedPayment = { ...payment, totalAmount: payment.amount }
            window.history.replaceState({ storeView: { name: 'success', payment: completedPayment } }, '', '/order-success')
            setView({ name: 'success', payment: completedPayment })
          }}
          onRetry={(context) => {
            const flowState = {
              orderItems: context.orderItems ?? context.lines,
              source: context.source ?? (context.fromCart ? 'cart' : 'product'),
              productId: context.productId,
              retryItems: context.paymentItems,
            }
            window.history.replaceState({}, '', '/payment')
            setView({ name: 'payment', flowState })
          }} />
      )}
      {view.name === 'success' && (
        <OrderSuccess payment={view.payment} onHome={handleHome} />
      )}
      {view.name === 'history' && (
        <OrderHistory payments={payments} onRequestCancel={handleRequestCancel} onBack={handleHome} />
      )}

      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => {
                   clearOrderFlowClientState()
                   setMe(u)
                   setAuthOpen(false)
                 }} />
    </>
  )
}
