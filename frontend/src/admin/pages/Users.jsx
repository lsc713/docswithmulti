import { useEffect, useState } from 'react'
import { api } from '../../api'

export default function Users() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState({ content: [], totalElements: 0, size: 20 })
  const [err, setErr] = useState('')

  const load = (p) => api.adminUsers(p, 20).then(setData).catch(e => setErr(e.message))
  useEffect(() => { load(page) }, [page])

  async function change(userId, role) {
    setErr('')
    try { await api.changeRole(userId, role); load(page) } catch (e) { setErr(e.message) }
  }

  const pages = Math.max(1, Math.ceil(data.totalElements / data.size))

  return (
    <>
      <h1>회원관리</h1>
      {err && <p className="error">{err}</p>}
      <table className="admin-table">
        <thead><tr><th>ID</th><th>이메일</th><th>이름</th><th>상태</th><th>역할</th></tr></thead>
        <tbody>
          {data.content.map(u => (
            <tr key={u.id}>
              <td>{u.id}</td><td>{u.email}</td><td>{u.name}</td><td>{u.status}</td>
              <td>
                <select value={u.role} onChange={e => change(u.id, e.target.value)}>
                  <option value="USER">USER</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {pages}</span>
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>다음</button>
      </div>
    </>
  )
}
