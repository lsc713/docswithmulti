import { useState } from 'react'
import { api } from './api'

export default function App() {
  const [mode, setMode] = useState('login')       // 'login' | 'signup'
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '' })
  const [me, setMe] = useState(null)
  const [err, setErr] = useState('')

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault(); setErr('')
    try {
      if (mode === 'signup') await api.signup(form)
      else await api.login({ email: form.email, password: form.password })
      setMe(await api.me())                         // 신원은 /me로만 (토큰 미접근)
    } catch (e) { setErr(e.message) }
  }

  async function logout() {
    try { await api.logout(); setMe(null) } catch (e) { setErr(e.message) }
  }

  if (me) return (
    <main style={{ fontFamily: 'sans-serif', padding: 40 }}>
      <h1>안녕하세요, {me.name}님</h1>
      <p>{me.email} · {me.role} · userId {me.userId}</p>
      <button onClick={logout}>로그아웃</button>
    </main>
  )

  return (
    <main style={{ fontFamily: 'sans-serif', padding: 40, maxWidth: 320 }}>
      <h1>{mode === 'login' ? '로그인' : '회원가입'}</h1>
      <form onSubmit={submit} style={{ display: 'grid', gap: 8 }}>
        <input placeholder="email" value={form.email} onChange={set('email')} />
        <input placeholder="password" type="password" value={form.password} onChange={set('password')} />
        {mode === 'signup' && <input placeholder="name" value={form.name} onChange={set('name')} />}
        {mode === 'signup' && <input placeholder="phone" value={form.phone} onChange={set('phone')} />}
        <button type="submit">{mode === 'login' ? '로그인' : '가입'}</button>
      </form>
      {err && <p style={{ color: 'crimson' }}>{err}</p>}
      <button onClick={() => setMode(mode === 'login' ? 'signup' : 'login')} style={{ marginTop: 12 }}>
        {mode === 'login' ? '회원가입으로' : '로그인으로'}
      </button>
    </main>
  )
}
