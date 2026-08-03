import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api } from '../api'

export default function AdminLayout() {
  const navigate = useNavigate()
  async function logout() {
    try { await api.logout() } catch { /* 무시 */ }
    navigate('/admin/login', { replace: true })
  }
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="brand">어드민 콘솔</div>
        <NavLink to="/admin" end>대시보드</NavLink>
        <NavLink to="/admin/products">상품관리</NavLink>
        <NavLink to="/admin/products/new">상품 등록</NavLink>
        <NavLink to="/admin/users">회원관리</NavLink>
        <a onClick={logout} style={{ cursor: 'pointer' }}>로그아웃</a>
      </aside>
      <main className="admin-main"><Outlet /></main>
    </div>
  )
}
