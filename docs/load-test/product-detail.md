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

### Preflight

Read [`infra/load-test/README.md`](../../infra/load-test/README.md), including its cost and destroy guidance. From the repository root, verify the local tools, executable scripts, GitHub authentication/workflow/secrets, Terraform configuration, and AWS identity before creating billable resources:

```bash
set -euo pipefail

for tool in git gh terraform aws session-manager-plugin mysql jq curl base64 tar shasum; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done

for script in \
  infra/load-test/deploy/ssm-deploy.sh \
  infra/load-test/deploy/port-forward.sh \
  k6/seed/product-detail-seed.sh \
  k6/run-product-detail-aws.sh; do
  test -x "$script" || { echo "Not executable: $script" >&2; exit 1; }
done

gh auth status
gh workflow view loadtest-images.yml --ref main >/dev/null
GH_SECRETS=$(gh secret list --app actions --json name --jq '.[].name')
grep -qx DOCKERHUB_USERNAME <<<"$GH_SECRETS"
grep -qx DOCKERHUB_TOKEN <<<"$GH_SECRETS"

terraform -chdir=infra/load-test init
terraform -chdir=infra/load-test validate
aws sts get-caller-identity
```

Secret listing confirms only that the required names exist; it cannot validate their values. Fix any failed check before continuing.

### Provision and deploy

Run AWS operations only after this work is merged to remote `main`. From a clean `main` checkout, create a unique workflow ref at that exact commit, dispatch it, capture the run belonging to that ref and SHA, wait for success, then deploy both the immutable image and repository revision:

```bash
set -euo pipefail

printf 'Docker Hub username: '
IFS= read -r IMAGE_NS
test -n "$IMAGE_NS"
git fetch origin main
test "$(git branch --show-current)" = main
git pull --ff-only origin main
test -z "$(git status --porcelain)"

IMAGE_SHA=$(git rev-parse HEAD)
test "$IMAGE_SHA" = "$(git rev-parse origin/main)"
IMAGE_REF="product-detail-loadtest-${IMAGE_SHA}-$(date -u +%Y%m%dT%H%M%SZ)"
git tag "$IMAGE_REF" "$IMAGE_SHA"
git push origin "refs/tags/$IMAGE_REF"

gh workflow run loadtest-images.yml --ref "$IMAGE_REF"
RUN_ID=""
until [ -n "$RUN_ID" ]; do
  RUN_ID=$(gh run list --workflow loadtest-images.yml --branch "$IMAGE_REF" \
    --commit "$IMAGE_SHA" --event workflow_dispatch --limit 1 \
    --json databaseId --jq '.[0].databaseId // empty')
  [ -n "$RUN_ID" ] || sleep 3
done
gh run watch "$RUN_ID" --exit-status

cp infra/load-test/product-only.tfvars.example infra/load-test/terraform.tfvars
terraform -chdir=infra/load-test apply
LOAD_TEST_PROFILE=product IMAGE_NS="$IMAGE_NS" IMAGE_TAG="$IMAGE_SHA" REPO_REF="$IMAGE_SHA" \
  ./infra/load-test/deploy/ssm-deploy.sh
```

`terraform apply` starts billing. This profile creates one small NAT instance plus private `k6`, `product`, and `mysql-product` Spot nodes in the same AZ. The unique tag makes the returned run ID unambiguous. `IMAGE_TAG="$IMAGE_SHA"` selects the immutable image and `REPO_REF="$IMAGE_SHA"` makes every host detach-checkout the matching Compose/config revision. Do not continue if the workflow watch or deployment fails.

### Wait for product-service and Flyway

Deployment starts product-service so Flyway can create the schema, but the SSM deploy command completing is not a readiness signal. In terminal 1, open the long-running product tunnel and leave it attached:

```bash
LOCAL_PORT=18084 ./infra/load-test/deploy/port-forward.sh product
```

In terminal 2, wait for an HTTP 200 response whose health status is `UP`:

```bash
ready=0
for _ in {1..90}; do
  if curl -fsS http://127.0.0.1:18084/actuator/health | jq -e '.status == "UP"' >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" = 1 ] || {
  echo "product-service was not UP after 180s; inspect the deployment, then destroy the rig if the run is aborted" >&2
  exit 1
}
```

Only after this succeeds is Flyway/application readiness established. Return to terminal 1 and press `Ctrl-C` to close the product tunnel before opening the database tunnel.

### Seed through the SSM tunnel

In terminal 1, start the database tunnel and leave it running; it owns the terminal until `Ctrl-C`:

```bash
LOCAL_PORT=13306 ./infra/load-test/deploy/port-forward.sh product-db
```

In terminal 2, seed the private product database:

```bash
MYSQL_PORT=13306 SEED_COUNT=1000 ./k6/seed/product-detail-seed.sh
```

Keep the tunnel open until seeding finishes. The seeder writes `k6/seed/productIds.json`; the AWS runner transfers that file to the k6 instance before each run.

### Warm up and smoke

Run one distribution at a time. These smoke runs warm the service and verify the AWS data path; do not include them in the recorded comparison:

```bash
export PROM_URL=http://127.0.0.1:9090/api/v1/write
REPO_REF="$IMAGE_SHA" DISTRIBUTION=hot STAGE=smoke ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=uniform STAGE=smoke ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=realistic STAGE=smoke ./k6/run-product-detail-aws.sh
```

To inspect scrape targets without a public endpoint, run `LOAD_TEST_PROFILE=product ./infra/load-test/deploy/port-forward.sh prometheus` and open `http://localhost:9090/targets`.

### Record identical comparisons

Keep the dataset, instance types, JVM heap, and stage definitions unchanged. Run all three distributions separately for each recorded stage:

```bash
REPO_REF="$IMAGE_SHA" DISTRIBUTION=hot STAGE=baseline ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=uniform STAGE=baseline ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=realistic STAGE=baseline ./k6/run-product-detail-aws.sh

REPO_REF="$IMAGE_SHA" DISTRIBUTION=hot STAGE=ramp ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=uniform STAGE=ramp ./k6/run-product-detail-aws.sh
REPO_REF="$IMAGE_SHA" DISTRIBUTION=realistic STAGE=ramp ./k6/run-product-detail-aws.sh
```

Each runner invocation configures Prometheus remote-write trend gauges as p50/p95/p99 and saves a checksum-verified pair under `k6/results/`: `<run>.summary.json` contains the machine-readable aggregate and `<run>.console.log` contains the complete terminal output. The runner exits nonzero rather than recording a partial or truncated transfer. Inspect the newest result before copying values into the log:

```bash
RESULT=$(ls -t k6/results/*.summary.json | head -1)
jq '{
  duration_ms: .state.testRunDurationMs,
  requests: .metrics.http_reqs.values.count,
  rps: (.metrics.http_reqs.values.count * 1000 / .state.testRunDurationMs),
  p50_ms: .metrics.http_req_duration.values.med,
  p95_ms: .metrics.http_req_duration.values["p(95)"],
  p99_ms: .metrics.http_req_duration.values["p(99)"],
  failure_rate: .metrics.http_req_failed.values.rate
}' "$RESULT"
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
