import { check } from 'k6';
import { options as mixOptions, selectProduct, stockRequests, uniquePaymentKey, writeOutcome } from './product-stock-mix.js';

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

  check(null, {
    'read/write ramp ratio': () => JSON.stringify(readTargets) === '[500,750,1000,1250]' &&
      JSON.stringify(writeTargets) === '[56,83,111,139]',
    'ramp starts and stages last exactly three minutes': () => read.startVUs === 500 && write.startVUs === 56 &&
      read.stages.every((stage) => stage.duration === '3m') && write.stages.every((stage) => stage.duration === '3m'),
    'selection reads a seeded product pair': () => Number.isInteger(selected.productId) && selected.productId > 0 &&
      Number.isInteger(selected.skuId) && selected.skuId > 0,
    'payment key is unique per iteration': () => uniquePaymentKey(1) !== uniquePaymentKey(2),
    'stock shortage is not a server error': () => writeOutcome(409) === 'insufficient' &&
      writeOutcome(500) === 'server_error',
    'unexpected stock client errors fail the run': () =>
      JSON.stringify(mixOptions.thresholds.stock_unexpected_client_error_rate) === '["rate==0"]',
    'reserve is paired with release': () => requests.length === 2 &&
      requests[0].operation === 'reserve' && requests[1].operation === 'release' &&
      reserveBody.paymentKey === releaseBody.paymentKey && reserveBody.items.length === 1 && releaseBody.items.length === 1 &&
      reserveBody.items[0].productId === 1 && reserveBody.items[0].skuId === 11 && reserveBody.items[0].qty === 1 &&
      releaseBody.items[0].skuId === 11 && releaseBody.items[0].qty === 1 && !('productId' in releaseBody.items[0]) &&
      requests[0].params.tags.operation === 'reserve' && requests[1].params.tags.operation === 'release',
  });
}
