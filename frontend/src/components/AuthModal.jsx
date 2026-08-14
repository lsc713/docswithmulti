import { useEffect, useRef, useState } from 'react'
import { api } from '../api'

export default function AuthModal({ open, onClose, onAuthed }) {
  const [form, setForm] = useState({ email: '', password: '' })
  const [error, setError] = useState('')
  const [emailError, setEmailError] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const emailInput = useRef(null)
  const dialog = useRef(null)
  const submitting = useRef(false)
  const attempt = useRef(0)
  const isOpen = useRef(open)
  const trigger = useRef(null)
  isOpen.current = open

  useEffect(() => {
    if (!open) return
    submitting.current = false
    setLoading(false)
    trigger.current = document.activeElement
    emailInput.current?.focus()
    return () => {
      attempt.current += 1
      trigger.current?.focus()
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKeyDown = event => {
      if (event.key === 'Escape') return onClose()
      if (event.key !== 'Tab') return
      const focusable = [...dialog.current.querySelectorAll('button:not(:disabled), input:not(:disabled)')]
      const first = focusable[0]
      const last = focusable.at(-1)
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = previousOverflow
    }
  }, [open, onClose])

  if (!open) return null

  async function submit(event) {
    event.preventDefault()
    if (submitting.current) return
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
      setEmailError('이메일 형식을 확인하세요.')
      emailInput.current?.focus()
      return
    }
    setEmailError('')
    setError('')
    submitting.current = true
    setLoading(true)
    const attemptId = ++attempt.current
    try {
      await api.login(form)
      if (!isOpen.current || attempt.current !== attemptId) return
      const identity = await api.me()
      if (!isOpen.current || attempt.current !== attemptId) return
      onAuthed(identity)
    } catch (caught) {
      if (!isOpen.current || attempt.current !== attemptId) return
      setError(caught.status === 401 ? '이메일 또는 비밀번호를 확인하세요.' : caught.message)
    } finally {
      if (attempt.current === attemptId) {
        submitting.current = false
        setLoading(false)
      }
    }
  }

  return (
    <div className="modal-overlay" onClick={event => event.target === event.currentTarget && onClose()}>
      <section ref={dialog} className="modal" role="dialog" aria-modal="true"
               aria-labelledby="login-title" aria-describedby="login-description">
        <header className="auth-modal-header">
          <div>
            <h1 id="login-title">로그인</h1>
            <p id="login-description">이메일과 비밀번호를 입력하세요.</p>
          </div>
        </header>
        {error && <p className="auth-error" role="alert">{error}</p>}
        <form className="auth-form" onSubmit={submit} noValidate>
          <label htmlFor="login-email">이메일</label>
          <input ref={emailInput} id="login-email" name="email" type="email"
                 autoComplete="email" placeholder="name@example.com" value={form.email}
                 aria-invalid={emailError ? 'true' : undefined}
                 aria-describedby={emailError ? 'login-email-error' : undefined}
                 onChange={event => {
                   setEmailError('')
                   setForm({ ...form, email: event.target.value })
                 }} />
          {emailError && <p id="login-email-error" className="field-error" role="alert">{emailError}</p>}
          <label htmlFor="login-password">비밀번호</label>
          <div className="password-field">
            <input id="login-password" name="password" type={showPassword ? 'text' : 'password'}
                   autoComplete="current-password" placeholder="비밀번호 입력" value={form.password}
                   aria-invalid={error ? 'true' : undefined}
                   aria-describedby={error ? 'login-password-error' : undefined}
                   onChange={event => {
                     setError('')
                     setForm({ ...form, password: event.target.value })
                   }} />
            <button type="button" className="password-toggle"
                    aria-label={`비밀번호 ${showPassword ? '숨기기' : '표시'}`}
                    onClick={() => setShowPassword(visible => !visible)}>
              <svg aria-hidden="true" viewBox="0 0 24 24">
                <path d="M2.5 12s3.5-5 9.5-5 9.5 5 9.5 5-3.5 5-9.5 5-9.5-5-9.5-5Z" />
                <circle cx="12" cy="12" r="2.5" />
                {showPassword && <path d="m4 4 16 16" />}
              </svg>
            </button>
          </div>
          {error && <p id="login-password-error" className="field-error">입력 내용을 확인하세요.</p>}
          <button className="auth-submit" type="submit" disabled={loading}>
            {loading ? '로그인 중…' : '로그인'}
          </button>
        </form>
        <p className="auth-helper">이메일 · 비밀번호 로그인</p>
        <button type="button" className="modal-close" aria-label="로그인 닫기" onClick={onClose}>×</button>
      </section>
    </div>
  )
}
