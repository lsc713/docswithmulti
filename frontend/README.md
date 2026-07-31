# React + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend using TypeScript with type-aware lint rules enabled. Check out the [TS template](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react-ts) for information on how to integrate TypeScript and Oxlint's TypeScript related rules in your project.

## 수동 검증 (백엔드 2개 기동 필요)

백엔드 2개 기동: `./gradlew :user-service:bootRun` + `./gradlew :api-gateway:bootRun` (+ user-service MySQL). 프론트 `npm run dev`.

1. [ ] 회원가입 → "안녕하세요 X님" 표시 (Network: login 응답에 `Set-Cookie: access_token=...; HttpOnly` 확인).
2. [ ] DevTools Application→Cookies: `access_token`/`refresh_token`은 HttpOnly=✓, `csrf_token`은 HttpOnly=✗.
3. [ ] Console에서 `document.cookie` — access/refresh 토큰이 **안 보임**(httpOnly), csrf만 보임.
4. [ ] 로그아웃 → Network에 `X-CSRF-Token` 헤더 존재, 200, 쿠키 만료(Max-Age=0).
5. [ ] CSRF 음성 확인: DevTools에서 csrf 헤더 없이 logout POST 재현 시 403.
6. [ ] CORS 확인: 다른 출처(예: `http://127.0.0.1:5500`)에서 호출 시 브라우저가 차단(화이트리스트에 없음).
