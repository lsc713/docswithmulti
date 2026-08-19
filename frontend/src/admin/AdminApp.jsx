import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import RequireRole from './RequireRole'
import AdminLayout from './AdminLayout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ProductList from './pages/ProductList'
import ProductCreate from './pages/ProductCreate'
import ProductDetail from './pages/ProductDetail'
import Users from './pages/Users'
import CancelRequests from './pages/CancelRequests'
import Categories from './pages/Categories'
import Orders from './pages/Orders'
import Settlements from './pages/Settlements'

export default function AdminApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/admin/login" element={<Login />} />
        <Route path="/admin" element={<RequireRole roles={['ADMIN', 'MERCHANT']}><AdminLayout /></RequireRole>}>
          <Route index element={<Dashboard />} />
          <Route path="cancel-requests" element={<CancelRequests />} />
          <Route path="products" element={<RequireRole roles={['ADMIN']}><ProductList /></RequireRole>} />
          <Route path="products/new" element={<RequireRole roles={['ADMIN']}><ProductCreate /></RequireRole>} />
          <Route path="products/:id" element={<RequireRole roles={['ADMIN']}><ProductDetail /></RequireRole>} />
          <Route path="categories" element={<RequireRole roles={['ADMIN']}><Categories /></RequireRole>} />
          <Route path="users" element={<RequireRole roles={['ADMIN']}><Users /></RequireRole>} />
          <Route path="orders" element={<RequireRole roles={['ADMIN']}><Orders /></RequireRole>} />
          <Route path="settlements" element={<RequireRole roles={['ADMIN']}><Settlements /></RequireRole>} />
        </Route>
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
