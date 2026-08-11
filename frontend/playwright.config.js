import { defineConfig } from '@playwright/test'

// 실 스택 대상 E2E: 백엔드(user-service:8085 + api-gateway:8000) + 프론트(:5173)가
// 이미 떠 있어야 한다. dev proxy 없음 → 브라우저가 :5173 → :8000 실제 cross-origin 호출(CORS 검증).
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.js',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5173',
    // 기본은 headless. HEADED=1 로 창 표시, SLOWMO=ms 로 동작을 천천히.
    headless: !process.env.HEADED,
    launchOptions: { slowMo: process.env.SLOWMO ? Number(process.env.SLOWMO) : 0 },
  },
  reporter: [['list']],
})
