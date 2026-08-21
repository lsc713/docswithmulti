# Reserve SKU Batch Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify with a controlled A/B load test whether batching `reserve` SKU lookup removes the multi-item N+1 query cost and improves write throughput or latency.

**Architecture:** Keep the existing write-only uniform ramp and infrastructure, but send 10 distinct SKUs per reservation because the current one-SKU request cannot expose an N+1 difference. Run the old `0099cfc` image and the new image on the same stack, reset reservation/digest state between runs, and compare both HTTP and MySQL evidence.

**Tech Stack:** Java 22, Spring Data JPA, k6, Terraform, AWS EC2/SSM, MySQL 8.0, Prometheus

**Spec:** `docs/load-test/product-stock-mysql-threshold-2026-08-20.md`

## Global Constraints

- Preserve the default one-SKU workload; enable 10 SKUs only with `STOCK_ITEMS_PER_RESERVATION=10`.
- Use write-only uniform `1 → 3 → 6 → 8 → 11 VU`, three minutes per stage.
- Keep gp3 at 50 GiB / 3,000 IOPS / 125 MiB/s, `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`, and binlog group commit delay at `0/0`.
- Compare old and new application images on the same infrastructure and seed.
- Validate SQL digest counts as well as k6 failures, throughput, p95, and p99.
- Destroy all Terraform resources after evidence collection.

---

### Task 1: Add a reproducible multi-item write workload

**Files:**
- Modify: `k6/product-stock-mix.js`
- Modify: `k6/product-stock-mix-test.js`
- Modify: `k6/run-product-stock-mix-aws.sh`
- Modify: `docs/load-test/product-stock-mysql-threshold-2026-08-20.md`

**Interfaces:**
- Consumes: `STOCK_ITEMS_PER_RESERVATION`, default `1`
- Produces: reserve/release request pairs containing the requested number of distinct seeded SKUs

- [ ] **Step 1:** Add a failing k6 check that requests 10 seeded products and asserts both request bodies contain the same 10 SKU IDs.
- [ ] **Step 2:** Run `k6 run k6/product-stock-mix-test.js` and confirm the new check fails.
- [ ] **Step 3:** Add the minimum selection/request logic and pass `STOCK_ITEMS_PER_RESERVATION` through the AWS runner.
- [ ] **Step 4:** Run `k6 run k6/product-stock-mix-test.js` and `bash k6/product-stock-mix-runner-test.sh`.
- [ ] **Step 5:** Document why the previous one-item workload could not measure this optimization.

### Task 2: Run the old/new A/B measurement

**Files:**
- Results: `k6/results/<run-key>.*`

**Interfaces:**
- Consumes: old image `product-0099cfc391edafb232c95fe166ef4a5cf1396b0d`, new pushed image, and the Task 1 runner
- Produces: two valid k6 bundles plus SQL digest evidence

- [ ] **Step 1:** Commit and push the scoped implementation, wait for its `loadtest-images` workflow to succeed, then record the full SHA.
- [ ] **Step 2:** Apply Terraform with `load_test_profile=product-scaleout`, default gp3 settings, and deploy the old image while checking out the new runner SHA.
- [ ] **Step 3:** Seed 1,000 products; verify four Product targets and MySQL durability/group-commit settings.
- [ ] **Step 4:** Reset `stock_reservation` and Performance Schema statement summaries, then run `STOCK_ITEMS_PER_RESERVATION=10` write-only uniform load against the old image.
- [ ] **Step 5:** Collect old-image SQL digests, redeploy only Product nodes with the new image, reset the same state, and repeat the identical run.
- [ ] **Step 6:** Verify both runs have zero unexpected workload failures and that old per-item SKU SELECTs become one `IN (...)` SELECT per reserve in the new run.

### Task 3: Publish results and clean up

**Files:**
- Modify: `docs/load-test/product-stock-mysql-threshold-2026-08-20.md`

**Interfaces:**
- Consumes: Task 2 bundles and SQL digests
- Produces: source-linked comparison and an empty Terraform state

- [ ] **Step 1:** Calculate iteration/s, request RPS, p95/p99, MySQL CPU, COMMIT counts/timing, and SKU SELECT count/timing for both runs.
- [ ] **Step 2:** Replace the experiment-plan note with conditions, run keys, results, limitations, and conclusion.
- [ ] **Step 3:** Run `terraform destroy` with the same profile variables and verify `terraform state list` is empty.
- [ ] **Step 4:** Run `git diff --check` and re-check every documented number against its artifact.
