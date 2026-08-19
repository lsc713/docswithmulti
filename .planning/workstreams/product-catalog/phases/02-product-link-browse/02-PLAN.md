---
phase: 02-product-link-browse
plan: 02
type: execute
wave: 1
depends_on: [01-01]
requirements: [PLINK-01, PLINK-02, BROWSE-01, BROWSE-02, INV-01]
files_modified:
  # created
  - product-service/src/main/resources/db/migration/V4__link_product_category.sql
  - product-service/src/main/java/com/example/product/application/service/ProductQueryService.java
  - product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java
  - product-service/src/main/java/com/example/product/presentation/controller/ProductQueryController.java
  - product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java
  - product-service/src/main/java/com/example/product/presentation/dto/ProductListResponse.java
  - product-service/src/main/java/com/example/product/common/exception/application/ProductCategoryInvalidException.java
  - product-service/src/main/java/com/example/product/common/exception/application/ProductNotFoundException.java
  - product-service/src/test/java/com/example/product/integration/ProductBrowseIntegrationTest.java
  - product-service/src/test/java/com/example/product/integration/ProductCategoryMigrationTest.java
  # modified
  - product-service/src/main/java/com/example/product/domain/entity/Product.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaEntity.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java
  - product-service/src/main/java/com/example/product/application/service/CatalogService.java
  - product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java
  - product-service/src/main/java/com/example/product/presentation/controller/ProductController.java
  - product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
  - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
autonomous: true

must_haves:
  truths:
    - "POST /v1/products with a leaf (level 3) categoryId registers the product; a non-leaf or missing categoryId returns 400 (PLINK-01)"
    - "After V4 (add nullable column → idempotent 미분류 대>중>소 backfill → NOT NULL + FK + index), the app boots (Hibernate validate passes on the mapped category_id); the populated-backfill path is proven by ProductCategoryMigrationTest — legacy orphan rows land on a level-3 '미분류' leaf, chain built exactly once (PLINK-02)"
    - "GET /v1/categories/{id}/products aggregates products of all descendant leaves for a 대/중 node, newest-first, paged; unknown category id → 404 (BROWSE-01)"
    - "GET /v1/products/{id} returns category path (대/중/소) + SKU list (skuCode, optionSummary) + availableQty from a read-only product_stock join; unknown product id → 404 (BROWSE-02)"
    - "Stock reserve/release/cancel-restore code and product_stock/stock_reservation write logic are unchanged; only V4 is added under db/migration (INV-01)"
  artifacts:
    - "product-service/src/main/resources/db/migration/V4__link_product_category.sql"
    - "product-service/.../application/service/ProductQueryService.java"
    - "product-service/.../presentation/controller/ProductQueryController.java"
    - "product-service/src/test/.../integration/ProductBrowseIntegrationTest.java"
    - "product-service/src/test/.../integration/ProductCategoryMigrationTest.java"
  key_links:
    - "product.category_id → category.id (leaf, level 3) via fk_product_category — the linkage BROWSE-01/02 read"
    - "recursive CTE over category(parent_id) → descendant leaf ids → product.category_id IN (...) — the aggregation path"
    - "product_stock read-only join keyed by sku_id → availableQty — must never enter a write path (INV-01)"
---

<objective>
Link products to leaf categories and deliver category-based browsing on top of the Phase 1 taxonomy. Migration V4 adds `product.category_id` (nullable → idempotent 미분류 backfill → NOT NULL+FK), product registration requires a leaf category, and two read endpoints (category-scoped list with recursive aggregation, product detail with category path + SKU + availableQty) complete the "browse by category" vertical. The stock lifecycle is touched only through a new read-only join.

Purpose: complete the first vertical slice of the product-catalog milestone — a user can browse products by any category node and open a product detail.
Output: V4 migration, category_id on Product, extended seed (PLINK-01), GET /v1/categories/{id}/products (BROWSE-01), GET /v1/products/{id} (BROWSE-02), and an INV-01 invariance gate.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@docs/superpowers/specs/2026-07-31-product-category-taxonomy-design.md
@.planning/workstreams/product-catalog/ROADMAP.md
@.planning/workstreams/product-catalog/phases/01-category-taxonomy/01-SUMMARY.md

# Phase 1 category stack is the freshest exemplar — same module, same hexagonal shape:
@product-service/src/main/java/com/example/product/domain/entity/Category.java
@product-service/src/main/java/com/example/product/application/service/CategoryService.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java
@product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
@product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
@product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java

# Existing product/stock code to extend or read-only-join:
@product-service/src/main/java/com/example/product/application/service/CatalogService.java
@product-service/src/main/java/com/example/product/presentation/controller/ProductController.java
@product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java
@product-service/src/main/java/com/example/product/domain/entity/Product.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaEntity.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProductStockJpaRepository.java
@product-service/src/main/resources/db/migration/V1__create_product_core.sql
@product-service/src/main/resources/db/migration/V3__create_category.sql

# Boot 4.0.5 test harness (no TestRestTemplate / @AutoConfigureMockMvc):
# use MockMvcBuilders.webAppContextSetup(ctx) exactly as CategoryTaxonomyIntegrationTest does.
</context>

<interface_context>
Existing signatures the tasks build on (do not restate implementations):
- Category domain: `Category.reconstruct(id, parentId, name, level, createdAt)`, `getParentId()`, `getLevel()`, `isLeaf()` (level==3). Product must be on a leaf.
- `CategoryRepository` port: `save`, `findById(Long)`, `findAll()`. Reuse `findById` for leaf validation and ancestry walk.
- `CatalogService.seed(String name, List<SkuSeed> skus)` returns `SeedResult(productId, skus)`. SkuSeed = (skuCode, optionSummary, initialStock).
- `Product.create(String name)` / `Product.reconstruct(id, name, createdAt, updatedAt)` — POJO, no Spring/JPA annotations (domain rule).
- `ProductJpaEntity` maps table `product`; adding a mapped `category_id` column is what makes "app boots (Hibernate validate)" true after V4.
- Persistence wiring pattern: port bean constructed in `PersistenceConfig` from a `*JpaRepository`.
- `product_stock` (PK sku_id, available_qty) — READ ONLY here. `ProductStockJpaRepository.tryReserve/restore` are the write path and MUST NOT be called or changed.
- ErrorCode enum is the single source of error codes; add new entries there, throw `BusinessException` subclasses (see `application/CategoryNotFoundException`).
</interface_context>

<tasks>

<task type="tracer">
  <name>Task 1 (tracer): product↔leaf-category link end-to-end — register under a leaf, read it back as detail</name>
  <files>
    product-service/src/main/resources/db/migration/V4__link_product_category.sql,
    product-service/src/main/java/com/example/product/domain/entity/Product.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaEntity.java,
    product-service/src/main/java/com/example/product/application/service/CatalogService.java,
    product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java,
    product-service/src/main/java/com/example/product/presentation/controller/ProductController.java,
    product-service/src/main/java/com/example/product/common/exception/ErrorCode.java,
    product-service/src/main/java/com/example/product/common/exception/application/ProductCategoryInvalidException.java,
    product-service/src/main/java/com/example/product/common/exception/application/ProductNotFoundException.java,
    product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java,
    product-service/src/main/java/com/example/product/application/service/ProductQueryService.java,
    product-service/src/main/java/com/example/product/presentation/controller/ProductQueryController.java,
    product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java,
    product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java,
    product-service/src/test/java/com/example/product/integration/ProductBrowseIntegrationTest.java,
    product-service/src/test/java/com/example/product/integration/ProductCategoryMigrationTest.java
  </files>
  <action>
    Wire ONE vertical path proving PLINK-01, PLINK-02, and BROWSE-02: register a product under a level-3 leaf category, then GET /v1/products/{id} and read back its category path + SKU + availableQty.

    MIGRATION V4 (PLINK-02) — file `V4__link_product_category.sql`, three explicit steps in order. Must be V4 (V3 = category). Must be safe on a product table that may already hold rows from prior seeds/tests.
      Step 1 — add nullable column: `ALTER TABLE product ADD COLUMN category_id BIGINT NULL;`
      Step 2 — idempotent backfill, only when legacy rows exist. Create a 미분류 대>중>소 chain, then point orphan products at the leaf. MySQL 8: a bare `SELECT const WHERE ...` is invalid — every guarded insert uses `... FROM DUAL WHERE ...`. The `category` table's sibling-unique key is on the STORED generated column `parent_key = IFNULL(parent_id,0)` (see V3), so match roots with `parent_key = 0`. Use session `@vars` to carry ids between statements (Flyway runs the file on one connection).
        CRITICAL — ALL THREE inserts (root, mid, leaf) must carry BOTH guards: `EXISTS (SELECT 1 FROM product WHERE category_id IS NULL)` AND the per-level `NOT EXISTS`. If only the root carries the `EXISTS(product…)` guard, then on an empty product table the root is skipped (@root=NULL) but the mid/leaf `NOT EXISTS` guards match nothing → both insert `(parent_id=NULL, level 2/3, '미분류')`, and the leaf collides with the mid on `uk_category_parent_name(parent_key=0, name='미분류')` → duplicate-key → V4 fails at startup → whole suite errors. Guarding all three on `EXISTS(product…)` makes empty-DB fire nothing.
        `INSERT INTO category (parent_id, name, level) SELECT NULL, '미분류', 1 FROM DUAL WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL) AND NOT EXISTS (SELECT 1 FROM category WHERE parent_key = 0 AND name = '미분류');`
        `SET @root = (SELECT id FROM category WHERE parent_key = 0 AND name = '미분류');`
        `INSERT INTO category (parent_id, name, level) SELECT @root, '미분류', 2 FROM DUAL WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL) AND @root IS NOT NULL AND NOT EXISTS (SELECT 1 FROM category WHERE parent_id = @root AND name = '미분류');`
        `SET @mid = (SELECT id FROM category WHERE parent_id = @root AND name = '미분류');`
        `INSERT INTO category (parent_id, name, level) SELECT @mid, '미분류', 3 FROM DUAL WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL) AND @mid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM category WHERE parent_id = @mid AND name = '미분류');`
        `SET @leaf = (SELECT id FROM category WHERE parent_id = @mid AND name = '미분류');`
        `UPDATE product SET category_id = @leaf WHERE category_id IS NULL AND @leaf IS NOT NULL;`
      Step 3 — constrain: `ALTER TABLE product MODIFY category_id BIGINT NOT NULL, ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id), ADD KEY idx_product_category (category_id);`
      Note: on a fresh DB (tests) there are zero orphan product rows, so all three guarded inserts fire nothing, @root/@mid/@leaf stay NULL, the final UPDATE no-ops, and Step 3's NOT NULL is trivially satisfied — the chain never pollutes the tree unless real legacy rows exist. On a populated DB the chain builds once and re-runs are no-ops via the NOT-EXISTS guards.

    DOMAIN + MAPPING: add `categoryId` (Long) to `Product`; `Product.create(String name, Long categoryId)` and `Product.reconstruct(..., categoryId, ...)`. Map `category_id` in `ProductJpaEntity` (`@Column(name="category_id", nullable=false)`) — this mapped column is what makes Hibernate schema-validate pass post-V4 (the PLINK-02 "app boots" criterion). Keep Product a POJO (no Spring/JPA).

    PLINK-01 (write): `SeedRequest` gains `@NotNull Long categoryId`. `ProductController` passes it through. `CatalogService.seed` takes `categoryId`, injects `CategoryRepository`, and validates: `findById(categoryId)` present AND `isLeaf()` (level 3) — else throw `ProductCategoryInvalidException` (400). Per spec §5 both "missing" and "non-leaf" map to the same 400. Pass categoryId into `Product.create`.

    BROWSE-02 (read): `ProductQueryService.detail(Long productId)` →
      - load product; absent → `ProductNotFoundException` (404).
      - category path 대/중/소: walk ancestry from `product.categoryId` up via `CategoryRepository.findById` (leaf → parent → parent; exactly 3 nodes), emit ordered root→leaf.
      - SKU list + availableQty: a NEW read-only native query on `ProductSkuJpaRepository` — `SELECT s.sku_code, s.option_summary, st.available_qty FROM product_sku s JOIN product_stock st ON st.sku_id = s.id WHERE s.product_id = :productId` returning a projection (interface projection or record via `ProductQueryRepositoryImpl`). This is the ONLY product_stock access — read only, never tryReserve/restore.
      `ProductQueryRepository` port exposes what the service needs (product-by-id, sku+stock rows); `ProductQueryRepositoryImpl` delegates to the JpaRepositories; wire the bean in `PersistenceConfig`. `ProductDetailResponse` shape: `{ id, name, category:[{level,id,name}... root→leaf], skus:[{skuCode, optionSummary, availableQty}] }`. New `ProductQueryController` GET `/v1/products/{id}` (keep the existing POST-only `ProductController` for writes).

    New ErrorCodes: `PRODUCT_CATEGORY_INVALID("PRODUCT_001", 400, ...)`, `PRODUCT_NOT_FOUND("PRODUCT_002", 404, ...)`. Do NOT reuse CATEGORY_NOT_FOUND (404) for seed validation — spec §5 mandates 400 there.

    TEST (`ProductBrowseIntegrationTest`): copy the Testcontainers + `MockMvcBuilders.webAppContextSetup(ctx)` harness from `CategoryTaxonomyIntegrationTest` verbatim (Boot 4 — no TestRestTemplate). Tracer cases: (a) build 대>중>소 via POST /v1/categories, POST /v1/products with the leaf id + one SKU(initialStock N) → 200; (b) GET /v1/products/{id} → 200 with 3-node category path (root→leaf) and skus[0].availableQty == N; (c) POST /v1/products with the 중분류 (non-leaf) id → 400 PRODUCT_001; (d) GET /v1/products/999999 → 404 PRODUCT_002; (e) PLINK-01 400 coverage — POST /v1/products with NO categoryId field → 400 (code INVALID_REQUEST via GlobalExceptionHandler @NotNull, assert status only); POST with categoryId 999999 (non-existent) → 400 PRODUCT_001 (assert code — deterministic).

    MIGRATION TEST (`ProductCategoryMigrationTest`, covers PLINK-02 populated-backfill — the branch the app's own startup Flyway never exercises because startup runs V4 on an empty DB). This is the test that would have caught the empty-vs-populated backfill collision. Do NOT use @SpringBootTest (its Flyway auto-runs to latest at startup). Instead: raw `MySQLContainer` + programmatic Flyway. Flyway is already a dependency.
      - `Flyway.configure().dataSource(jdbcUrl,user,pass).target("3").load().migrate()` → schema at V1–V3 (product without category_id, category empty).
      - via JDBC insert 2 orphan products: `INSERT INTO product(name) VALUES ('legacy-a'),('legacy-b')`.
      - `Flyway.configure()...target("4").load().migrate()` → runs V4.
      - Assert: (1) both legacy rows now have `category_id` pointing at a `level=3` category named '미분류'; (2) the 미분류 chain exists exactly once — one row per level 1/2/3 with name '미분류' (parent_key/parent_id linked 대>중>소); (3) `category_id` is NOT NULL on both rows.
      Optionally re-run `migrate()` (already at V4 → no-op) to sanity-check the Flyway checksum is stable. This gives criterion 2 a real observable test rather than a deferred gap.
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test --tests 'com.example.product.integration.ProductBrowseIntegrationTest' --tests 'com.example.product.integration.ProductCategoryMigrationTest'</automated>
  </verify>
  <done>V4 applies cleanly (Flyway); app boots with mapped category_id; populated-backfill test proves legacy rows land on a level-3 '미분류' leaf with the chain built exactly once; product registers only under a leaf (non-leaf/missing/non-existent → 400, PRODUCT_001 where deterministic); GET /v1/products/{id} returns category path + SKU + availableQty (read-only stock join); unknown product → 404 PRODUCT_002.</done>
</task>

<task type="auto">
  <name>Task 2 (expansion): GET /v1/categories/{id}/products — recursive descendant aggregation + paging</name>
  <files>
    product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaRepository.java,
    product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java,
    product-service/src/main/java/com/example/product/application/service/ProductQueryService.java,
    product-service/src/main/java/com/example/product/presentation/controller/ProductQueryController.java,
    product-service/src/main/java/com/example/product/presentation/dto/ProductListResponse.java,
    product-service/src/test/java/com/example/product/integration/ProductBrowseIntegrationTest.java
  </files>
  <action>
    BROWSE-01: add GET `/v1/categories/{id}/products?page=&size=` to `ProductQueryController` (defaults page=0, size=20). Fixed newest-first sort (created_at desc) — sort options are out of scope.

    Aggregation: `ProductQueryService.listByCategory(Long categoryId, int page, int size)` →
      - category exists? `CategoryRepository.findById(categoryId)` absent → `CategoryNotFoundException` (404, existing CATEGORY_003). This must be distinct from "valid category, no products" (→ 200 empty page).
      - descendant ids via MySQL 8 `WITH RECURSIVE` native query on `CategoryJpaRepository` returning `List<Long>` (the node itself + all descendants):
        `WITH RECURSIVE sub AS (SELECT id FROM category WHERE id = :rootId UNION ALL SELECT c.id FROM category c JOIN sub ON c.parent_id = sub.id) SELECT id FROM sub`.
        A leaf node returns just itself (products only sit on leaves, so non-leaf ids in the set simply match nothing). This aggregation is native (CTE) — QueryDSL cannot express it.
      - page over the PRODUCT result (not the category set): Spring Data derived query on `ProductJpaRepository` — `Page<ProductJpaEntity> findByCategoryIdInOrderByCreatedAtDescIdDesc(List<Long> ids, Pageable pageable)`; call with `PageRequest.of(page, size)`. The `IdDesc` secondary key breaks ties deterministically when two products share a same-microsecond `created_at` — without it the newest-first order-assertion test is flaky. Guard empty id list (can't happen once category exists, but keep the derived query safe).
    `ProductListResponse` = paged `{ content:[{id,name}], page, size, totalElements }`. Extend the `ProductQueryRepository` port + `ProductQueryRepositoryImpl` with the list method.

    TEST (extend `ProductBrowseIntegrationTest`): (a) build 대>중>소, register 2 products on the leaf, GET the 대분류 node → both aggregated, newest-first (assert order by created_at desc); (b) a second 중>소 branch under the same 대분류 with its own product → GET 대분류 returns all 3 (descendant aggregation across branches); (c) paging boundary: size=1 → page 0 has 1, page 1 has the next, totalElements correct; (d) GET /v1/categories/999999/products → 404 CATEGORY_003; (e) GET a valid leaf with no products → 200 empty content.
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test --tests 'com.example.product.integration.ProductBrowseIntegrationTest'</automated>
  </verify>
  <done>GET /v1/categories/{id}/products aggregates all descendant-leaf products for a 대/중 node, newest-first (created_at desc, id desc tiebreak), correctly paged; unknown category → 404; valid-but-empty → 200 empty page.</done>
</task>

<task type="auto">
  <name>Task 3: INV-01 invariance gate — stock path unchanged, only V4 added, full suite green</name>
  <files>(verification only — no source changes)</files>
  <action>
    Prove the stock lifecycle is untouched (INV-01). merge-base is `git merge-base HEAD main` (currently b3d0f736…).

    GATE A — stock-path Java diff = 0. Assert zero changed lines on the write paths:
      `git diff --stat $(git merge-base HEAD main) -- \
        'product-service/**/StockService.java' \
        'product-service/**/ProcessCancelledStockService.java' \
        'product-service/**/PaymentCancelledStock*.java' \
        'product-service/**/OrphanReservationRecovery*.java' \
        'product-service/**/StockReservation*.java' \
        'product-service/**/ProductStock*.java'`
      Expected: empty output. The `PaymentCancelledStock*` glob covers both PaymentCancelledStockConsumer AND PaymentCancelledStockRetryConsumer; `OrphanReservationRecovery*` covers both the service AND OrphanReservationRecoveryScheduler — a silent edit to either sibling must not slip past the gate. IMPORTANT: `ProductStockJpaRepository` (tryReserve/restore) must appear ZERO times — the availableQty read-only join added in Task 1 lives on ProductSkuJpaRepository / ProductQueryRepositoryImpl, NOT on ProductStock* write files. If any ProductStock* file shows in the diff, INV-01 is violated — stop and report.
    GATE B — migration dir: only V4 added, V1/V2/V3 unchanged:
      `git diff --stat $(git merge-base HEAD main) -- product-service/src/main/resources/db/migration/`
      Expected: single new file `V4__link_product_category.sql`; no lines under V1/V2/V3.
    GATE C — full suite green (all prior stock/reservation/orphan/cancel-restore tests + new browse tests):
      `find . -path '*/build/*' -name '* [0-9].sql' -delete; ./gradlew :product-service:test`
      Expected: BUILD SUCCESSFUL, 0 failures. (The stray-copy cleanup avoids Flyway "more than one migration version N" from cloud-sync build/ duplicates.)

    Record the merge-base sha and each gate's output in the SUMMARY (mirror Phase 1's "INV-01 Gate Output" section).
  </action>
  <verify>
    <automated>find . -path '*/build/*' -name '* [0-9].sql' -delete; MB=$(git merge-base HEAD main); test -z "$(git diff --name-only $MB -- 'product-service/**/StockService.java' 'product-service/**/ProcessCancelledStockService.java' 'product-service/**/PaymentCancelledStock*.java' 'product-service/**/OrphanReservationRecovery*.java' 'product-service/**/StockReservation*.java' 'product-service/**/ProductStock*.java')" && echo STOCK_PATH_UNCHANGED && ./gradlew :product-service:test</automated>
  </verify>
  <done>Stock-path Java diff = 0 (ProductStock* absent); only V4 added under db/migration (V1/V2/V3 unchanged); `:product-service:test` BUILD SUCCESSFUL with 0 failures (existing stock/reservation/orphan/cancel-restore + new browse). Gate output recorded in SUMMARY.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → product-service HTTP | untrusted `categoryId`, `productId`, page/size cross here |
| product-service → product_db | SQL (native CTE + native stock join) built from request input |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-02-01 | Tampering | POST /v1/products categoryId | high | mitigate | leaf (level 3) + existence validation in CatalogService; non-leaf/missing → 400 PRODUCT_001. FK fk_product_category enforces referential integrity at DB. |
| T-02-02 | Elevation/Injection | recursive CTE + stock-join native queries | high | mitigate | bound parameters only (`:rootId`, `:productId`, IN-list via Spring Data); no string concatenation of request values into SQL. |
| T-02-03 | Denial of Service | GET list paging | medium | mitigate | Pageable with defaults (page=0,size=20); paging over product result, not unbounded fetch. (size cap not enforced — accept for internal admin scope.) |
| T-02-04 | Tampering | INV-01 stock write path | critical | mitigate | Task 3 git-diff gate proves ProductStock*/stock_reservation write logic = 0 change; availableQty is read-only join. |
| T-02-05 | Integrity | V4 backfill on legacy rows | medium | mitigate | all-three-insert `EXISTS(product…)` + NOT EXISTS guards (empty-DB fires nothing, no duplicate-key); NOT NULL asserted by Step 3; app-boot (Hibernate validate) proves mapping; ProductCategoryMigrationTest proves populated backfill lands legacy rows on a level-3 leaf. |
| T-02-SC | Tampering | npm/pip/cargo installs | high | accept | no new packages added this phase (pure JDK/Spring/existing deps) — no legitimacy gate needed. |
</threat_model>

<verification>
- V4 applies via Flyway at container startup; Hibernate schema-validate passes on the new mapped `category_id` (any booting integration test proves it). Populated-backfill branch proven by `ProductCategoryMigrationTest` (Flyway target V3 → insert orphan products → target V4 → assert legacy rows on level-3 '미분류' leaf, chain once).
- PLINK-01: leaf categoryId registers; non-leaf → 400 PRODUCT_001; missing categoryId → 400 INVALID_REQUEST; non-existent categoryId → 400 PRODUCT_001.
- BROWSE-02: GET /v1/products/{id} returns 3-node category path + SKU list + availableQty (read-only join); unknown → 404 PRODUCT_002.
- BROWSE-01: GET /v1/categories/{id}/products aggregates descendant-leaf products for 대/중 nodes, newest-first, paged; unknown → 404 CATEGORY_003; empty leaf → 200 empty page.
- INV-01: stock-path Java diff = 0, only V4 under db/migration, full `:product-service:test` green.
</verification>

<success_criteria>
All five ROADMAP Phase 2 success criteria observable via `ProductBrowseIntegrationTest` + `ProductCategoryMigrationTest` + the Task 3 gate:
1. POST /v1/products with leaf categoryId registers; non-leaf/missing/non-existent → 400.
2. V4 (add → 미분류 backfill → NOT NULL+FK) applies; app boots; populated backfill lands legacy rows on a level-3 leaf (ProductCategoryMigrationTest); no existing row violates.
3. GET /v1/categories/{id}/products aggregates 하위 leaf products for 대/중 nodes, newest-first, paged.
4. GET /v1/products/{id} returns category path + SKU(skuCode, optionSummary) + availableQty (read-only join).
5. INV-01 gate: stock logic change 0, only V4 added, existing + new tests all green.
</success_criteria>

<output>
Create `.planning/workstreams/product-catalog/phases/02-product-link-browse/02-SUMMARY.md` when done (mirror Phase 1 SUMMARY: tasks table, INV-01 Gate Output section with merge-base sha, requirements coverage, deviations).
</output>