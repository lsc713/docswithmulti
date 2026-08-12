# Product detail load test runbook

This runbook measures `GET /v1/products/{id}` directly against product-service. Local runs validate the dataset, scripts, response checks, and metrics wiring; discard their timings. Capacity results come only from the separated AWS rig.

## Local smoke

From the repository root, start the required dependencies:

```bash
docker compose up -d mysql-product redis minio minio-init kafka1 kafka2 kafka3
```

Start product-service in a terminal and leave it running:

```bash
./gradlew :product-service:bootRun
```

In a second terminal, seed 100 products and run each distribution:

```bash
SEED_COUNT=100 ./k6/seed/product-detail-seed.sh
DISTRIBUTION=hot STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
DISTRIBUTION=uniform STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
DISTRIBUTION=realistic STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
```

All three runs must pass the HTTP and representative-response checks. Do not record their latency or throughput as capacity results because the load generator, service, and dependencies share local resources.

## AWS run

Run only when AWS provisioning and image publishing have been explicitly approved. The committed revision must be available to the repository cloned by the deployment and AWS runner.

### Provision and deploy

From the repository root, publish the images, wait for that workflow to succeed, then provision and deploy:

```bash
gh workflow run loadtest-images.yml
terraform -chdir=infra/load-test apply
IMAGE_NS=<dockerhub-user> ./infra/load-test/deploy/ssm-deploy.sh
```

The deployment starts product-service once so Flyway creates the product schema before seeding.

### Seed through the SSM tunnel

Start the tunnel in one terminal and leave it running; it owns the terminal until `Ctrl-C`:

```bash
LOCAL_PORT=13306 ./infra/load-test/deploy/port-forward.sh product-db
```

In a second terminal, seed the private product database:

```bash
MYSQL_PORT=13306 SEED_COUNT=1000 ./k6/seed/product-detail-seed.sh
```

Keep the tunnel open until seeding finishes. The seeder writes `k6/seed/productIds.json`; the AWS runner transfers that file to the k6 instance before each run.

### Warm up and smoke

Run one distribution at a time. These smoke runs warm the service and verify the AWS data path; do not include them in the recorded comparison:

```bash
DISTRIBUTION=hot STAGE=smoke ./k6/run-product-detail-aws.sh
DISTRIBUTION=uniform STAGE=smoke ./k6/run-product-detail-aws.sh
DISTRIBUTION=realistic STAGE=smoke ./k6/run-product-detail-aws.sh
```

### Record identical comparisons

Keep the dataset, instance types, JVM heap, and stage definitions unchanged. Run all three distributions separately for each recorded stage:

```bash
DISTRIBUTION=hot STAGE=baseline ./k6/run-product-detail-aws.sh
DISTRIBUTION=uniform STAGE=baseline ./k6/run-product-detail-aws.sh
DISTRIBUTION=realistic STAGE=baseline ./k6/run-product-detail-aws.sh

DISTRIBUTION=hot STAGE=ramp ./k6/run-product-detail-aws.sh
DISTRIBUTION=uniform STAGE=ramp ./k6/run-product-detail-aws.sh
DISTRIBUTION=realistic STAGE=ramp ./k6/run-product-detail-aws.sh
```

For each run, record:

- k6 RPS, p50, p95, p99, and failure rate
- product host CPU and memory
- product JVM heap, GC pause, HTTP server latency, and Hikari active/pending
- product MySQL host CPU, memory, disk I/O, connections, and available slow-query/lock indicators

Use this result row in the load-test log:

```text
date/time | commit | image tag | region/AZ | instance types | dataset count | JVM heap | stage | distribution | VUs/duration | RPS | p50 | p95 | p99 | failures | product CPU/memory | JVM/GC/HTTP/Hikari | MySQL CPU/memory/disk/connections/slow/locks | notes
```

### Destroy the rig

Destroy immediately after the session, including after an aborted or failed run:

```bash
terraform -chdir=infra/load-test destroy
```

Confirm the destroy plan and completion before ending the session.
