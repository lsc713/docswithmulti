// k6 공통 설정 — TARGET 환경별 BASE URL 스위치
//
//   __ENV.TARGET = 'local' (기본, docker-compose) | 'aws' (infra/load-test 사설 IP)
//   개별 오버라이드: __ENV.PAYMENT_URL / ORDER_URL / MERCHANT_URL

const TARGET = __ENV.TARGET || 'local';

const PRESETS = {
  local: {
    PAYMENT:  'http://localhost:8080',
    ORDER:    'http://localhost:8081',
    MERCHANT: 'http://localhost:8082',
  },
  // infra/load-test/deploy 고정 사설 IP (payment=.20, cold-svc=.22)
  aws: {
    PAYMENT:  'http://10.0.1.20:8080',
    ORDER:    'http://10.0.1.22:8081',
    MERCHANT: 'http://10.0.1.22:8082',
  },
};

const preset = PRESETS[TARGET] || PRESETS.local;

export const BASE = {
  PAYMENT:  __ENV.PAYMENT_URL  || preset.PAYMENT,
  ORDER:    __ENV.ORDER_URL    || preset.ORDER,
  MERCHANT: __ENV.MERCHANT_URL || preset.MERCHANT,
};

export const HEADERS = { 'Content-Type': 'application/json' };

// HTTP 팩토리(buildPaymentPool) 소규모 시딩 시 pool 크기.
// 대규모 스테이지는 k6/seed/seed.sh (SQL bulk) 사용.
export const POOL_SIZE = 1500;
