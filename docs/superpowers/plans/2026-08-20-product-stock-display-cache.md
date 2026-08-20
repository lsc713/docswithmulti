# Product Stock Display Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 상세 본문 캐시에서 재고를 분리하고, 재고 변경 뒤 최신 품절 상태와 혼합 ramp 병목 지표를 제공한다.

**Architecture:** 기존 `ProductDetailCacheService`는 상품 본문·가격을 캐시한다. 새 `ProductStockSnapshotCacheService`가 상품별 SKU 가용 재고를 Redis에 짧게 보관하고, `ProductQueryService`가 응답 직전에 합성한다. `StockService`는 커밋 뒤에만 재고 snapshot을 갱신한다.

**Tech Stack:** Spring Boot, Spring transactions, Redisson, MySQL, Micrometer, React, Playwright, k6, Prometheus.

**Spec:** `docs/features/product-stock-display-cache/spec-fixed.md`, `docs/features/product-stock-display-cache/prd.md`, `docs/features/product-stock-display-cache/issues.md`

## Global Constraints

- `reserve`의 MySQL 조건부 차감과 `paymentKey` 멱등 규칙을 변경하지 않는다.
- 재고 cache write는 성공한 트랜잭션의 `AFTER_COMMIT`에서만 수행한다.
- Redis 재고 상태 오류는 MySQL fallback으로 처리하되 MySQL 오류는 숨기지 않는다.
- 혼합 ramp는 500→750→1,000→1,250 VU, 각 3분이며 읽기 90%, 예약·해제 10%다.
- 측정 인프라는 생성 전 Terraform plan, 종료 후 Terraform destroy를 실행한다.

---

### Task 1: 분리 재고 snapshot과 상품 상세 합성 (#127)

**Files:**
- Create: `product-service/src/main/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheService.java`
- Create: `product-service/src/main/java/com/example/product/application/service/ProductStockChangedEvent.java`
- Modify: `product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java`
- Modify: `product-service/src/main/java/com/example/product/application/service/ProductQueryService.java`
- Modify: `product-service/src/main/java/com/example/product/application/service/StockService.java`
- Create: `product-service/src/test/java/com/example/product/infrastructure/cache/ProductStockSnapshotCacheServiceTest.java`
- Create: `product-service/src/test/java/com/example/product/integration/ProductStockSnapshotIntegrationTest.java`

**Interfaces:**
- Consumes: `ProductQueryRepository.findSkuStock(Long)` and `StockService.reserve/release`.
- Produces: `ProductStockSnapshotCacheService.getOrLoad(Long): Map<Long, Integer>` and `refreshAfterCommit(Set<Long>): void`.

- [ ] **Step 1: Write failing cache tests**

```java
@Test
void cache_miss_reads_db_and_writes_snapshot() {
    when(repository.findSkuAvailability(10L)).thenReturn(Map.of(101L, 7));

    assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
    verify(repository).findSkuAvailability(10L);
}

@Test
void redis_failure_falls_back_to_db() {
    when(redisson.getBucket(anyString())).thenThrow(new RedisException("down"));
    when(repository.findSkuAvailability(10L)).thenReturn(Map.of(101L, 0));

    assertThat(service.getOrLoad(10L)).containsEntry(101L, 0);
}
```

- [ ] **Step 2: Run the failing tests**

Run: `./gradlew :product-service:test --tests '*ProductStockSnapshotCacheServiceTest'`

Expected: FAIL because `ProductStockSnapshotCacheService` and `findSkuAvailability` do not exist.

- [ ] **Step 3: Add the query, cache and response merge**

```java
public Map<Long, Integer> getOrLoad(Long productId) {
    try {
        Map<Long, Integer> cached = read(productId);
        if (cached != null) return cached;
    } catch (RuntimeException ignored) { }
    Map<Long, Integer> fresh = repository.findSkuAvailability(productId);
    write(productId, fresh);
    return fresh;
}

public ProductDetail detail(Long productId) {
    ProductDetail body = cacheService.getOrLoad(productId, ProductDetail.class,
            () -> loadDetail(productId));
    return withAvailability(body, stockSnapshotCacheService.getOrLoad(productId));
}
```

Use Redis key `product:stock:{productId}` and property `product.cache.stock-ttl-seconds` with default 5 seconds. Add Micrometer counters tagged `outcome=hit|miss|fallback|write`. `withAvailability` replaces only `SkuDetail.availableQty`; it preserves all other response fields and ordering.

- [ ] **Step 4: Refresh only after the database commit**

```java
public record ProductStockChangedEvent(Set<Long> productIds) { }

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void refresh(ProductStockChangedEvent event) {
    event.productIds().forEach(stockSnapshotCacheService::refresh);
}
```

Publish the event only for successful reserve/release mutations. A failed reservation and idempotent no-op release do not change the snapshot.

- [ ] **Step 5: Add integration assertions and verify**

```java
assertThat(productQueryService.detail(productId).skus())
    .filteredOn(sku -> sku.skuId().equals(skuId))
    .extracting(ProductQueryService.SkuDetail::availableQty)
    .containsExactly(0);
```

Cover reserve-to-zero, release-to-positive, failed reserve and Redis fallback. Run `./gradlew :product-service:test --tests '*ProductStockSnapshot*' --tests '*Stock*IntegrationTest'`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add product-service/src/main/java/com/example/product product-service/src/test/java/com/example/product
git commit -m "feat(product): separate stock snapshot cache"
```

### Task 2: 재고 부족 후 상세 UI 최신화 (#128)

**Files:**
- Modify: `frontend/src/api.js`
- Modify: `frontend/src/orderFlow.js`
- Modify: `frontend/src/components/ProductDetailDraft.jsx`
- Modify: `frontend/src/components/ProductDetailDraft.css`
- Modify: `frontend/e2e/product-detail-drafts.spec.js`
- Modify: `frontend/e2e/checkout.spec.js`

**Interfaces:**
- Consumes: `api.product(id)` and the existing order/payment stock-insufficiency response.
- Produces: a `refreshProduct(productId)` callback that replaces the displayed SKU list after stock failure.

- [ ] **Step 1: Write failing browser tests**

```javascript
await page.route('**/v1/products/42', async route => {
  await route.fulfill({ json: soldOutProduct })
})
await triggerStockInsufficientCheckout(page)
await expect(page.getByRole('status')).toContainText('방금 품절됨')
await expect(page.getByRole('radio', { name: /품절/ })).toBeDisabled()
```

Add a second test that refreshes to a lower quantity and asserts the quantity input equals the refreshed maximum.

- [ ] **Step 2: Run the failing test**

Run: `npm --prefix frontend run test:e2e -- product-detail-drafts.spec.js`

Expected: FAIL because checkout failure does not refetch detail data or render the stale-stock notice.

- [ ] **Step 3: Add the explicit recovery path**

```javascript
try {
  await onBuy(line)
} catch (error) {
  if (isStockInsufficient(error)) {
    setStockNotice('방금 품절됨')
    setProduct(await api.product(product.id))
    return
  }
  throw error
}
```

After refresh, clamp the selected quantity to `availableQty`; if it is zero, clear the selection and disable purchase. Preserve all non-stock checkout errors.

- [ ] **Step 4: Verify and commit**

Run: `npm --prefix frontend run test:unit && npm --prefix frontend run test:e2e -- product-detail-drafts.spec.js checkout.spec.js`

Expected: PASS.

```bash
git add frontend/src frontend/e2e
git commit -m "feat(frontend): refresh stale stock after checkout failure"
```

### Task 3: 읽기·쓰기 혼합 ramp와 병목 보고서 (#129)

**Files:**
- Modify: `k6/seed/product-detail-seed.sh`
- Modify: `k6/seed/product-detail-seed-test.sh`
- Create: `k6/product-stock-mix.js`
- Create: `k6/run-product-stock-mix-aws.sh`
- Create: `k6/product-stock-mix-test.js`
- Modify: `docs/load-test/product-detail-scaleout-results-2026-08-20.md`

**Interfaces:**
- Consumes: seed JSON `[{ "productId": 1, "skuId": 11 }]`, `POST /v1/stock/reserve`, `POST /v1/stock/release`, and `GET /v1/products/{id}`.
- Produces: tagged k6 read/write latency metrics and result artifacts for every ramp stage.

- [ ] **Step 1: Write the failing seed shape assertion**

```bash
jq -e 'length == 2 and all(.[]; (.productId|numbers) and (.skuId|numbers))' "$OUT" >/dev/null
```

Run: `k6/seed/product-detail-seed-test.sh`

Expected: FAIL because the current seed is an array of product IDs only.

- [ ] **Step 2: Emit one reservable SKU per product**

Change the final seed query to emit `productId` and one positive-stock `skuId`. Preserve the existing 9-SKU product shape assertion.

- [ ] **Step 3: Write the mixed k6 scenario**

```javascript
export const options = {
  scenarios: {
    read: { executor: 'ramping-vus', exec: 'read', startVUs: 500,
      stages: [{ target: 500, duration: '3m' }, { target: 750, duration: '3m' },
               { target: 1000, duration: '3m' }, { target: 1250, duration: '3m' }] },
    write: { executor: 'ramping-vus', exec: 'write', startVUs: 56,
      stages: [{ target: 56, duration: '3m' }, { target: 83, duration: '3m' },
               { target: 111, duration: '3m' }, { target: 139, duration: '3m' }] },
  },
}
```

Tag requests with `operation=read|reserve|release`. Every write creates a unique `paymentKey`, reserves one SKU, then releases the same reservation after success. Keep expected stock-insufficient 4xx separate from server errors.

- [ ] **Step 4: Add local scenario checks**

```javascript
export function uniquePaymentKey(iteration) {
  return `stock-mix-${__VU}-${iteration}`
}

if (uniquePaymentKey(1) === uniquePaymentKey(2)) throw new Error('payment key collision')
```

Run: `k6 run k6/product-stock-mix-test.js`

Expected: PASS for read/write ratio, unique keys and reserve→release pairing.

- [ ] **Step 5: Add the AWS runner and observation query**

Reuse the result bundle/checksum flow from `k6/run-product-detail-aws.sh`. Pass `PROM_URL`, save the exact UTC start/end timestamps, and query each interval for CPU/memory of k6, every Product instance, MySQL and Redis; `mysql_global_status_threads_running`; and stock snapshot cache outcome counters.

- [ ] **Step 6: Run the private-AWS ramp and record the first bottleneck**

Run locally: `bash k6/seed/product-detail-seed-test.sh` and `k6 run k6/product-stock-mix-test.js`.

After Terraform apply and readiness, run: `REPO_REF=<full-sha> ./k6/run-product-stock-mix-aws.sh`.

Append a table to `docs/load-test/product-detail-scaleout-results-2026-08-20.md` with VU, read/write RPS, read/write p95/p99, error rates, per-instance CPU/memory, MySQL threads, Redis cache outcomes and the first saturated component.

- [ ] **Step 7: Commit**

```bash
git add k6 docs/load-test/product-detail-scaleout-results-2026-08-20.md
git commit -m "test(load): add product stock mixed ramp"
```

## Plan self-review

- Spec coverage: Task 1 covers split cache, commit-only updates, fallback and cache observability; Task 2 covers buyer stale recovery; Task 3 covers the required mixed ramp and bottleneck evidence.
- Placeholders: none.
- Interface consistency: Task 1 supplies the response and metrics consumed by Tasks 2 and 3; Task 3 uses the stable Product, reserve and release APIs.
