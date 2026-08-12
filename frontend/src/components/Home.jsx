import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import ProductGrid from './ProductGrid'
import heroDesktop from '../assets/figma/hero-desktop.svg'
import heroMobile from '../assets/figma/hero-mobile.svg'
import editorialDesktop from '../assets/figma/editorial-desktop.svg'
import emptyProducts from '../assets/figma/empty-products.svg'

function leafCategories(tree) {
  const leaves = []
  ;(function walk(nodes) {
    nodes.forEach(node => (node.children?.length ? walk(node.children) : leaves.push(node)))
  })(tree)
  return leaves
}

function uniqueProducts(productsByLeaf, leaves) {
  const products = []
  const seen = new Set()
  leaves.forEach(leaf => {
    ;(productsByLeaf[leaf.id] ?? []).forEach(product => {
      if (seen.has(product.id)) return
      seen.add(product.id)
      products.push(product)
    })
  })
  return products
}

export default function Home({ query, onOpen }) {
  const [leaves, setLeaves] = useState([])
  const [active, setActive] = useState('all')
  const [productsByLeaf, setProductsByLeaf] = useState({})
  const [status, setStatus] = useState('loading')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    api.categories(controller.signal).then(tree => {
      const nextLeaves = leafCategories(tree)
      setLeaves(nextLeaves)
      if (nextLeaves.length === 0) setStatus('ready')
    }).catch(error => {
      if (error.name !== 'AbortError') setStatus('error')
    })
    return () => controller.abort()
  }, [reloadKey])

  useEffect(() => {
    if (leaves.length === 0) return
    const controller = new AbortController()
    setStatus('loading')

    Promise.all(leaves.map(async leaf => {
      const response = await api.productsByCategory(leaf.id, 0, controller.signal)
      return [leaf.id, response.content ?? []]
    })).then(entries => {
      if (controller.signal.aborted) return
      setProductsByLeaf(Object.fromEntries(entries))
      setStatus('ready')
    }).catch(error => {
      if (error.name !== 'AbortError') setStatus('error')
    })

    return () => controller.abort()
  }, [leaves])

  const aggregate = useMemo(() => uniqueProducts(productsByLeaf, leaves), [productsByLeaf, leaves])
  const normalizedQuery = query.trim().toLocaleLowerCase()
  const visibleItems = useMemo(() => {
    const selected = normalizedQuery || active === 'all'
      ? aggregate
      : productsByLeaf[active] ?? []
    return normalizedQuery
      ? selected.filter(product => product.name.toLocaleLowerCase().includes(normalizedQuery))
      : selected
  }, [active, aggregate, normalizedQuery, productsByLeaf])

  const retryAll = () => {
    setActive('all')
    setReloadKey(key => key + 1)
  }

  if (status === 'error') {
    return (
      <main className="home state-message" aria-live="polite">
        <h1>상품을 불러오지 못했어요</h1>
        <p>잠시 후 다시 시도해 주세요.</p>
        <button className="primary-pill" onClick={retryAll}>다시 시도</button>
      </main>
    )
  }

  if (status === 'ready' && aggregate.length === 0) {
    return (
      <main className="home empty-state">
        <img src={emptyProducts} alt="" width="96" height="96" />
        <h1>아직 상품이 없어요</h1>
        <p>다른 카테고리를 둘러보거나 검색어를 바꿔보세요.</p>
        <button className="primary-pill" onClick={retryAll}>전체 상품 보기</button>
      </main>
    )
  }

  return (
    <main className="home">
      <nav className="cat-tabs" aria-label="상품 카테고리">
        <button className="all-category" aria-current={active === 'all' ? 'true' : undefined} onClick={() => setActive('all')}>전체</button>
        {leaves.map(leaf => (
          <button key={leaf.id} className={leaf.id === active ? 'active' : ''}
                  aria-current={leaf.id === active ? 'true' : undefined} onClick={() => setActive(leaf.id)}>
            {leaf.name}
          </button>
        ))}
      </nav>

      <section className="home-hero">
        <div className="hero-copy">
          <p className="eyebrow">FASHION-SHOP · CONCEPT EDIT</p>
          <h1>새로운 균형,<br />매일의 스타일</h1>
          <p className="hero-description"><span className="desktop-copy">절제된 실루엣과 부드러운 소재를 제안하는<br />패션 커머스 UI 콘셉트입니다.</span><span className="mobile-copy">절제된 실루엣을 제안하는 UI 콘셉트</span></p>
          <button className="primary-pill hero-button" onClick={() => document.querySelector('.new-arrivals')?.scrollIntoView({ behavior: 'smooth' })}>컬렉션 보기</button>
        </div>
        <picture className="hero-art">
          <source media="(max-width: 600px)" srcSet={heroMobile} />
          <img src={heroDesktop} alt="" />
        </picture>
      </section>

      <section className="product-section new-arrivals">
        <div className="section-heading">
          <h2>이번 주 신상품</h2>
          <span>전체 보기&nbsp; →</span>
        </div>
        {status === 'loading'
          ? <div className="catalog-loading" role="status">상품을 불러오는 중입니다.</div>
          : <ProductGrid items={visibleItems} onOpen={onOpen} label="이번 주 신상품" />}
      </section>

      <section className="editorial">
        <img src={editorialDesktop} alt="" />
        <div>
          <p className="eyebrow">STYLE NOTE · SAMPLE</p>
          <h2>레이어를 가볍게,<br />인상을 선명하게</h2>
          <p>계절 사이를 위한 스타일링 아이디어를 살펴보세요.</p>
        </div>
      </section>

      <section className="product-section recommended">
        <div className="section-heading">
          <h2>지금 추천하는 스타일</h2>
          <span>SAMPLE PICKS</span>
        </div>
        {status === 'loading'
          ? <div className="catalog-loading" role="status">추천 상품을 불러오는 중입니다.</div>
          : <ProductGrid items={visibleItems.slice(0, 4)} onOpen={onOpen} recommended label="지금 추천하는 스타일" />}
      </section>

      <footer className="store-footer" role="contentinfo">
        <div className="footer-columns">
          <strong>fashion-shop</strong>
          <p>SHOP<br />신상품<br />추천 스타일<br />액세서리</p>
          <p>HELP<br />이용 안내<br />문의하기<br />로그인</p>
        </div>
        <p className="mobile-footer-links">신상품&nbsp;&nbsp; 추천 스타일&nbsp;&nbsp; 이용 안내&nbsp;&nbsp; 로그인</p>
        <small>Concept v2 · 모든 상품명, 가격, 카피는 UI 검토용 샘플입니다.</small>
      </footer>
    </main>
  )
}
