import { useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api } from '../api'

export default function AdminLayout() {
  const navigate = useNavigate()
  const [role, setRole] = useState(null)
  useEffect(() => { api.me().then(me => setRole(me.role)).catch(() => setRole(null)) }, [])
  async function logout() {
    try { await api.logout() } catch { /* 무시 */ }
    navigate('/admin/login', { replace: true })
  }
  const isAdmin = role === 'ADMIN'
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="brand">어드민 콘솔</div>
        {isAdmin && <NavLink to="/admin" end>대시보드</NavLink>}
        <NavLink to="/admin/cancel-requests">취소 요청</NavLink>
        {isAdmin && <NavLink to="/admin/products">상품관리</NavLink>}
        {isAdmin && <NavLink to="/admin/products/new">상품 등록</NavLink>}
        {isAdmin && <NavLink to="/admin/categories">카테고리 관리</NavLink>}
        {isAdmin && <NavLink to="/admin/users">회원관리</NavLink>}
        {isAdmin && <NavLink to="/admin/orders">주문관리</NavLink>}
        {isAdmin && <NavLink to="/admin/settlements">정산관리</NavLink>}
        <button onClick={logout} className="logout-btn">로그아웃</button>
      </aside>
      <main className="admin-main"><Outlet /></main>
    </div>
  )
}
