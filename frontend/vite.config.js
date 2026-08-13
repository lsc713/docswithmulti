import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { resolveApiBaseUrl } from './src/api-base.js'

// 별도 엔트리(admin.html)에 /admin/* 클린 URL 유지 — dev 서버에서 네비게이션 요청을 admin.html로 rewrite.
// (prod 정적 호스팅 시 동일 rewrite 필요 — 현재는 dev 서버로 구동.)
const adminHtmlRewrite = {
  name: 'admin-html-rewrite',
  configureServer(server) {
    server.middlewares.use((req, _res, next) => {
      if (req.url === '/admin' || req.url.startsWith('/admin/')) req.url = '/admin.html'
      next()
    })
  },
}

export default defineConfig(({ mode }) => {
  const apiOrigin = new URL(resolveApiBaseUrl(loadEnv(mode, process.cwd(), 'VITE_').VITE_API_BASE_URL)).origin
  return {
    plugins: [react(), adminHtmlRewrite, {
      name: 'api-csp',
      transformIndexHtml: html => html.replace(
        "connect-src 'self' http://localhost:8000",
        `connect-src 'self' ${apiOrigin}`,
      ),
    }],
    server: { port: 5173, strictPort: true },  // dev proxy 의도적으로 없음 — CORS 정식 경로 검증
    build: {
      rollupOptions: {
        input: { main: 'index.html', admin: 'admin.html' },
      },
    },
  }
})
