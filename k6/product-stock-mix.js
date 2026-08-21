import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { BASE, HEADERS } from './config.js';

const stockInsufficient = new Rate('stock_insufficient_rate');
const stockServerError = new Rate('stock_server_error_rate');
const stockUnexpectedClientError = new Rate('stock_unexpected_client_error_rate');
// Workload-only series provide correct read/write aggregate percentiles. Operation
// tags remain on the standard HTTP series for reserve/release diagnostics.
const workloadDuration = new Trend('stock_mix_workload_duration', true);
const workloadFailure = new Rate('stock_mix_workload_failure');
const products = new SharedArray('product stock mix', () => JSON.parse(open('./seed/productIds.json')));
const runTag = __ENV.RUN_KEY || 'local';
const mysqlThreshold = __ENV.MYSQL_THRESHOLD_RAMP === 'true';
const mysqlThresholdLow = __ENV.MYSQL_THRESHOLD_LOW_RAMP === 'true';
const mysqlThresholdVeryLow = __ENV.MYSQL_THRESHOLD_VERY_LOW_RAMP === 'true';
const stockDistribution = __ENV.STOCK_MIX_DISTRIBUTION || 'uniform';
const readTargets = mysqlThresholdVeryLow ? [10, 25, 50, 75, 100]
  : mysqlThresholdLow ? [100, 125, 150, 175, 200]
  : mysqlThreshold ? [250, 300, 350, 400, 450, 500] : [500, 750, 1000, 1250];
const writeTargets = readTargets.map((target) => Math.round(target / 9));

if (!products.length || !products.every((product) => Number.isInteger(product.productId) && product.productId > 0 &&
  Number.isInteger(product.skuId) && product.skuId > 0)) {
  throw new Error('productIds.json must contain positive productId/skuId pairs');
}
if (stockDistribution !== 'uniform' && stockDistribution !== 'hot') {
  throw new Error('STOCK_MIX_DISTRIBUTION must be uniform or hot');
}

export function optionsForMode(mode = 'mixed') {
  const scenarios = {
    read: { executor: 'ramping-vus', exec: 'read', startVUs: readTargets[0], stages: readTargets.map((target) => ({ target, duration: '3m' })) },
    write: { executor: 'ramping-vus', exec: 'write', startVUs: writeTargets[0], stages: writeTargets.map((target) => ({ target, duration: '3m' })) },
  };
  if (mode === 'read') delete scenarios.write;
  else if (mode === 'write') delete scenarios.read;
  else if (mode !== 'mixed') throw new Error('STOCK_MIX_WORKLOAD must be mixed, read, or write');
  return {
    scenarios,
  thresholds: {
    stock_server_error_rate: ['rate==0'],
    stock_unexpected_client_error_rate: ['rate==0'],
  },
  };
};

export const options = optionsForMode(__ENV.STOCK_MIX_WORKLOAD || 'mixed');

export function uniquePaymentKey(iteration, run = runTag) {
  return `stock-mix-${run}-${__VU}-${iteration}`;
}

export function stockRequests(product, iteration) {
  const paymentKey = uniquePaymentKey(iteration);
  const bodies = {
    reserve: JSON.stringify({ paymentKey, items: [{ productId: product.productId, skuId: product.skuId, qty: 1 }] }),
    release: JSON.stringify({ paymentKey, items: [{ skuId: product.skuId, qty: 1 }] }),
  };
  return ['reserve', 'release'].map((operation) => ({
    operation,
    url: `${BASE.PRODUCT}/v1/stock/${operation}`,
    body: bodies[operation],
    params: { headers: HEADERS, tags: { operation, workload: 'write', run: runTag } },
  }));
}

export function selectProduct(vu, iteration, distribution = stockDistribution) {
  if (distribution === 'hot') return products[0];
  return products[(vu + iteration) % products.length];
}

function productForVu() {
  return selectProduct(__VU, __ITER);
}

export function writeOutcome(status) {
  if (status === 409) return 'insufficient';
  if (status >= 500) return 'server_error';
  if (status >= 400) return 'unexpected_client_error';
  return 'ok';
}

export function writeFailed(status) {
  return status !== 200 && status !== 409;
}

function addWorkloadResponse(res, workload, failed) {
  workloadDuration.add(res.timings.duration, { workload, run: runTag });
  workloadFailure.add(failed, { workload, run: runTag });
}

function addWriteStatus(res, operation) {
  const outcome = writeOutcome(res.status);
  stockInsufficient.add(outcome === 'insufficient', { operation });
  stockServerError.add(outcome === 'server_error', { operation });
  stockUnexpectedClientError.add(outcome === 'unexpected_client_error', { operation });
}

export function read() {
  const { productId } = productForVu();
  const res = http.get(`${BASE.PRODUCT}/v1/products/${productId}`, { tags: { operation: 'read', workload: 'read', run: runTag } });
  addWorkloadResponse(res, 'read', res.status !== 200);
}

export function write() {
  const [reserve, release] = stockRequests(productForVu(), __ITER);
  const reserved = http.post(reserve.url, reserve.body, reserve.params);
  addWriteStatus(reserved, reserve.operation);
  addWorkloadResponse(reserved, 'write', writeFailed(reserved.status));
  if (reserved.status === 200) {
    const released = http.post(release.url, release.body, release.params);
    addWriteStatus(released, release.operation);
    addWorkloadResponse(released, 'write', writeFailed(released.status));
  }
}
