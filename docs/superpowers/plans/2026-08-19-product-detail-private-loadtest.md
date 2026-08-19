# Product Detail Private Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a product-only AWS load-test profile with three private test nodes and a small NAT instance in one AZ.

**Architecture:** Keep the existing full rig as the default and select the smaller topology with `load_test_profile = "product"`. Run k6 and lightweight Prometheus together, product-service with Redis, and MySQL separately; disable Kafka listeners for the read-only test.

**Tech Stack:** Terraform, AWS EC2/VPC/SSM, Docker Compose, Bash, Spring Boot, k6, Prometheus

**Spec:** `docs/superpowers/specs/2026-08-19-product-detail-private-loadtest-design.md`

## Global Constraints

- Test traffic stays on fixed private IPs in one AZ.
- The existing full topology remains the default.
- Product test nodes use Spot; the NAT instance is on-demand.
- No API Gateway, Kafka broker, MinIO, Grafana, or separate observability node.
- AWS creation is a separately approved, billable step.

---

### Task 1: Product-only Terraform topology

**Files:** `infra/load-test/{variables,instances,network,security,outputs}.tf`, `product-only.tfvars.example`, `product-only-static-test.sh`

**Produces:** validated `load_test_profile = full|product`, three product nodes, conditional NAT egress, and `egress_mode` output.

- [x] Add a failing static test for the profile, NAT instance route, and output.
- [x] Add `product_instances` with k6 `10.0.1.10`, product `10.0.1.23`, and MySQL `10.0.1.33`.
- [x] Keep NAT Gateway for `full`; use public `t4g.nano` with IP forwarding and masquerading for `product`.
- [x] Run `terraform fmt -check`, `terraform validate`, and the static test.
- [x] Commit as `feat: add product-only private load test topology`.

### Task 2: Read-only deployment and lightweight metrics

**Files:** `KafkaConsumerConfig.java`, `infra/load-test/deploy/{product-readonly.compose.yml,ssm-deploy.sh,port-forward.sh}`, `infra/load-test/observability/product-only*`, `k6/run-product-detail-aws.sh`, `k6/product-detail-aws-static-test.sh`

**Produces:** `LOAD_TEST_PROFILE=product` deployment path and configurable `PROM_URL`.

- [x] Make both custom Kafka listener factories honor `spring.kafka.listener.auto-startup` while retaining default `true`.
- [x] Add a product-only Compose override with local Redis and disabled Kafka listeners.
- [x] Add Prometheus and mysqld-exporter on k6, scraping only product, three nodes, and product MySQL.
- [x] Route product deployment and Prometheus port forwarding without changing full mode.
- [x] Parameterize k6 remote write via `PROM_URL`.
- [x] Verify shell syntax, static assertions, Compose config, and Java compilation.
- [x] Commit as `feat: deploy product-only load test stack`.

### Task 3: Runbook and final verification

**Files:** `docs/load-test/product-detail.md`, `infra/load-test/README.md`

**Produces:** exact provision, deploy, seed, test, inspect, and destroy commands.

- [x] Document `product-only.tfvars.example`, `LOAD_TEST_PROFILE=product`, local Prometheus remote write, and immediate destroy.
- [x] Run Terraform, shell, Compose, Gradle, and whitespace checks.
- [ ] Commit documentation and this plan.
