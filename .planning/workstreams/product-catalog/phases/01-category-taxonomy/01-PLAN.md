---
phase: 01-category-taxonomy
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - product-service/src/main/resources/db/migration/V3__create_category.sql
  - product-service/src/main/java/com/example/product/domain/entity/Category.java
  - product-service/src/main/java/com/example/product/application/service/CategoryService.java
  - product-service/src/main/java/com/example/product/application/interfaces/CategoryRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaEntity.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java
  - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java
  - product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
  - product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java
  - product-service/src/main/java/com/example/product/presentation/dto/CreateCategoryRequest.java
  - product-service/src/main/java/com/example/product/presentation/dto/CategoryResponse.java
  - product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
  - product-service/src/main/java/com/example/product/common/exception/application/CategoryDepthExceededException.java
  - product-service/src/main/java/com/example/product/common/exception/application/CategoryNameDuplicateException.java
  - product-service/src/main/java/com/example/product/common/exception/application/CategoryNotFoundException.java
  - product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java
autonomous: true
requirements: [CAT-01, CAT-02, CAT-03, INV-01]

must_haves:
  truths:
    - "POST /v1/categories with no parentId creates a level-1 대분류 and returns {id, level:1} (CAT-01)."
    - "POST /v1/categories with parentId creates a child at parent.level+1 (CAT-01)."
    - "Creating a 4th level under a level-3 node is rejected with 400 CATEGORY_DEPTH_EXCEEDED (CAT-01)."
    - "Duplicate name under the same parent is rejected with 409 — including two 대분류 (parentId null) with the same name; the same name under a different parent is allowed (CAT-02)."
    - "GET /v1/categories returns the full 대→중→소 nested tree (CAT-03)."
    - "No stock/reservation code changes; existing product-service test suite passes (INV-01)."
  artifacts:
    - product-service/src/main/resources/db/migration/V3__create_category.sql
    - product-service/src/main/java/com/example/product/domain/entity/Category.java
    - product-service/src/main/java/com/example/product/application/service/CategoryService.java
    - product-service/src/main/java/com/example/product/application/interfaces/CategoryRepository.java
    - product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaEntity.java
    - product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java
    - product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java
  key_links:
    - "CategoryController → CategoryService → CategoryRepository port → CategoryJpaRepository, wired as a @Bean in PersistenceConfig (mirrors productRepository)."
    - "V3 UK uk_category_parent_name over generated parent_key=IFNULL(parent_id,0) (so roots collide too) ↔ DataIntegrityViolationException caught in the persistence adapter → 409 CATEGORY_NAME_DUPLICATE."
    - "domain Category child-creation level derivation (parent.level+1) ↔ 400 CATEGORY_DEPTH_EXCEEDED when parent.level == 3."
---

<objective>
Phase 1 of the product-catalog workstream: category taxonomy, CREATE + READ only. Deliver a pure-additive category tree (대·중·소, adjacency list) so an admin can build the tree and anyone can read it — no product linkage (that is Phase 2).

Covers CAT-01 (create + level derivation + depth>3 reject), CAT-02 (sibling name uniqueness), CAT-03 (nested tree read). INV-01 is enforced as a verification gate: this phase adds ONLY new Category files + Flyway V3 and must not touch any stock/reservation code.

Purpose: lay the first thin end-to-end slice ("build a category tree, read it back") that Phase 2 builds product browsing on top of.
Output: V3 category table + Category domain/app/infra/presentation layers (hexagonal, mirroring the existing Product* stack) + one Testcontainers integration test proving all three requirements.

Authoritative design: docs/superpowers/specs/2026-07-31-product-category-taxonomy-design.md (§3 DDL, §4 domain rules, §5 API, §9 open questions resolved below).

Open questions from spec §9 resolved for this phase:
- GET /v1/categories returns the FULL nested tree (no parentId partial-expansion param) — data is small (design assumption).
- No '미분류' backfill node and NO product.category_id column in Phase 1 — that ALTER + backfill belongs to Phase 2 (keeps Phase 1 pure-additive). Ordering is explicit: category table = V3 (this phase), product ALTER + backfill = V4 (Phase 2).
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/workstreams/product-catalog/ROADMAP.md
@.planning/workstreams/product-catalog/REQUIREMENTS.md
@docs/superpowers/specs/2026-07-31-product-category-taxonomy-design.md

# Existing hexagonal patterns to mirror EXACTLY (copy structure, not just style):
@product-service/src/main/java/com/example/product/domain/entity/Product.java
@product-service/src/main/java/com/example/product/application/service/CatalogService.java
@product-service/src/main/java/com/example/product/application/interfaces/ProductRepository.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaEntity.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProductRepositoryImpl.java
@product-service/src/main/java/com/example/product/infrastructure/persistence/ProductJpaRepository.java
@product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java
@product-service/src/main/java/com/example/product/presentation/controller/ProductController.java
@product-service/src/main/java/com/example/product/presentation/dto/SeedRequest.java
@product-service/src/main/java/com/example/product/presentation/GlobalExceptionHandler.java
@product-service/src/main/java/com/example/product/common/exception/ErrorCode.java
@product-service/src/main/java/com/example/product/common/exception/BusinessException.java
@product-service/src/main/java/com/example/product/common/exception/application/StockInsufficientException.java
@product-service/src/main/resources/db/migration/V1__create_product_core.sql
@product-service/src/test/java/com/example/product/integration/StockTracerIntegrationTest.java
</context>

<tasks>

<task type="tracer">
  <name>Task 1: End-to-end "create 대분류 → read tree" — one path, all layers</name>
  <files>
    product-service/src/main/resources/db/migration/V3__create_category.sql,
    product-service/src/main/java/com/example/product/domain/entity/Category.java,
    product-service/src/main/java/com/example/product/application/interfaces/CategoryRepository.java,
    product-service/src/main/java/com/example/product/application/service/CategoryService.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaEntity.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java,
    product-service/src/main/java/com/example/product/infrastructure/config/PersistenceConfig.java,
    product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java,
    product-service/src/main/java/com/example/product/presentation/dto/CreateCategoryRequest.java,
    product-service/src/main/java/com/example/product/presentation/dto/CategoryResponse.java,
    product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java
  </files>
  <action>
    Wire ONE happy path — create a level-1 대분류, then GET the tree and see it — through every layer, mirroring the Product* stack file-for-file.

    Migration V3__create_category.sql: create ONLY the `category` table per design §3 (columns id, parent_id NULL, name VARCHAR(100), level TINYINT, created_at DATETIME(6); PK id; KEY idx_category_parent (parent_id); FK fk_category_parent → category(id); InnoDB utf8mb4). **Sibling-name uniqueness MUST hold for 대분류 too**: MySQL/InnoDB treats NULL as DISTINCT in a UNIQUE index, so `(parent_id, name)` would NOT reject two level-1 roots both named "의류". Use a generated column instead — `parent_key BIGINT AS (IFNULL(parent_id, 0)) STORED` + `UNIQUE KEY uk_category_parent_name (parent_key, name)` — so root siblings collide on parent_key=0 and the atomic UK (no pre-SELECT) + DataIntegrityViolationException→409 mapping works for roots and children alike. Do NOT add product.category_id here — that is Phase 2's V4. Follow the V1 header convention noting "적용 후 불변, 변경은 새 버전으로만".

    domain/entity/Category.java: pure POJO, NO Spring/JPA annotations (mirror Product.java — @Getter, private final fields, private ctor, static factories). Fields: id, parentId, name, level, createdAt. Factory `createRoot(name)` → parentId=null, level=1. Factory `createChild(parent, name)` → level=parent.level()+1 (level-derivation lives in the domain). Add `boolean isLeaf()` → level == 3. For this tracer only createRoot is exercised; createChild + the depth guard are completed in Task 2.

    application/interfaces/CategoryRepository.java: port. Methods `Category save(Category)` and `List<Category> findAll()` (findAll powers the tree read).

    infrastructure/persistence: CategoryJpaEntity (mirror ProductJpaEntity — @Entity @Table(name="category"), @Id @GeneratedValue IDENTITY, map parent_id/level/created_at columns, from(Category)/toDomain(); LocalDateTime↔Instant via ZoneOffset.UTC as ProductJpaEntity does). CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, Long>. CategoryRepositoryImpl implements the port (delegates to jpa, mirror ProductRepositoryImpl).

    PersistenceConfig.java: add a `categoryRepository(CategoryJpaRepository jpa)` @Bean returning `new CategoryRepositoryImpl(jpa)` — mirror the existing productRepository bean exactly.

    application/service/CategoryService.java: @Service. `create(Long parentId, String name)` → for this tracer, when parentId is null persist Category.createRoot(name) and return {id, level}. `getTree()` → repository.findAll(), build nested tree in memory (roots = parentId null; attach children by parentId), return list of tree nodes ordered by id. Return a small service record/DTO the controller maps.

    presentation/dto: CreateCategoryRequest (record: Long parentId (nullable, no annotation), @NotBlank String name — mirror SeedRequest Bean Validation). CategoryResponse: two shapes — a create response {Long id, int level} and a nested tree node {Long id, String name, int level, List&lt;node&gt; children}. Keep them as records (nested static records ok).

    presentation/controller/CategoryController.java: @RestController @RequestMapping("/v1/categories"). POST → create, returns {id, level}. GET → getTree(), returns nested list. Mirror ProductController wiring. Reuse the existing GlobalExceptionHandler (no new advice needed).

    Integration test CategoryTaxonomyIntegrationTest.java: mirror StockTracerIntegrationTest setup EXACTLY (Boot 4.0.5 — NO @AutoConfigureMockMvc / TestRestTemplate; use @SpringBootTest + @Testcontainers MySQLContainer("mysql:8.0") withDatabaseName("product_db"), @DynamicPropertySource for datasource, MockMvcBuilders.webAppContextSetup(ctx) in @BeforeEach, JdbcTemplate for DB assertions). Tracer test: POST /v1/categories {"name":"의류"} → 200, body {id>0, level:1}; GET /v1/categories → 200, tree contains a node name "의류" level 1 with empty children.
  </action>
  <verify>
    <automated>./gradlew :product-service:test --tests "com.example.product.integration.CategoryTaxonomyIntegrationTest" -x checkstyleMain</automated>
  </verify>
  <done>POST /v1/categories with no parentId returns 200 {id, level:1}; GET /v1/categories returns a nested tree containing the created root; the slice compiles and the integration test passes end-to-end against Testcontainers MySQL.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Complete creation rules — child level derivation, depth>3 reject (400), sibling-name uniqueness (409)</name>
  <files>
    product-service/src/main/java/com/example/product/domain/entity/Category.java,
    product-service/src/main/java/com/example/product/application/service/CategoryService.java,
    product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java,
    product-service/src/main/java/com/example/product/common/exception/ErrorCode.java,
    product-service/src/main/java/com/example/product/common/exception/application/CategoryDepthExceededException.java,
    product-service/src/main/java/com/example/product/common/exception/application/CategoryNameDuplicateException.java,
    product-service/src/main/java/com/example/product/common/exception/application/CategoryNotFoundException.java,
    product-service/src/test/java/com/example/product/integration/CategoryTaxonomyIntegrationTest.java
  </files>
  <behavior>
    - POST {parentId: rootId, name} → 200, child level == parent.level+1 (2 under a level-1 → level 2; 3 under a level-2 → level 3). (CAT-01)
    - POST {parentId: level3NodeId, name} → 400 CATEGORY_DEPTH_EXCEEDED (would be level 4). (CAT-01)
    - POST {parentId: nonexistentId, name} → 404 CATEGORY_NOT_FOUND. (parent-lookup guard; Claude's discretion — spec §5 unspecified, chose 404)
    - POST duplicate {parentId, name} where a sibling with that name already exists → 409 CATEGORY_NAME_DUPLICATE. (CAT-02)
    - POST two 대분류 with NO parentId + same name → second returns 409 CATEGORY_NAME_DUPLICATE (root siblings collide via parent_key=0; NOT skippable — the plain (parent_id,name) UK would let both through). (CAT-02)
    - POST same name under a DIFFERENT parent → 200 (uniqueness is per-parent only). (CAT-02)
  </behavior>
  <action>
    Complete the domain + service + adapter so all CAT-01 / CAT-02 rules hold. Add error codes and exception classes first (RED test asserts the mapped statuses), then implement.

    ErrorCode.java: add three entries using the existing 3-arg constructor `ErrorCode(String code, int httpStatus, String defaultMessage)` (mirror INVALID_REQUEST/STOCK_INSUFFICIENT):
    `CATEGORY_DEPTH_EXCEEDED("CATEGORY_001", 400, "카테고리 깊이는 3단계까지만 허용됩니다.")`,
    `CATEGORY_NAME_DUPLICATE("CATEGORY_002", 409, "같은 부모 아래 이름이 중복됩니다.")`,
    `CATEGORY_NOT_FOUND("CATEGORY_003", 404, "카테고리를 찾을 수 없습니다.")`.

    Exception classes under common/exception/application (mirror StockInsufficientException — extend BusinessException, pass ErrorCode + message): CategoryDepthExceededException, CategoryNameDuplicateException, CategoryNotFoundException.

    domain Category.createChild(parent, name): if parent.isLeaf() (parent.level == 3, i.e. child would be level 4) throw CategoryDepthExceededException — the depth rule lives in the domain, derived from parent.level, per design §4.

    CategoryService.create: when parentId != null, load parent via repository (add `Optional<Category> findById(Long)` to the port + adapter); missing → CategoryNotFoundException; else persist Category.createChild(parent, name).

    Sibling uniqueness: the DB UK uk_category_parent_name (already in V3) is the source of truth. In CategoryRepositoryImpl.save, catch org.springframework.dao.DataIntegrityViolationException and rethrow CategoryNameDuplicateException so GlobalExceptionHandler maps it to 409. (Do not pre-check with a SELECT — let the UK enforce it atomically; the adapter translates the persistence violation to a domain exception, keeping the port DB-agnostic.)

    Extend CategoryTaxonomyIntegrationTest with all six behavior cases above (build a root→중→소 chain, assert levels via response + JdbcTemplate, assert the rejection statuses and codes, assert duplicate ROOT name → 409, and assert same-name-different-parent succeeds).
  </action>
  <verify>
    <automated>./gradlew :product-service:test --tests "com.example.product.integration.CategoryTaxonomyIntegrationTest" -x checkstyleMain</automated>
  </verify>
  <done>Child creation derives level from parent; a 4th level is rejected 400 CATEGORY_DEPTH_EXCEEDED; unknown parent is 404; duplicate sibling name is 409 CATEGORY_NAME_DUPLICATE while the same name under a different parent succeeds — all asserted by the integration test.</done>
</task>

<task type="auto">
  <name>Task 3: INV-01 invariance gate — prove zero stock-path changes + full-suite no-regression</name>
  <files>(verification only — no source changes)</files>
  <action>
    Prove this phase is pure-additive w.r.t. the stock/reservation lifecycle (INV-01). Two checks:

    1. Diff gate: against the merge-base with main, confirm NONE of the guarded stock-path files changed. Run the diff-name check below; the guarded filename matches (StockService, ProcessCancelledStockService, PaymentCancelledStockConsumer, OrphanReservationRecoveryService, StockReservation*, ProductStock*) MUST count 0.

    2. Migration immutability: the only migration touched under db/migration/ must be the NEW `V3__create_category.sql`. V1/V2 hold the product_stock/stock_reservation DDL that INV-01 protects, and a fresh Testcontainers DB has no checksum history to catch a silent edit — so gate it in git: any changed migration filename other than `V3__*.sql` fails the check.

    If either gate trips, STOP — the plan was violated; revert that change.

    3. No-regression: run the full product-service test suite (existing stock reserve/release, orphan recovery, cancel-restore idempotency tracers) — all must still pass alongside the new category test.
  </action>
  <verify>
    <automated>BASE=$(git merge-base main HEAD); test "$(git diff --name-only $BASE HEAD -- product-service/ | grep -Ec 'StockService|ProcessCancelledStockService|PaymentCancelledStockConsumer|OrphanReservationRecoveryService|StockReservation|ProductStock')" = "0" && test "$(git diff --name-only $BASE HEAD -- product-service/src/main/resources/db/migration/ | grep -Ev 'V3__[^/]*\.sql$' | grep -c .)" = "0" && ./gradlew :product-service:test -x checkstyleMain</automated>
  </verify>
  <done>git diff against merge-base shows 0 changes to any guarded stock/reservation file, the only migration touched is V3__create_category.sql (V1/V2 untouched), and the full :product-service:test suite is green (new category test + all pre-existing stock/reservation/cancel-restore tests).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| client → POST /v1/categories | Untrusted `{parentId, name}` crosses into the service; name length and null-ness must be enforced. |
| app → product_db | Category writes; sibling-name integrity enforced at the DB (UK), not just app code. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-01-01 | Tampering | POST /v1/categories name field | low | mitigate | @NotBlank on CreateCategoryRequest.name + column VARCHAR(100); Bean Validation → 400 via existing GlobalExceptionHandler. |
| T-01-02 | Tampering | sibling-name uniqueness (race two identical siblings, incl. two roots) | medium | mitigate | UK uk_category_parent_name over generated parent_key=IFNULL(parent_id,0) enforces atomically at DB for roots and children (NULL-distinct pitfall avoided); adapter maps DataIntegrityViolationException → 409 (no read-then-write TOCTOU window). |
| T-01-03 | Denial of Service | unbounded tree depth via chained creates | low | mitigate | Domain createChild rejects level>3 (400), capping depth at 3. |
| T-01-04 | Elevation of Privilege | category writes without auth | low | accept | This phase adds no auth; product-service sits behind the api-gateway trust-header boundary (v2.0) — endpoint authz is a gateway/deploy concern, out of scope for this pure-additive slice. No package installs (T-{phase}-SC N/A — zero new dependencies). |
</threat_model>

<verification>
- CAT-01: create root (level 1) + child level derivation + depth>3 → 400, all in CategoryTaxonomyIntegrationTest.
- CAT-02: duplicate sibling → 409, same name different parent → 200.
- CAT-03: GET /v1/categories returns full 대→중→소 nested tree.
- INV-01: merge-base diff shows 0 guarded-file changes; full :product-service:test green.
- Flyway: `./gradlew :product-service:flywayInfo` shows V3 pending/applied cleanly (no edit to V1/V2).
</verification>

<success_criteria>
All four Phase 1 ROADMAP success criteria observable via the integration test + the INV-01 gate:
1. Root (level 1) and child (parent.level+1) creation works.
2. 4-level create → 400.
3. Duplicate sibling name → rejected; same name different parent → allowed.
4. GET /v1/categories → full nested tree.
5. INV-01: zero stock-path changes, full suite green.
</success_criteria>

<output>
Create `.planning/workstreams/product-catalog/phases/01-category-taxonomy/01-01-SUMMARY.md` when done.
</output>
