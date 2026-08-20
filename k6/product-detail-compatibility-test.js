import { check } from 'k6';
import { selectDetailProductId } from './product-detail.js';

export const options = { scenarios: { checks: { executor: 'shared-iterations', vus: 1, iterations: 1 } }, thresholds: { checks: ['rate==1'] } };

export default function () {
  check(null, {
    'read-only selection accepts object seed pairs': () => selectDetailProductId('hot', () => 0.99) > 0,
  });
}
