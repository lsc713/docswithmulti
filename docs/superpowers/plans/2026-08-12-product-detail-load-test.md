# Product Detail Load Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible product-detail load test with representative data and isolated k6, product-service, and product-MySQL EC2 nodes.

**Architecture:** Reuse the existing `infra/load-test` VPC, SSM, Docker image, and Prometheus patterns. Add two EC2 roles, one SQL seeder, one k6 scenario with a deterministic distribution helper, and one SSM runner. Local runs prove correctness; AWS private-IP runs produce capacity results.

**Tech Stack:** Bash, MySQL 8, k6 0.54, Docker Compose, Terraform, Spring Boot Actuator/Micrometer, Prometheus.

## Global Constraints

- Target only `GET /v1/products/{id}` on product-service port `8084`; bypass API Gateway.
- Every product has a 3-level category, 9 SKUs, 3 images, 2 variant attributes, and 2 descriptive attributes.
- Seed 100 products locally and 1,000 on AWS without deleting existing rows.
- Modes are `hot`, `uniform`, and `realistic`; realistic is 80% first-ten and 20% whole-pool.
- Do not add dependencies, caching, image downloads, a dashboard, or multi-AZ infrastructure.
- Never commit `k6/seed/productIds.json`.

---

### Task 1: Distribution helper and product-detail k6 scenario

**Files:**
- Create: `k6/helpers/product-distribution.js`
- Create: `k6/product-distribution-test.js`
- Create: `k6/product-detail.js`
- Modify: `k6/config.js`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `selectProductId(ids, distribution, random = Math.random)`
- Consumes: `BASE.PRODUCT`, `STAGE`, `DISTRIBUTION`, `k6/seed/productIds.json`

- [ ] **Step 1: Write the failing selector check**

```javascript
// k6/product-distribution-test.js
import { check } from 'k6';
import { selectProductId } from './helpers/product-distribution.js';

const ids = Array.from({ length: 100 }, (_, i) => i + 1);
const sequence = (...values) => () => values.shift();
export const options = { thresholds: { checks: ['rate==1'] } };

export default function () {
  check(null, {
    'hot stays in first ten': () => selectProductId(ids, 'hot', () => 0.99) === 10,
    'uniform spans all': () => selectProductId(ids, 'uniform', () => 0.99) === 100,
    'realistic 80 percent is hot': () => selectProductId(ids, 'realistic', sequence(0.79, 0.99)) === 10,
    'realistic tail spans all': () => selectProductId(ids, 'realistic', sequence(0.80, 0.99)) === 100,
  });
}
```

- [ ] **Step 2: Verify RED**

Run: `k6 run --iterations 1 k6/product-distribution-test.js`

Expected: missing `product-distribution.js` failure.

- [ ] **Step 3: Implement the selector**

```javascript
// k6/helpers/product-distribution.js
export function selectProductId(ids, distribution, random = Math.random) {
  if (ids.length < 10) throw new Error(`상품 ID가 10개 미만입니다: ${ids.length}`);
  if (distribution === 'hot') return ids[Math.floor(random() * 10)];
  if (distribution === 'uniform') return ids[Math.floor(random() * ids.length)];
  if (distribution === 'realistic') {
    const size = random() < 0.8 ? 10 : ids.length;
    return ids[Math.floor(random() * size)];
  }
  throw new Error(`알 수 없는 DISTRIBUTION=${distribution}. 가능: hot, uniform, realistic`);
}
```

- [ ] **Step 4: Verify GREEN**

Run: `k6 run --iterations 1 k6/product-distribution-test.js`

Expected: four checks pass.

- [ ] **Step 5: Add URL and artifact wiring**

In `k6/config.js`, add local `PRODUCT: 'http://localhost:8084'`, AWS `PRODUCT: 'http://10.0.1.23:8084'`, and `BASE.PRODUCT` with `PRODUCT_URL` override. Add `k6/seed/productIds.json` to `.gitignore`.

- [ ] **Step 6: Implement `k6/product-detail.js`**

Reuse the existing stage values verbatim without refactoring `stages.js`. Load numeric IDs with `SharedArray`, validate the mode during init, tag every request with `stage` and `distribution`, and use this request check:

```javascript
export function detail() {
  const id = selectProductId(ids, DISTRIBUTION);
  const res = http.get(`${BASE.PRODUCT}/v1/products/${id}`, {
    tags: { stage: STAGE, distribution: DISTRIBUTION },
  });
  const ok = check(res, {
    'HTTP 200': r => r.status === 200,
    'representative product shape': r => {
      try {
        const b = r.json();
        return b.id === id && b.category.length === 3 && b.images.length === 3 &&
          b.skus.length === 9 && b.variantOptions.length === 2 && b.specs.length === 2;
      } catch (_) { return false; }
    },
  });
  productDetailSuccess.add(ok, { distribution: DISTRIBUTION });
}
```

Smoke/baseline thresholds: HTTP failures `<1%`, `product_detail_success_rate >99%`. Ramp/stress/soak thresholds must not abort.

- [ ] **Step 7: Validate and commit**

```bash
printf '[1,2,3,4,5,6,7,8,9,10]' > k6/seed/productIds.json
k6 inspect k6/product-detail.js
git diff --check
git add .gitignore k6/config.js k6/helpers/product-distribution.js k6/product-distribution-test.js k6/product-detail.js
git commit -m "test(load): add product detail traffic distributions"
```

Expected: inspect succeeds. Delete the generated JSON afterward; it remains ignored.

---

### Task 2: Representative product seeder

**Files:**
- Create: `k6/seed/product-detail-seed.sh`
- Create: `k6/seed/product-detail-seed-test.sh`

**Interfaces:**
- Consumes: `SEED_COUNT`, MySQL connection variables, `PRODUCT_URL`, `OUT`
- Produces: JSON numeric ID array, default `k6/seed/productIds.json`

- [ ] **Step 1: Write the failing local self-check**

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT
SEED_COUNT=2 OUT="$OUT" "$ROOT/k6/seed/product-detail-seed.sh"
[ "$(jq length "$OUT")" -eq 2 ]
id=$(jq -r '.[0]' "$OUT")
curl -sf "${PRODUCT_URL:-http://localhost:8084}/v1/products/$id" | jq -e '
  (.category|length)==3 and (.images|length)==3 and (.skus|length)==9 and
  (.variantOptions|length)==2 and (.specs|length)==2' >/dev/null
```

- [ ] **Step 2: Verify RED**

Run: `bash k6/seed/product-detail-seed-test.sh`

Expected: missing seeder failure. Local product MySQL and product-service must be running.

- [ ] **Step 3: Implement the seeder shell**

Use these exact defaults and guards:

```bash
set -euo pipefail
SEED_COUNT="${SEED_COUNT:-100}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3310}"
MYSQL_USER="${MYSQL_USER:-product}"
MYSQL_PASS="${MYSQL_PASS:-product}"
MYSQL_DB="${MYSQL_DB:-product_db}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/productIds.json}"
PREFIX="product_lt_$(date +%s)_$$_"
command -v mysql >/dev/null
command -v jq >/dev/null
[[ "$SEED_COUNT" =~ ^[1-9][0-9]*$ ]]
db() { mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" "$@"; }
```

Send this procedure through `db`; it uses `LAST_INSERT_ID()` rather than predicting IDs:

```sql
DROP PROCEDURE IF EXISTS seed_product_detail_loadtest;
DELIMITER //
CREATE PROCEDURE seed_product_detail_loadtest(IN p_count INT, IN pfx VARCHAR(80))
BEGIN
  DECLARE i INT DEFAULT 1;
  DECLARE c INT;
  DECLARE s INT;
  DECLARE root_id, mid_id, leaf_id BIGINT;
  DECLARE color_attr, size_attr, material_attr, origin_attr BIGINT;
  DECLARE red_id, green_id, blue_id, small_id, medium_id, large_id BIGINT;
  DECLARE material_id, origin_id, product_id, sku_id, color_id, size_id BIGINT;
  DECLARE color_name, size_name VARCHAR(20);

  START TRANSACTION;
  INSERT INTO category(parent_id,name,level) VALUES(NULL,CONCAT(pfx,'root'),1);
  SET root_id=LAST_INSERT_ID();
  INSERT INTO category(parent_id,name,level) VALUES(root_id,CONCAT(pfx,'mid'),2);
  SET mid_id=LAST_INSERT_ID();
  INSERT INTO category(parent_id,name,level) VALUES(mid_id,CONCAT(pfx,'leaf'),3);
  SET leaf_id=LAST_INSERT_ID();

  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'color')); SET color_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'size')); SET size_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'material')); SET material_attr=LAST_INSERT_ID();
  INSERT INTO attribute(name) VALUES(CONCAT(pfx,'origin')); SET origin_attr=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'red'); SET red_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'green'); SET green_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(color_attr,'blue'); SET blue_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'S'); SET small_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'M'); SET medium_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(size_attr,'L'); SET large_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(material_attr,'cotton'); SET material_id=LAST_INSERT_ID();
  INSERT INTO attribute_value(attribute_id,value) VALUES(origin_attr,'KR'); SET origin_id=LAST_INSERT_ID();

  WHILE i <= p_count DO
    INSERT INTO product(name,category_id) VALUES(CONCAT(pfx,i),leaf_id);
    SET product_id=LAST_INSERT_ID();
    INSERT INTO product_attribute(product_id,attribute_id,is_variant) VALUES
      (product_id,color_attr,TRUE),(product_id,size_attr,TRUE),
      (product_id,material_attr,FALSE),(product_id,origin_attr,FALSE);

    SET c=1;
    WHILE c <= 3 DO
      SET color_id=CASE c WHEN 1 THEN red_id WHEN 2 THEN green_id ELSE blue_id END;
      SET color_name=CASE c WHEN 1 THEN 'red' WHEN 2 THEN 'green' ELSE 'blue' END;
      SET s=1;
      WHILE s <= 3 DO
        SET size_id=CASE s WHEN 1 THEN small_id WHEN 2 THEN medium_id ELSE large_id END;
        SET size_name=CASE s WHEN 1 THEN 'S' WHEN 2 THEN 'M' ELSE 'L' END;
        INSERT INTO product_sku(product_id,sku_code,option_summary,price)
          VALUES(product_id,CONCAT(pfx,'sku_',i,'_',c,'_',s),CONCAT(color_name,'/',size_name),10000+i);
        SET sku_id=LAST_INSERT_ID();
        INSERT INTO product_stock(sku_id,available_qty) VALUES(sku_id,100);
        INSERT INTO sku_attribute_value(sku_id,attribute_value_id) VALUES(sku_id,color_id),(sku_id,size_id);
        SET s=s+1;
      END WHILE;
      SET c=c+1;
    END WHILE;

    INSERT INTO product_image(product_id,s3_key,sort_order) VALUES
      (product_id,CONCAT(pfx,i,'/1.jpg'),0),(product_id,CONCAT(pfx,i,'/2.jpg'),1),(product_id,CONCAT(pfx,i,'/3.jpg'),2);
    INSERT INTO product_descriptive_value(product_id,attribute_value_id) VALUES
      (product_id,material_id),(product_id,origin_id);
    SET i=i+1;
  END WHILE;
  COMMIT;
END//
DELIMITER ;
CALL seed_product_detail_loadtest(${SEED_COUNT}, '${PREFIX}');
DROP PROCEDURE seed_product_detail_loadtest;
```

Export only this exact prefix and validate count:

```bash
db -N -B -r -e "SELECT JSON_ARRAYAGG(id) FROM (SELECT id FROM product WHERE LEFT(name,CHAR_LENGTH('${PREFIX}'))='${PREFIX}' ORDER BY id) p" > "$OUT"
actual=$(jq length "$OUT")
[ "$actual" -eq "$SEED_COUNT" ] || { echo "기대 $SEED_COUNT != 실제 $actual" >&2; exit 1; }
```

- [ ] **Step 4: Verify GREEN and commit**

```bash
chmod +x k6/seed/product-detail-seed.sh k6/seed/product-detail-seed-test.sh
bash k6/seed/product-detail-seed-test.sh
git add k6/seed/product-detail-seed.sh k6/seed/product-detail-seed-test.sh
git commit -m "test(load): seed representative product details"
```

---

### Task 3: Product application observability

**Files:**
- Modify: `product-service/src/main/resources/application.yml`

**Interfaces:** Produces `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.

- [ ] **Step 1: Confirm current RED**

Run against the local service: `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8084/actuator/prometheus`

Expected: not `200`.

- [ ] **Step 2: Add the existing metrics pattern**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :product-service:test
curl -sf http://localhost:8084/actuator/prometheus | rg 'http_server_requests|jvm_memory'
git add product-service/src/main/resources/application.yml
git commit -m "chore(product): expose load test metrics"
```

Restart product-service before the curl. Expected: tests pass and metrics appear.

---

### Task 4: Product EC2 and deployment roles

**Files:**
- Modify: `infra/load-test/instances.tf`
- Create: `infra/load-test/deploy/mysql-product.compose.yml`
- Create: `infra/load-test/deploy/product.compose.yml`
- Modify: `infra/load-test/deploy/Dockerfile`
- Modify: `infra/load-test/deploy/ssm-deploy.sh`
- Modify: `infra/load-test/deploy/port-forward.sh`
- Modify: `.github/workflows/loadtest-images.yml`
- Modify: `infra/load-test/README.md`

**Interfaces:** Produces `product=10.0.1.23:8084` and `mysql-product=10.0.1.33:3306`.

- [ ] **Step 1: Add instance entries**

```hcl
product       = { type = "c7g.xlarge", ip = "10.0.1.23", disk = 30 }
mysql-product = { type = "m7g.large", ip = "10.0.1.33", disk = 100 }
```

Use the existing Spot toggle; add no network resource.

- [ ] **Step 2: Add MySQL Compose**

Copy the existing payment-DB shape with service/container `mysql-product`, database/user/password `product`, host port 3306, 500 max connections, 4G buffer pool, and `mysql-product-data` volume.

- [ ] **Step 3: Add product Compose**

```yaml
services:
  product-service:
    image: ${IMAGE_NS:?Docker Hub 네임스페이스(IMAGE_NS) 필요}/cancel-loadtest:product-${IMAGE_TAG:-latest}
    pull_policy: always
    container_name: product-service
    restart: unless-stopped
    network_mode: host
    environment:
      SPRING_DATASOURCE_URL: "jdbc:mysql://10.0.1.33:3306/product_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true"
      SPRING_DATASOURCE_USERNAME: product
      SPRING_DATASOURCE_PASSWORD: product
      SPRING_DATA_REDIS_HOST: 10.0.1.40
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "10.0.1.40:9092"
      APP_S3_ENDPOINT: "http://10.0.1.40:9000"
      APP_S3_REGION: us-east-1
      APP_S3_BUCKET: product-images
      APP_S3_ACCESS_KEY: loadtest
      APP_S3_SECRET_KEY: loadtest
      APP_S3_PRESIGN_TTL_SECONDS: "300"
      APP_S3_PATH_STYLE: "true"
      JAVA_TOOL_OPTIONS: "-Xmx4g -XX:+UseG1GC ${OTEL_JAVAAGENT:-}"
      OTEL_SERVICE_NAME: product
      OTEL_EXPORTER_OTLP_ENDPOINT: "http://10.0.1.50:4317"
```

Do not add MinIO: presigning does not contact S3 and k6 does not download images.

- [ ] **Step 4: Wire CI, SSM, and tunnels**

- Add `product-service/**` and `{ module: product-service, tag: product }` to the image workflow.
- Change deploy order to `infra mysql-payment mysql-risk cold-db mysql-product cold-svc risk payment product`.
- Map `mysql-product` and `product` to their Compose files.
- Add tunnel cases `product -> product:8084` and `product-db -> mysql-product:3306`.
- Update the Dockerfile service-list comment and README topology from 9 to 11 nodes.

- [ ] **Step 5: Validate and commit**

```bash
terraform -chdir=infra/load-test fmt -check
terraform -chdir=infra/load-test validate
IMAGE_NS=test docker compose -f infra/load-test/deploy/mysql-product.compose.yml config -q
IMAGE_NS=test docker compose -f infra/load-test/deploy/product.compose.yml config -q
bash -n infra/load-test/deploy/ssm-deploy.sh
bash -n infra/load-test/deploy/port-forward.sh
git diff --check
git add .github/workflows/loadtest-images.yml infra/load-test/instances.tf infra/load-test/README.md \
  infra/load-test/deploy/Dockerfile infra/load-test/deploy/mysql-product.compose.yml \
  infra/load-test/deploy/product.compose.yml infra/load-test/deploy/ssm-deploy.sh \
  infra/load-test/deploy/port-forward.sh
git commit -m "feat(load): add isolated product service rig"
```

---

### Task 5: Prometheus and AWS k6 runner

**Files:**
- Modify: `infra/load-test/observability/prometheus.yml`
- Modify: `infra/load-test/observability/docker-compose.yml`
- Create: `k6/run-product-detail-aws.sh`

**Interfaces:** Consumes local `productIds.json`; runs k6 on the `Role=k6` host and remote-writes to `10.0.1.50:9090`.

- [ ] **Step 1: Add scrape targets**

Add app `10.0.1.23:8084`, node targets `10.0.1.23:9100` and `10.0.1.33:9100`, and MySQL target `mysqld-exporter-product:9104` with `db: product`.

Add this Compose service:

```yaml
  mysqld-exporter-product:
    image: prom/mysqld-exporter:v0.14.0
    container_name: mysqld-exporter-product
    restart: unless-stopped
    environment:
      DATA_SOURCE_NAME: "root:root@(10.0.1.33:3306)/"
```

- [ ] **Step 2: Implement the strict SSM runner**

Validate `DISTRIBUTION` by case statement, require the JSON file, resolve the running `Role=k6` instance, encode the file, and build SSM parameters with `jq -n --arg`. The remote command is:

```bash
set -e
mkdir -p /opt/loadtest
if [ ! -d /opt/loadtest/repo/.git ]; then git clone --depth 1 "$REPO_URL" /opt/loadtest/repo; else git -C /opt/loadtest/repo pull --ff-only; fi
printf '%s' "$IDS_B64" | base64 -d > /opt/loadtest/repo/k6/seed/productIds.json
docker run --rm --network host -v /opt/loadtest/repo:/work -w /work \
  -e TARGET=aws -e STAGE="$STAGE" -e DISTRIBUTION="$DISTRIBUTION" \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://10.0.1.50:9090/api/v1/write \
  grafana/k6:0.54.0 run -o experimental-prometheus-rw k6/product-detail.js
```

Poll as `ssm-deploy.sh` does; exit nonzero on Failed, Cancelled, or TimedOut.

- [ ] **Step 3: Validate and commit**

```bash
IMAGE_NS=test docker compose -f infra/load-test/observability/docker-compose.yml config -q
bash -n k6/run-product-detail-aws.sh
git diff --check
git add infra/load-test/observability/prometheus.yml \
  infra/load-test/observability/docker-compose.yml k6/run-product-detail-aws.sh
git commit -m "feat(load): observe and run product detail load"
```

---

### Task 6: Runbook and final verification

**Files:**
- Create: `docs/load-test/product-detail.md`
- Modify: `k6/README.md`

**Interfaces:** Documents local smoke and AWS apply/deploy/seed/run/record/destroy.

- [ ] **Step 1: Document exact local commands**

```bash
docker compose up -d mysql-product redis minio minio-init kafka1 kafka2 kafka3
./gradlew :product-service:bootRun
SEED_COUNT=100 ./k6/seed/product-detail-seed.sh
DISTRIBUTION=hot STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
DISTRIBUTION=uniform STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
DISTRIBUTION=realistic STAGE=smoke SCRIPT=k6/product-detail.js ./k6/run-stage.sh
```

State that local timings are discarded.

- [ ] **Step 2: Document exact AWS order**

```bash
gh workflow run loadtest-images.yml
terraform -chdir=infra/load-test apply
IMAGE_NS=<dockerhub-user> ./infra/load-test/deploy/ssm-deploy.sh
LOCAL_PORT=13306 ./infra/load-test/deploy/port-forward.sh product-db
MYSQL_PORT=13306 SEED_COUNT=1000 ./k6/seed/product-detail-seed.sh
DISTRIBUTION=hot STAGE=smoke ./k6/run-product-detail-aws.sh
DISTRIBUTION=uniform STAGE=smoke ./k6/run-product-detail-aws.sh
DISTRIBUTION=realistic STAGE=smoke ./k6/run-product-detail-aws.sh
```

Then document warmup, identical baseline/ramp runs, the design metric checklist, result recording, and mandatory `terraform -chdir=infra/load-test destroy`.

- [ ] **Step 3: Run the complete local verification**

```bash
k6 run --iterations 1 k6/product-distribution-test.js
bash k6/seed/product-detail-seed-test.sh
./gradlew :product-service:test
terraform -chdir=infra/load-test fmt -check
terraform -chdir=infra/load-test validate
IMAGE_NS=test docker compose -f infra/load-test/deploy/mysql-product.compose.yml config -q
IMAGE_NS=test docker compose -f infra/load-test/deploy/product.compose.yml config -q
docker compose -f infra/load-test/observability/docker-compose.yml config -q
git diff --check
```

Expected: all exit 0. Start only required local dependencies; do not provision AWS during verification.

- [ ] **Step 4: Commit and review before AWS operations**

```bash
git add docs/load-test/product-detail.md k6/README.md
git commit -m "docs(load): add product detail runbook"
```

Review the complete diff against the approved design. Image publishing and AWS apply are separate operational actions performed only when explicitly requested.
