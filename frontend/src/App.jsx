import { useEffect, useState } from 'react'
import './App.css'
import { api } from './api'
import NavBar from './components/NavBar'
import AuthModal from './components/AuthModal'
import Home from './components/Home'
import ProductDetail from './components/ProductDetail'
import Checkout from './components/Checkout'
import OrderSuccess from './components/OrderSuccess'

export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState({ name: 'home' })
  const [authOpen, setAuthOpen] = useState(false)

  useEffect(() => { api.me().then(setMe).catch(() => setMe(null)) }, [])

  function handleBuy(lines) {
    if (!me) { setAuthOpen(true); return }
    setView({ name: 'checkout', lines })
  }

  return (
    <>
      <NavBar me={me} onHome={() => setView({ name: 'home' })}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => { await api.logout(); setMe(null) }} />

      {view.name === 'home' && <Home onOpen={(id) => setView({ name: 'detail', id })} />}
      {view.name === 'detail' && (
        <ProductDetail id={view.id} me={me} onBack={() => setView({ name: 'home' })} onBuy={handleBuy} />
      )}
      {view.name === 'checkout' && (
        <Checkout lines={view.lines}
                  onPaid={(payment) => setView({ name: 'success', payment })}
                  onBack={() => setView({ name: 'home' })} />
      )}
      {view.name === 'success' && (
        <OrderSuccess payment={view.payment} onHome={() => setView({ name: 'home' })} />
      )}

      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => { setMe(u); setAuthOpen(false) }} />
    </>
  )
}
