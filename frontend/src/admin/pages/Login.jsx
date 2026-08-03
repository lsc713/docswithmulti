import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api'

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' })
  const [err, setErr] = useState('')
  const navigate = useNavigate()
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault(); setErr('')
    try {
      await api.login(form)
      const me = await api.me()
      if (me.role !== 'ADMIN') { setErr('관리자 권한이 없습니다.'); return }
      navigate('/admin', { replace: true })
    } catch (e) { setErr(e.message) }
  }

  return (
    <form className="admin-login" onSubmit={submit}>
      <h1>어드민 로그인</h1>
      <input placeholder="email" value={form.email} onChange={set('email')} />
      <input placeholder="password" type="password" value={form.password} onChange={set('password')} />
      <button className="primary" type="submit">로그인</button>
      {err && <p className="error">{err}</p>}
    </form>
  )
}
