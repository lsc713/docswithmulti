// k6 공통 설정 — docker-compose 기준 localhost 포트

export const BASE = {
  PAYMENT:  'http://localhost:8080',
  ORDER:    'http://localhost:8081',
  MERCHANT: 'http://localhost:8082',
};

export const HEADERS = { 'Content-Type': 'application/json' };

// load-test.js pool 크기:
// 50 VU × 30s, 평균 응답 0.5s → VU당 최대 60 iter → 50 × 30 = 1500
// 배치 50개씩 30회 → setup 약 30~40초
export const POOL_SIZE = 1500;
