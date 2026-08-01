import { useEffect, useState } from 'react'
import { api, putToS3 } from '../api'

export default function ImageManager({ productId, images, onChanged }) {
  const [order, setOrder] = useState(images ?? []) // local drag-order buffer, synced from images prop
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => { setOrder(images ?? []) }, [images])

  async function handleUpload(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setError(null)
    setBusy(true)
    try {
      const { key, uploadUrl } = await api.presignImage(productId, file.type)
      await putToS3(uploadUrl, file)
      await api.confirmImage(productId, key)
      onChanged()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(imageId) {
    setError(null)
    try {
      await api.deleteImage(productId, imageId)
      onChanged()
    } catch (err) {
      setError(err.message)
    }
  }

  function move(index, dir) {
    const j = index + dir
    if (j < 0 || j >= order.length) return
    const previous = order
    const next = [...order]
    ;[next[index], next[j]] = [next[j], next[index]]
    setOrder(next)
    api.reorderImages(productId, next.map(img => img.id)).then(onChanged).catch(err => {
      setOrder(previous)
      setError(err.message)
    })
  }

  return (
    <section className="image-manager">
      <h2>이미지 관리 (ADMIN)</h2>

      <label className="upload-btn">
        {busy ? '업로드 중...' : '이미지 추가'}
        <input type="file" accept="image/*" onChange={handleUpload} disabled={busy} hidden />
      </label>
      {error && <p className="error">{error}</p>}

      {order.length > 0 && (
        <ul className="image-manager-list">
          {order.map((img, i) => (
            <li key={img.id}>
              <img src={img.url} alt="" />
              <div className="image-manager-actions">
                <button onClick={() => move(i, -1)} disabled={i === 0}>위</button>
                <button onClick={() => move(i, 1)} disabled={i === order.length - 1}>아래</button>
                <button onClick={() => handleDelete(img.id)}>삭제</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
