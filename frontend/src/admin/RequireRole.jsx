import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api } from '../api'

export default function RequireRole({ roles, children }) {
  const [state, setState] = useState({ loading: true, ok: false })

  useEffect(() => {
    api.me()
      .then(me => setState({ loading: false, ok: roles.includes(me.role) }))
      .catch(() => setState({ loading: false, ok: false }))
  }, [])  // roles는 렌더마다 새 배열일 수 있으나 마운트 1회 판정으로 충분

  if (state.loading) return <div className="admin-main">확인 중...</div>
  if (!state.ok) return <Navigate to="/admin/login" replace />
  return children
}
