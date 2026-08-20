import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate } from 'k6/metrics';
import { BASE } from './config.js';
import { selectProductId } from './helpers/product-distribution.js';

const products = new SharedArray('products', () => JSON.parse(open('./seed/productIds.json')));
const STAGE = __ENV.STAGE || 'baseline';
const DISTRIBUTION = __ENV.DISTRIBUTION || 'realistic';
const VUS = Number(__ENV.VUS || 10);
const DURATION = __ENV.DURATION || '3m';
const productDetailSuccess = new Rate('product_detail_success_rate');

const SCENARIOS = {
  smoke: { executor: 'shared-iterations', vus: 1, iterations: 20, maxDuration: '2m' },
  baseline: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  ramp: {
    executor: 'ramping-vus', startVUs: 10, gracefulRampDown: '10s',
    stages: [
      { target: 10, duration: '3m' },
      { target: 50, duration: '3m' },
      { target: 100, duration: '3m' },
    ],
  },
  stress: {
    executor: 'ramping-vus', startVUs: 50, gracefulRampDown: '10s',
    stages: [
      { target: 100, duration: '2m' },
      { target: 200, duration: '2m' },
      { target: 400, duration: '2m' },
    ],
  },
  soak: { executor: 'constant-vus', vus: 70, duration: '30m' },
};

if (!SCENARIOS[STAGE]) throw new Error(`알 수 없는 STAGE=${STAGE}. 가능: ${Object.keys(SCENARIOS).join(', ')}`);
if (!['hot', 'uniform', 'realistic'].includes(DISTRIBUTION)) {
  throw new Error(`알 수 없는 DISTRIBUTION=${DISTRIBUTION}. 가능: hot, uniform, realistic`);
}
if (products.length < 10 || !products.every((product) => Number.isInteger(product.productId) && product.productId > 0 &&
  Number.isInteger(product.skuId) && product.skuId > 0)) {
  throw new Error('상품 seed는 10개 이상의 양의 productId/skuId 쌍이어야 합니다');
}
const ids = products.map((product) => product.productId);

export function selectDetailProductId(distribution, random = Math.random) {
  return selectProductId(ids, distribution, random);
}

const strict = STAGE === 'baseline' || STAGE === 'smoke';
export const options = {
  scenarios: { [STAGE]: { ...SCENARIOS[STAGE], exec: 'detail', tags: { stage: STAGE, distribution: DISTRIBUTION } } },
  thresholds: strict
    ? {
        http_req_failed: ['rate<0.01'],
        product_detail_success_rate: ['rate>0.99'],
      }
    : {
        http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
        product_detail_success_rate: [{ threshold: 'rate>0.99', abortOnFail: false }],
      },
};

export function detail() {
  const id = selectDetailProductId(DISTRIBUTION);
  const res = http.get(`${BASE.PRODUCT}/v1/products/${id}`, {
    tags: { stage: STAGE, distribution: DISTRIBUTION },
  });
  const ok = check(res, {
    'HTTP 200': (r) => r.status === 200,
    'representative product shape': (r) => {
      try {
        const b = r.json();
        return b.id === id && b.category.length === 3 && b.images.length === 3 &&
          b.skus.length === 9 && b.variantOptions.length === 2 && b.specs.length === 2;
      } catch (_) { return false; }
    },
  });
  productDetailSuccess.add(ok, { distribution: DISTRIBUTION });
}
