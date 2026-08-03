import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api } from '../api'

export default function RequireAdmin({ children }) {
  const [state, setState] = useState({ loading: true, ok: false })

  useEffect(() => {
    api.me()
      .then(me => setState({ loading: false, ok: me.role === 'ADMIN' }))
      .catch(() => setState({ loading: false, ok: false }))
  }, [])

  if (state.loading) return <div className="admin-main">확인 중...</div>
  if (!state.ok) return <Navigate to="/admin/login" replace />
  return children
}
