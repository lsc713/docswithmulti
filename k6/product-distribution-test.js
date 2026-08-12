import { check } from 'k6';
import { selectProductId } from './helpers/product-distribution.js';

const ids = Array.from({ length: 100 }, (_, i) => i + 1);
const sequence = (...values) => () => values.shift();
export const options = { thresholds: { checks: ['rate==1'] } };

export default function () {
  check(null, {
    'hot stays in first ten': () => selectProductId(ids, 'hot', () => 0.99) === 10,
    'uniform spans all': () => selectProductId(ids, 'uniform', () => 0.99) === 100,
    'realistic 80 percent is hot': () => selectProductId(ids, 'realistic', sequence(0.79, 0.99)) === 10,
    'realistic tail spans all': () => selectProductId(ids, 'realistic', sequence(0.80, 0.99)) === 100,
  });
}
