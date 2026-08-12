export function selectProductId(ids, distribution, random = Math.random) {
  if (ids.length < 10) throw new Error(`상품 ID가 10개 미만입니다: ${ids.length}`);
  if (distribution === 'hot') return ids[Math.floor(random() * 10)];
  if (distribution === 'uniform') return ids[Math.floor(random() * ids.length)];
  if (distribution === 'realistic') {
    const size = random() < 0.8 ? 10 : ids.length;
    return ids[Math.floor(random() * size)];
  }
  throw new Error(`알 수 없는 DISTRIBUTION=${distribution}. 가능: hot, uniform, realistic`);
}
