import { check } from 'k6';
import { options as mixOptions, optionsForMode, selectProduct, selectProducts, stockRequests, uniquePaymentKey, writeFailed, writeOutcome } from './product-stock-mix.js';

export const options = {
  scenarios: { checks: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const readTargets = mixOptions.scenarios.read.stages.map((stage) => stage.target);
  const writeTargets = mixOptions.scenarios.write.stages.map((stage) => stage.target);
  const { read, write } = mixOptions.scenarios;
  const selected = selectProduct(1, 0);
  const requests = stockRequests({ productId: 1, skuId: 11 }, 1);
  const reserveBody = JSON.parse(requests[0].body);
  const releaseBody = JSON.parse(requests[1].body);
  const batch = selectProducts(1, 0, 10, 'uniform');
  const batchRequests = stockRequests(batch, 2);
  const batchReserveBody = JSON.parse(batchRequests[0].body);
  const batchReleaseBody = JSON.parse(batchRequests[1].body);

  check(null, {
    'read/write ramp ratio': () => JSON.stringify(readTargets) === '[500,750,1000,1250]' &&
      JSON.stringify(writeTargets) === '[56,83,111,139]',
    'read-only mode enables only the read ramp': () => Object.keys(optionsForMode('read').scenarios).join(',') === 'read',
    'write-only mode enables only the write ramp': () => Object.keys(optionsForMode('write').scenarios).join(',') === 'write',
    'ramp starts and stages last exactly three minutes': () => read.startVUs === 500 && write.startVUs === 56 &&
      read.stages.every((stage) => stage.duration === '3m') && write.stages.every((stage) => stage.duration === '3m'),
    'selection reads a seeded product pair': () => Number.isInteger(selected.productId) && selected.productId > 0 &&
      Number.isInteger(selected.skuId) && selected.skuId > 0,
    'hot distribution always selects one seeded sku': () =>
      selectProduct(1, 0, 'hot').skuId === selectProduct(11, 999, 'hot').skuId,
    'payment key is unique per iteration': () => uniquePaymentKey(1) !== uniquePaymentKey(2),
    'payment key is unique per run': () => uniquePaymentKey(1, 'run-a') !== uniquePaymentKey(1, 'run-b'),
    'stock shortage is not a server error': () => writeOutcome(409) === 'insufficient' &&
      writeOutcome(500) === 'server_error',
    'write failure excludes expected stock shortage': () => !writeFailed(200) && !writeFailed(409) &&
      writeFailed(0) && writeFailed(400) && writeFailed(500),
    'unexpected stock client errors fail the run': () =>
      JSON.stringify(mixOptions.thresholds.stock_unexpected_client_error_rate) === '["rate==0"]',
    'reserve is paired with release': () => requests.length === 2 &&
      requests[0].operation === 'reserve' && requests[1].operation === 'release' &&
      reserveBody.paymentKey === releaseBody.paymentKey && reserveBody.items.length === 1 && releaseBody.items.length === 1 &&
      reserveBody.items[0].productId === 1 && reserveBody.items[0].skuId === 11 && reserveBody.items[0].qty === 1 &&
      releaseBody.items[0].skuId === 11 && releaseBody.items[0].qty === 1 && !('productId' in releaseBody.items[0]) &&
      requests[0].params.tags.operation === 'reserve' && requests[1].params.tags.operation === 'release',
    'multi-item reserve and release use the same distinct skus': () => batchReserveBody.items.length === 10 &&
      batchReleaseBody.items.length === 10 &&
      new Set(batchReserveBody.items.map((item) => item.skuId)).size === 10 &&
      JSON.stringify(batchReserveBody.items.map((item) => item.skuId)) ===
        JSON.stringify(batchReleaseBody.items.map((item) => item.skuId)),
  });
}
