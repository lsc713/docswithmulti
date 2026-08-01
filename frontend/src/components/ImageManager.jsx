import { useState } from 'react'
import { api, putToS3 } from '../api'

// ponytail: api.product()의 imageUrls엔 imageId가 없다. 이 세션에서 업로드해 confirmImage
// 응답으로 id를 받은 이미지만 삭제/순서변경 가능 — 기존 이미지는 조회 불가. 백엔드가
// detail DTO에 imageId를 내려주면 전체 삭제/순서변경으로 확장.
export default function ImageManager({ productId, images, onChanged }) {
  const [uploaded, setUploaded] = useState([]) // { id, url } — 이번 세션에 업로드해 id를 아는 이미지
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const canReorder = uploaded.length > 0 && uploaded.length === (images?.length ?? 0)

  async function handleUpload(e) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setError(null)
    setBusy(true)
    try {
      const { key, uploadUrl } = await api.presignImage(productId, file.type)
      await putToS3(uploadUrl, file)
      const { imageId } = await api.confirmImage(productId, key)
      setUploaded(list => [...list, { id: imageId, url: URL.createObjectURL(file) }])
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
      setUploaded(list => list.filter(im => im.id !== imageId))
      onChanged()
    } catch (err) {
      setError(err.message)
    }
  }

  function move(index, dir) {
    const j = index + dir
    if (j < 0 || j >= uploaded.length) return
    const next = [...uploaded]
    ;[next[index], next[j]] = [next[j], next[index]]
    setUploaded(next)
    api.reorderImages(productId, next.map(im => im.id)).then(onChanged).catch(err => setError(err.message))
  }

  return (
    <section className="image-manager">
      <h2>이미지 관리 (ADMIN)</h2>
      <p className="image-manager-count">
        전체 {images?.length ?? 0}장 — 삭제/순서변경은 이번 세션에 업로드한 이미지에만 적용됩니다.
      </p>

      <label className="upload-btn">
        {busy ? '업로드 중...' : '이미지 추가'}
        <input type="file" accept="image/*" onChange={handleUpload} disabled={busy} hidden />
      </label>
      {error && <p className="error">{error}</p>}

      {uploaded.length > 0 && (
        <ul className="image-manager-list">
          {uploaded.map((im, i) => (
            <li key={im.id}>
              <img src={im.url} alt="" />
              <div className="image-manager-actions">
                <button onClick={() => move(i, -1)} disabled={!canReorder || i === 0}>위</button>
                <button onClick={() => move(i, 1)} disabled={!canReorder || i === uploaded.length - 1}>아래</button>
                <button onClick={() => handleDelete(im.id)}>삭제</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {uploaded.length > 0 && !canReorder && (
        <p className="image-manager-note">
          순서변경은 이 상품의 모든 이미지를 이번 세션에서 업로드했을 때만 가능합니다.
        </p>
      )}
    </section>
  )
}
