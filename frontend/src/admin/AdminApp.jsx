import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import RequireAdmin from './RequireAdmin'
import AdminLayout from './AdminLayout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ProductList from './pages/ProductList'
import ProductCreate from './pages/ProductCreate'
import ProductDetail from './pages/ProductDetail'
import Users from './pages/Users'

export default function AdminApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/admin/login" element={<Login />} />
        <Route path="/admin" element={<RequireAdmin><AdminLayout /></RequireAdmin>}>
          <Route index element={<Dashboard />} />
          <Route path="products" element={<ProductList />} />
          <Route path="products/new" element={<ProductCreate />} />
          <Route path="products/:id" element={<ProductDetail />} />
          <Route path="users" element={<Users />} />
        </Route>
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
