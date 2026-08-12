# Product Detail Load Test Design

**Date:** 2026-08-12

**Status:** Approved

**Scope:** `GET /v1/products/{id}` direct-to-product-service load test

## Goal

Measure product-detail capacity without local resource contention by running k6, product-service, and product MySQL on separate EC2 instances connected only through same-AZ private IPs. Compare hot, uniform, and 80/20 request distributions against the same representative 1,000-product dataset.

## Measurement Boundary

- Target: `GET /v1/products/{id}` on product-service port `8084`.
- First baseline bypasses API Gateway and frontend so the result describes product-service and product DB capacity.
- k6 measures HTTP latency, throughput, and errors. Prometheus measures product JVM/HTTP/Hikari metrics plus product and MySQL host metrics.
- Image URLs are presigned as part of the response, so presigning CPU and response serialization are included.
- k6 does not download the returned images. Object-storage bandwidth and browser rendering are outside this test.
- Local runs validate seeding and scripts only. Capacity conclusions come only from the separated AWS rig.

## Representative Dataset

The dataset has one three-level category tree and 1,000 products. Every product has the same shape so request distribution is the only changing variable:

- 2 variant attributes: color and size
- 3 color values and 3 size values
- 9 SKUs per product from the `3 × 3` combinations
- 1 stock row per SKU
- 3 image metadata rows per product
- 2 descriptive attributes, each with one value per product

Names, SKU codes, and image keys use a per-run prefix. Existing rows are not deleted. The seeder writes only rows carrying that prefix and exports exactly those product IDs to `k6/seed/productIds.json`.

The image rows contain deterministic test keys. The objects do not need to exist because product detail only creates presigned GET URLs; it does not issue S3 reads. The AWS product container still receives a private test endpoint and static test credentials so URL generation matches the application path.

## Load Distributions

One k6 script accepts `DISTRIBUTION=hot|uniform|realistic` and reads the shared product ID array once.

| Distribution | Product selection | Purpose |
|---|---|---|
| `hot` | Uniformly select among the first 10 IDs | Warm-buffer, concentrated-item behavior |
| `uniform` | Uniformly select among all 1,000 IDs | Broad working set and DB/cache pressure |
| `realistic` | 80% among the first 10; 20% among all IDs | Simple production-like popularity skew |

Each iteration performs one detail GET and checks:

- HTTP status is `200`.
- Response product ID equals the requested ID.
- `category` contains 3 nodes.
- `images` contains 3 entries.
- `skus` contains 9 entries.
- `variantOptions` contains 2 entries.
- `specs` contains 2 entries.

Smoke and baseline runs fail their thresholds when HTTP failures reach 1% or response-shape checks fall below 99%. Ramp and stress runs continue through degradation so the knee and breaking point remain observable.

## Local Flow

1. Start the existing local MySQL, Redis, Kafka, and MinIO dependencies and product-service.
2. Seed 100 products with the same schema used by AWS.
3. Run one smoke pass for each distribution against `http://localhost:8084`.
4. Treat success only as validation of data creation, selection logic, response checks, and metrics wiring—not as a capacity result.

The seeder accepts `SEED_COUNT`, MySQL connection variables, and `OUT`. Defaults target the current local product DB on port `3310`. AWS uses the same script with different connection variables and `SEED_COUNT=1000`.

## AWS Topology

Extend the existing `infra/load-test` single-AZ private subnet with two nodes:

| Role | Instance | Private IP | Purpose |
|---|---|---|---|
| `product` | `c7g.xlarge` | `10.0.1.23` | product-service only |
| `mysql-product` | `m7g.large` | `10.0.1.33` | product MySQL only |

The existing `k6` node at `10.0.1.10` sends requests directly to `http://10.0.1.23:8084`. The existing `infra` and `obs` nodes remain shared. No public IP or inbound internet rule is added.

New deployment files follow the existing role-to-compose pattern:

- `mysql-product.compose.yml` runs MySQL 8 with `product_db` and the existing product credentials.
- `product.compose.yml` runs the arm64 product-service image with host networking, product DB `10.0.1.33:3306`, Redis/Kafka private addresses, fixed heap settings, and load-test observability settings.
- The image workflow adds `product-service` to the current matrix and path filter; the common Dockerfile remains unchanged apart from its service list comment.
- `ssm-deploy.sh` adds the DB before the product application in deployment order.
- `port-forward.sh` adds product HTTP and product MySQL targets. The MySQL tunnel lets the same local seeder populate the private DB without SSH or a public endpoint.
- A small AWS runner sends `productIds.json` to the k6 instance through SSM and runs the pinned `grafana/k6` container with host networking. The k6 host therefore needs no public IP or locally installed k6 binary.

The application must start once before AWS seeding so Flyway creates the schema. The seeder then connects through the SSM MySQL port forward and writes `productIds.json` locally. The AWS runner transfers that file to the k6 host through SSM immediately before execution; it is never committed.

## Observability

Product-service exposes `health`, `metrics`, and `prometheus` actuator endpoints with HTTP server histograms enabled, matching the existing services.

Prometheus adds:

- product application target `10.0.1.23:8084`
- node-exporter targets for `product` and `mysql-product`
- a product mysqld-exporter connected to `10.0.1.33:3306`

The comparison records, for each distribution under identical stage settings:

- k6 RPS, p50, p95, p99, and failure rate
- product host CPU and memory
- JVM heap, GC pause, HTTP server latency, and Hikari active/pending
- product MySQL host CPU, memory, disk I/O, connections, and slow/lock indicators available from the existing exporter

The first comparison does not add a new Grafana dashboard. Existing Prometheus/Grafana exploration is sufficient; a dedicated dashboard is warranted only if repeated runs show the manual comparison is error-prone.

## Validation and Failure Handling

- The seeder exits non-zero when MySQL is unreachable, a statement fails, or the exported product count differs from `SEED_COUNT`.
- The k6 script rejects unknown `DISTRIBUTION` values during initialization.
- The k6 script aborts before sending load if fewer than 10 product IDs exist.
- A missing or malformed `productIds.json` is a setup failure, not an HTTP test result.
- Every run uses an explicit distribution tag so metrics from hot, uniform, and realistic runs cannot be confused.
- Run one distribution at a time and keep VUs, duration, instance types, JVM heap, and dataset fixed.
- Warm up before recording a baseline; record warm and cold behavior separately if cold-start performance later becomes a goal.

## Test Strategy

The smallest durable checks are:

1. A shell self-check for the seeder against local MySQL: seed a small count, verify the exported count, and verify one product has 9 SKUs, 3 images, 2 variant options, and 2 specs through the detail API.
2. A k6 smoke run for each distribution with the shared response-shape checks.
3. `terraform fmt -check` and `terraform validate` for the infrastructure changes.
4. Docker Compose config validation for the new product and product-DB files.
5. Existing product-service tests plus an actuator exposure check if configuration behavior is not already covered.

## Run Sequence

1. Local seed with `SEED_COUNT=100`.
2. Local `hot`, `uniform`, and `realistic` smoke runs.
3. Build and publish the product-service arm64 image.
4. `terraform apply` in `infra/load-test`.
5. Deploy infrastructure, databases, applications, and observability through SSM.
6. Open an SSM tunnel to product MySQL and seed `SEED_COUNT=1000`.
7. Use the AWS runner to transfer `productIds.json` through SSM and execute k6 on `10.0.1.10`.
8. Warm up product detail.
9. Run `hot`, `uniform`, and `realistic` separately with identical load stages and Prometheus remote-write enabled.
10. Record the result and configuration in the load-test log.
11. Run `terraform destroy` immediately after the session.

## Explicit Non-goals

- API Gateway and authentication overhead
- category listing performance
- product creation API performance
- image-object downloads or CDN behavior
- multi-AZ networking
- application caching changes
- a new dashboard or orchestration framework

These become separate measurements only after the direct product-detail baseline is stable.
