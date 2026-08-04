export default function NavBar({ me, onHome, onLoginClick, onLogout, cartCount, onCart }) {
  return (
    <nav className="navbar">
      <button className="navbar-brand" onClick={onHome}>fashion-shop</button>
      <div className="navbar-right">
        {me
          ? <>
              <span>{me.name}님</span>
              <button onClick={onLogout}>로그아웃</button>
              <button onClick={onCart}>장바구니({cartCount})</button>
            </>
          : <button onClick={onLoginClick}>로그인</button>}
      </div>
    </nav>
  )
}
