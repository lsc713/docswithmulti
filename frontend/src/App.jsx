import { useEffect, useState } from 'react'
import { api } from './api'
import NavBar from './components/NavBar'
import AuthModal from './components/AuthModal'
import Home from './components/Home'
import ProductDetail from './components/ProductDetail'

export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState({ name: 'home' })
  const [authOpen, setAuthOpen] = useState(false)

  useEffect(() => { api.me().then(setMe).catch(() => setMe(null)) }, [])

  return (
    <>
      <NavBar me={me} onHome={() => setView({ name: 'home' })}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => { await api.logout(); setMe(null) }} />
      {view.name === 'home'
        ? <Home onOpen={(id) => setView({ name: 'detail', id })} />
        : <ProductDetail id={view.id} me={me} onBack={() => setView({ name: 'home' })} />}
      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => { setMe(u); setAuthOpen(false) }} />
    </>
  )
}
