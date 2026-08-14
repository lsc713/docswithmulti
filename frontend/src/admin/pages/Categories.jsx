import { useEffect, useState } from 'react'
import { api } from '../../api'

function CategoryTree({ nodes }) {
  return <ul>{nodes.map(node => (
    <li key={node.id}>
      {node.name}
      {node.children?.length ? <CategoryTree nodes={node.children} /> : null}
    </li>
  ))}</ul>
}

export default function Categories() {
  const [tree, setTree] = useState([])
  const [parentId, setParentId] = useState('')
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const parents = tree.flatMap(root => [
    { id: root.id, label: root.name },
    ...(root.children ?? []).map(child => ({ id: child.id, label: `${root.name} > ${child.name}` })),
  ])

  useEffect(() => {
    api.categories().then(setTree).catch(error => setError(error.message))
  }, [])

  async function submit(event) {
    event.preventDefault()
    if (!name.trim() || busy) return
    setBusy(true)
    setError('')
    try {
      await api.createCategory({ parentId: parentId ? Number(parentId) : null, name: name.trim() })
      setName('')
      setTree(await api.categories())
    } catch (error) {
      setError(error.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h1>카테고리 관리</h1>
      <form className="admin-form" onSubmit={submit}>
        <select aria-label="상위 카테고리" value={parentId} onChange={event => setParentId(event.target.value)}>
          <option value="">대분류</option>
          {parents.map(parent => <option key={parent.id} value={parent.id}>{parent.label}</option>)}
        </select>
        <input placeholder="카테고리 이름" value={name} onChange={event => setName(event.target.value)} />
        <button className="primary" type="submit" disabled={busy}>카테고리 추가</button>
        {error && <p className="error">{error}</p>}
      </form>
      <div className="admin-category-tree"><CategoryTree nodes={tree} /></div>
    </>
  )
}
