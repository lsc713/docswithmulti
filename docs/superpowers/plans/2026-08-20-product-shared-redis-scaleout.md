# Product Shared Redis Scale-Out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run two product-service instances behind one private load balancer while sharing one Redis cache, lock, and invalidation namespace.

**Architecture:** Add a dedicated `redis-product` role to the product load-test profile and make both product nodes use its fixed private IP. Add an internal NLB with a target group for the two product nodes; k6 reaches the NLB private IP. Prometheus scrapes both product nodes and the shared Redis exposes one cache state to both nodes.

**Tech Stack:** Terraform AWS EC2/NLB, Docker Compose, Spring Data Redis/Redisson, Prometheus, k6.

**Spec:** `docs/load-test/product-detail-cache-handoff.md`

## Global Constraints

- Keep all test traffic on private IPs in one AZ.
- Do not change product cache key, lock key, or invalidation semantics.
- Do not add a production dependency; reuse Redis, Redisson, Terraform, Prometheus, and k6 already present.
- Preserve the one-instance product profile until the scale-out profile is explicitly selected.

---

### Task 1: Add a scale-out product profile

**Files:**
- Modify: `infra/load-test/variables.tf`
- Modify: `infra/load-test/instances.tf`
- Test: `infra/load-test` Terraform validation/plan

**Interfaces:**
- Produces `load_test_profile=product-scaleout` with roles `product-a`, `product-b`, `redis-product`, `mysql-product`, `k6`, and `obs`.

- [ ] **Step 1: Add a failing Terraform profile validation case**

Run `terraform -chdir=infra/load-test plan -var='load_test_profile=product-scaleout'`.

Expected: FAIL because the profile is not allowed.

- [ ] **Step 2: Add the profile and fixed private IPs**

Add `product-scaleout` to the validation list and create a `product_scaleout_instances` map. Use `10.0.1.24` and `10.0.1.25` for product nodes, `10.0.1.41` for shared Redis, and retain the existing product MySQL, k6, and obs IPs.

- [ ] **Step 3: Verify Terraform accepts the profile**

Run `terraform -chdir=infra/load-test validate` and `terraform -chdir=infra/load-test plan -var='load_test_profile=product-scaleout'`.

Expected: validation succeeds and the plan includes both product roles plus `redis-product`.

### Task 2: Deploy shared Redis and two product instances

**Files:**
- Create: `infra/load-test/deploy/redis-product.compose.yml`
- Create: `infra/load-test/deploy/product-scaleout.compose.yml`
- Modify: `infra/load-test/deploy/ssm-deploy.sh`
- Test: shell syntax check

**Interfaces:**
- `redis-product.compose.yml` exposes Redis only at `10.0.1.41:6379` through host networking.
- `product-scaleout.compose.yml` uses `SPRING_DATA_REDIS_HOST=10.0.1.41` and one `product-service` container per product node.

- [ ] **Step 1: Add deployment order test by running the current script with the profile**

Run `LOAD_TEST_PROFILE=product-scaleout IMAGE_NS=x REPO_REF=x ./infra/load-test/deploy/ssm-deploy.sh`.

Expected: FAIL with an invalid profile before any AWS command.

- [ ] **Step 2: Add compose files and role mapping**

Add `redis-product` before `product-a product-b` in the profile order. Map Redis to its compose file and both product roles to the same scale-out compose file. Keep node-exporter deployment for each role.

- [ ] **Step 3: Verify script syntax and profile parsing**

Run `bash -n infra/load-test/deploy/ssm-deploy.sh` and invoke the invalid-image preflight with `LOAD_TEST_PROFILE=product-scaleout`.

Expected: profile is accepted and deployment resolves all three new roles before image pull.

### Task 3: Route product-detail load through a private NLB

**Files:**
- Modify: `infra/load-test/*.tf` containing security groups and network resources
- Modify: `infra/load-test/outputs.tf`
- Modify: `k6/config.js`
- Test: Terraform plan and k6 script parse

**Interfaces:**
- Produces an internal NLB listener on port 8084 with targets `product-a` and `product-b`.
- k6 uses `PRODUCT_BASE_URL` when supplied; scale-out runner supplies the NLB private endpoint.

- [ ] **Step 1: Run Terraform plan with two product nodes and no NLB**

Run `terraform -chdir=infra/load-test plan -var='load_test_profile=product-scaleout'`.

Expected: no load-balancer target group exists.

- [ ] **Step 2: Add minimal NLB, target group, listener, and attachments**

Create only the internal NLB resources required for TCP 8084. Attach the two fixed product private IPs and output the NLB DNS name.

- [ ] **Step 3: Add the k6 endpoint override and verify parsing**

Use an existing environment-driven config pattern to set `BASE.PRODUCT` from `PRODUCT_BASE_URL`; retain the existing default for all other profiles. Run `k6 inspect k6/product-detail.js` or the repository's existing k6 parse command.

### Task 4: Extend product-only Prometheus targets

**Files:**
- Modify: `infra/load-test/observability/product-only-prometheus.yml`
- Test: Prometheus config validation in the existing container image

**Interfaces:**
- Produces product scrape targets for `10.0.1.24:8084`, `10.0.1.25:8084`, and node-exporter targets for both nodes and shared Redis.

- [ ] **Step 1: Add both product targets and labels**

Keep the original target for one-node product profile. Add explicit `host: product-a` and `host: product-b` labels for scale-out nodes.

- [ ] **Step 2: Validate configuration**

Run `docker run --rm -v "$PWD/infra/load-test/observability/product-only-prometheus.yml:/etc/prometheus/prometheus.yml:ro" prom/prometheus:v2.54.1 --config.file=/etc/prometheus/prometheus.yml --check-config`.

Expected: configuration is valid.

### Task 5: Build, deploy, and compare

**Files:**
- Modify: `docs/load-test/product-detail-cache-handoff.md`
- Test: 100 VU, 30s hot test through NLB

**Interfaces:**
- Records the scale-out RPS, latency, per-node CPU, shared Redis state, and MySQL CPU alongside the one-node baseline.

- [ ] **Step 1: Build the product arm64 image from the feature branch**

Push the branch and manually run `loadtest-images.yml` for that ref. Use the resulting commit SHA image tag.

- [ ] **Step 2: Deploy and validate readiness**

Apply `product-scaleout`, wait for all SSM agents, deploy shared Redis then product nodes, and verify both product actuator targets and Prometheus `up` values are `1`.

- [ ] **Step 3: Run the hot test through the NLB**

Seed MySQL, run `VUS=100 DURATION=30s DISTRIBUTION=hot`, and collect k6 plus Prometheus data.

- [ ] **Step 4: Record findings and destroy the test environment**

Update the handoff document with results, run Terraform destroy, then commit the implementation and documentation.
