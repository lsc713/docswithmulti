import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'

export default function AdminApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/admin/login" element={<div><h1>어드민 로그인</h1></div>} />
        <Route path="/admin" element={<div><h1>대시보드</h1></div>} />
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
