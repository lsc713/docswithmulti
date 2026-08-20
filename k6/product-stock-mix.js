import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Rate } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

const stockInsufficient = new Rate('stock_insufficient_rate');
const stockServerError = new Rate('stock_server_error_rate');
const stockUnexpectedClientError = new Rate('stock_unexpected_client_error_rate');
let products;

export const options = {
  scenarios: {
    read: { executor: 'ramping-vus', exec: 'read', startVUs: 500,
      stages: [{ target: 500, duration: '3m' }, { target: 750, duration: '3m' },
        { target: 1000, duration: '3m' }, { target: 1250, duration: '3m' }] },
    write: { executor: 'ramping-vus', exec: 'write', startVUs: 56,
      stages: [{ target: 56, duration: '3m' }, { target: 83, duration: '3m' },
        { target: 111, duration: '3m' }, { target: 139, duration: '3m' }] },
  },
  thresholds: { stock_server_error_rate: ['rate==0'] },
};

export function uniquePaymentKey(iteration) {
  return `stock-mix-${__VU}-${iteration}`;
}

export function stockRequests(product, iteration) {
  const body = JSON.stringify({ paymentKey: uniquePaymentKey(iteration), items: [{ skuId: product.skuId, qty: 1 }] });
  return ['reserve', 'release'].map((operation) => ({
    operation,
    url: `${BASE.PRODUCT}/v1/stock/${operation}`,
    body,
    params: { headers: HEADERS, tags: { operation } },
  }));
}

function productForVu() {
  if (!products) {
    products = new SharedArray('product stock mix', () => JSON.parse(open('./seed/productIds.json')));
    if (!products.length || !products.every((product) => Number.isInteger(product.productId) && product.productId > 0 &&
      Number.isInteger(product.skuId) && product.skuId > 0)) {
      throw new Error('productIds.json must contain positive productId/skuId pairs');
    }
  }
  return products[(__VU + __ITER) % products.length];
}

export function writeOutcome(status) {
  if (status === 409) return 'insufficient';
  if (status >= 500) return 'server_error';
  if (status >= 400) return 'unexpected_client_error';
  return 'ok';
}

function addWriteStatus(res, operation) {
  const outcome = writeOutcome(res.status);
  stockInsufficient.add(outcome === 'insufficient', { operation });
  stockServerError.add(outcome === 'server_error', { operation });
  stockUnexpectedClientError.add(outcome === 'unexpected_client_error', { operation });
}

export function read() {
  const { productId } = productForVu();
  http.get(`${BASE.PRODUCT}/v1/products/${productId}`, { tags: { operation: 'read' } });
}

export function write() {
  const [reserve, release] = stockRequests(productForVu(), __ITER);
  const reserved = http.post(reserve.url, reserve.body, reserve.params);
  addWriteStatus(reserved, reserve.operation);
  if (reserved.status === 200) {
    addWriteStatus(http.post(release.url, release.body, release.params), release.operation);
  }
}
