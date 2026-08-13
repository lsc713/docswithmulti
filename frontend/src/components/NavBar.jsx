import { useEffect, useRef, useState } from 'react'

export default function NavBar({ home, me, onHome, onLoginClick, onLogout, cartCount, onCart, onHistory,
  productQuery, onProductQueryChange }) {
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false)
  const searchInput = useRef(null)

  useEffect(() => {
    if (mobileSearchOpen) searchInput.current?.focus()
  }, [mobileSearchOpen])

  return (
    <header className={`site-header${home ? ' home-header' : ''}${mobileSearchOpen ? ' search-open' : ''}`}>
      {home && <div className="announcement">UI CONCEPT · SAMPLE COLLECTION</div>}
      <nav className="navbar" aria-label="주요 메뉴">
        <button className="navbar-brand" onClick={onHome}>fashion-shop</button>
        <div className="navbar-right">
          {home && <>
            <div className="desktop-sections" aria-hidden="true">NEW&nbsp;&nbsp; WOMEN&nbsp;&nbsp; MEN&nbsp;&nbsp; EDIT</div>
            <label className={`search-field${mobileSearchOpen ? ' mobile-open' : ''}`}>
              <span className="sr-only">상품 검색</span>
              <input ref={searchInput} type="search" placeholder="상품 검색" value={productQuery}
                     onChange={event => onProductQueryChange(event.target.value)} />
            </label>
            <button type="button" className="mobile-search" aria-label="상품 검색"
                    aria-expanded={mobileSearchOpen} onClick={() => setMobileSearchOpen(open => !open)}>
              {mobileSearchOpen ? '×' : '⌕'}
            </button>
          </>}
          {me
            ? <>
                <span>{me.name}님</span>
                <button className={home ? 'header-link' : undefined} onClick={onHistory}>주문내역</button>
                <button className={home ? 'header-link' : undefined} onClick={onLogout}>로그아웃</button>
                <button className={home ? 'header-link' : undefined} onClick={onCart}>장바구니({cartCount})</button>
              </>
            : <button className={home ? 'login-button' : undefined} onClick={onLoginClick}>로그인</button>}
        </div>
      </nav>
    </header>
  )
}
