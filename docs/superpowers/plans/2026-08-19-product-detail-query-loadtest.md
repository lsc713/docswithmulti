# Product Detail Query Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the product-detail query reduction and measure its AWS capacity against the existing baseline.

**Architecture:** Replace per-category lookups with one MySQL recursive CTE, expose the existing request query-count metric behind an opt-in flag, then deploy the existing same-AZ private product-only stack. Run query-count verification separately from uninstrumented capacity measurements so observability overhead does not distort the baseline.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MySQL 8, Micrometer, Gradle, Terraform, AWS EC2/SSM, Docker Compose, k6

**Spec:** `docs/load-test/product-detail-query-analysis-2026-08-19.md`

## Global Constraints

- Commit only Product query optimization, Product load-test configuration, tests, and measurement reports.
- Keep `LOADTEST_QUERYCOUNT_ENABLED=false` for capacity runs.
- Use the existing `ap-northeast-2a` same-AZ private-IP topology and destroy it after measurement.
- Compare against commit `d0990a3d0561e91582097410adfe843eee7f5cd5` using the same realistic request distribution.

---

### Task 1: Publish the query optimization

**Files:**
- Modify: `product-service/build.gradle`
- Modify: `product-service/src/main/java/com/example/product/application/interfaces/CategoryRepository.java`
- Modify: `product-service/src/main/java/com/example/product/application/service/ProductQueryService.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryJpaRepository.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/persistence/CategoryRepositoryImpl.java`
- Modify: `product-service/src/test/java/com/example/product/integration/ProductBrowseIntegrationTest.java`
- Modify: `infra/load-test/deploy/product.compose.yml`
- Create: `docs/load-test/product-detail-query-analysis-2026-08-19.md`

**Interfaces:**
- Consumes: `common-observability` query-count auto-configuration and the existing Product detail endpoint.
- Produces: `CategoryRepository.findPathByLeafId(Long)` and `db.queries.per_request` measurements.

- [x] **Step 1: Copy only the reviewed Product changes into this isolated worktree.**
- [x] **Step 2: Run `./gradlew :product-service:cleanTest :product-service:test`.**
- [x] **Step 3: Verify 97 tests, zero failures, and run `git diff --check`.**
- [ ] **Step 4: Commit, push `agent/product-detail-query-optimization`, and open a draft PR to `main`.**
- [ ] **Step 5: Merge the verified PR and fast-forward the isolated worktree to `origin/main`.**

### Task 2: Provision and verify the private Product stack

**Files:**
- Reuse: `infra/load-test/product-only-static-test.sh`
- Reuse: `infra/load-test/product-only.tfvars.example`
- Reuse: `k6/product-detail-aws-static-test.sh`

**Interfaces:**
- Consumes: merged `main`, AWS credentials, the existing Terraform Product-only topology.
- Produces: same-AZ private k6, Product, MySQL, and observability nodes.

- [ ] **Step 1: Run AWS identity and Terraform validation checks.**
- [ ] **Step 2: Apply the existing Product-only stack with the cheapest supported Spot configuration.**
- [ ] **Step 3: Deploy merged Product code and seed 100,000 products with 9 SKUs each.**
- [ ] **Step 4: Enable query counting for a short smoke request and confirm six SQL statements.**
- [ ] **Step 5: Restart Product with query counting disabled for capacity measurement.**

### Task 3: Measure and compare capacity

**Files:**
- Update: `docs/load-test/product-detail-query-analysis-2026-08-19.md`
- Create: `docs/load-test/product-detail-query-aws-results-2026-08-19.md`

**Interfaces:**
- Consumes: the private Product endpoint and the existing realistic k6 profile.
- Produces: smoke, baseline, ramp summaries and a comparison with the prior 1.1k RPS/MySQL bottleneck.

- [ ] **Step 1: Run the smoke profile and require zero HTTP failures.**
- [ ] **Step 2: Run the 10 VU baseline with query counting disabled.**
- [ ] **Step 3: Run the existing 10→50→100 VU ramp and collect RPS, p95/p99, errors, CPU, and Hikari metrics.**
- [ ] **Step 4: Compare saturation point and first bottleneck with the prior AWS report.**
- [ ] **Step 5: Save and publish the result report, then run `terraform destroy` and verify no test instances remain.**
