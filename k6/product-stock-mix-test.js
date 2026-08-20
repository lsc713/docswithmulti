import { check } from 'k6';
import { options as mixOptions, stockRequests, uniquePaymentKey, writeOutcome } from './product-stock-mix.js';

export const options = {
  scenarios: { checks: { executor: 'shared-iterations', vus: 1, iterations: 1 } },
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const readTargets = mixOptions.scenarios.read.stages.map((stage) => stage.target);
  const writeTargets = mixOptions.scenarios.write.stages.map((stage) => stage.target);
  const requests = stockRequests({ productId: 1, skuId: 11 }, 1);

  check(null, {
    'read/write ramp ratio': () => JSON.stringify(readTargets) === '[500,750,1000,1250]' &&
      JSON.stringify(writeTargets) === '[56,83,111,139]',
    'payment key is unique per iteration': () => uniquePaymentKey(1) !== uniquePaymentKey(2),
    'stock shortage is not a server error': () => writeOutcome(409) === 'insufficient' &&
      writeOutcome(500) === 'server_error',
    'reserve is paired with release': () => requests.length === 2 &&
      requests[0].operation === 'reserve' && requests[1].operation === 'release' &&
      requests[0].body === requests[1].body &&
      requests[0].params.tags.operation === 'reserve' && requests[1].params.tags.operation === 'release',
  });
}
