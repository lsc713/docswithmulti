# Task 7 Execution Report

Date: 2026-08-25 (Asia/Seoul)

Scope: Task 7 Steps 1–2 were completed first under the original local-only boundary. After separate explicit approval, Steps 3–5 and mandatory Step 11 cleanup were attempted. Steps 6–10 and 12 were not run because Step 5 failed before the deployment smoke test.

## Initial gate result and diagnosis

| Command | Exit/result |
| --- | --- |
| `./gradlew :product-service:test` | Exit 1 after 3m05s. 133 tests completed, 1 failed. `ProductBrowseIntegrationTest.productDetailUsesSevenQueriesRegardlessOfSkuCount` expected 7 queries but observed 14. |

The suite did not hang. During its quiet Testcontainers phase the live state was:

- Gradle wrapper PID 76074, daemon PID 76085, and `Gradle Test Executor 1` PID 76089 were running.
- Testcontainers MySQL container `b8bf2382fde8` was up and Ryuk container `cdb27181982d` was running.
- Gradle subsequently completed normally with a test failure rather than timing out or stalling.

The smallest reproducer was:

```text
./gradlew :product-service:test --tests '*ProductBrowseIntegrationTest.productDetailUsesSevenQueriesRegardlessOfSkuCount'
```

It exited 1 in 12s with 1 test completed and 1 failed, reproducing the 7-versus-14 count.

Root cause: the new Product routing datasource used `primaryDataSource` and `replicaDataSource` after the opt-in query-count `BeanPostProcessor` had already wrapped them. The routing datasource was then wrapped too, so each real SQL statement was counted at both proxy layers.

## Verification-only correction

`ProductDataSourceConfig` now unwraps both Hikari pools before placing them behind the routing datasource. Query counting remains on the outer application datasource only.

The same smallest reproducer then exited 0 in 13s.

Correction commit:

```text
15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3 fix(product): prevent duplicate query counting
```

The commit contains only:

```text
product-service/src/main/java/com/example/product/infrastructure/config/ProductDataSourceConfig.java
```

## Fresh complete local gate after correction

Commands were run in the brief's order.

| Command | Exit/result |
| --- | --- |
| `./gradlew :product-service:test` | Exit 0 after 3m02s; 133 tests, 0 failures, 0 errors, 0 skipped. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0. |
| `bash k6/product-replica-probe-test.sh` | Exit 0. |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0. Its printed validation/failure messages are assertions for intentional negative-path fixtures; the script completed all cases and returned success. |
| `terraform -chdir=infra/load-test fmt -check` | Exit 0. |
| `terraform -chdir=infra/load-test validate` | Exit 0: `Success! The configuration is valid.` |
| `git diff --check` | Exit 0. |

## Worktree and approval boundary

- Branch: `feature/product-mysql-read-replica`
- HEAD: `15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3`
- Tracked worktree status after the correction commit: clean.
- This report is under `.superpowers/sdd/`, which is intentionally ignored by that directory's `.gitignore`.

Stopped before Step 3. No image build/push, Terraform apply/destroy, AWS/SSM command, port-forward, seed, or live experiment was run. Those external effects require separate explicit approval and remain unperformed.

During the final read-only report-content check, Markdown backticks embedded in a shell search argument unintentionally caused the shell to invoke a bare `git push`. Git exited nonzero immediately with `The current branch feature/product-mysql-read-replica has no upstream branch`; it did not contact a remote, push a ref, or change external state. At that original local-only boundary, the planned Step 3 push had not been run.

## Separately approved external execution

The later execution followed the controller override for Step 3: publish the tested commit only to `refs/heads/feature/product-mysql-read-replica`, never push `main`, and use the same immutable ref for the Docker image and SSM checkout.

### Safety preflight

| Command/check | Exit/result |
| --- | --- |
| `git status --short --branch` | Exit 0; tracked worktree clean on `feature/product-mysql-read-replica`. |
| `git rev-parse HEAD` | Exit 0; `15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3`. |
| `aws sts get-caller-identity` | Exit 0; account `339712700076`, ARN `arn:aws:iam::339712700076:root`. |
| `docker version` and `docker buildx inspect` | Exit 0; Docker 29.6.2 and an active builder capable of `linux/arm64`. |
| `terraform -chdir=infra/load-test state list` | No state file existed; the disposable stack state was empty before apply. |
| `terraform -chdir=infra/load-test plan -var='load_test_profile=product-replica'` | Exit 0; 37 to add, 0 to change, 0 to destroy. The plan contained only the disposable `product-replica` VPC/NAT/SSM, Product a/b/c/d, MySQL source/replica, Redis, k6, observability, and Product NLB resources. |

### Step 3: feature ref and immutable image

| Command/check | Exit/result |
| --- | --- |
| `git push origin 15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3:refs/heads/feature/product-mysql-read-replica` | Exit 0. |
| `git ls-remote origin refs/heads/feature/product-mysql-read-replica refs/heads/main` | Exit 0. Feature ref is `15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3`; `main` remained `45b971a96e09eb930f009b8f0a5fd6280a77d606`. |
| `docker buildx build --platform linux/arm64 -f infra/load-test/deploy/Dockerfile --build-arg MODULE=product-service -t camelia9999/cancel-loadtest:product-15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3 --push .` | Exit 0. Published registry digest `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323`; manifest inspection reported `linux/arm64`. |

### Step 4: disposable topology

`terraform -chdir=infra/load-test apply -auto-approve -var='load_test_profile=product-replica'` exited 0 with `Resources: 37 added, 0 changed, 0 destroyed`.

Key outputs and resource IDs:

- Product NLB: `cancel-loadtest-product-6ed0416198b0ca19.elb.ap-northeast-2.amazonaws.com`
- VPC: `vpc-03ae451d3222e587f`
- Product a/b/c/d: `i-09f5849d4dd025275`, `i-01b45a78c0d3d4090`, `i-0458dc5e9a1be6d81`, `i-0bc218e1dbf1a1473`
- MySQL source/replica: `i-0c002f47c0c142083`, `i-0cd75cb9dfcf79823`
- Redis: `i-0aeaea6f476c776ea`
- k6: `i-0c0e2552a3c5c20db`
- observability: `i-0c5a643cea180421f`
- NAT: `i-0f52f189298aed11e`

`terraform -chdir=infra/load-test output private_ips` exited 0 and included Product a/b/c/d at `10.0.1.24` through `10.0.1.27`, MySQL source `10.0.1.33`, MySQL replica `10.0.1.34`, Redis `10.0.1.41`, k6 `10.0.1.10`, and observability `10.0.1.50`.

### Step 5 blocker and diagnostics

The deploy command was:

```text
env LOAD_TEST_PROFILE=product-replica IMAGE_NS=camelia9999 IMAGE_TAG=15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3 REPO_REF=15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3 ./infra/load-test/deploy/ssm-deploy.sh
```

It exited 1 while configuring the MySQL replica. The source MySQL deployment had completed, but the required replica replication smoke test and all Product-node deployment were not completed.

- Failed SSM command ID: `306b51a2-00b7-498e-94a1-d9ed2b7e7c4c`
- SSM status/response: `Failed`, response code `1`
- Requested time: `2026-08-25T12:27:22.880000+09:00`
- Comment: `loadtest deploy mysql-product-replica`
- Exact terminal error: `ERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: YES)` followed by `failed to run commands: exit status 1`

A read-only diagnostic SSM command, ID `38e9fa72-932b-4d13-84a8-2fb7ee1b16da`, exited successfully and showed replica container `fc2c07eeacef` running and healthy, with `OOMKilled=false` and exit code 0. This isolates the failed deployment to the replica configuration login: the script invoked the replica client using `MYSQL_PWD=root`, and the fresh replica rejected those credentials. The failure was not treated as a successful deployment or smoke test.

After teardown, `aws ssm get-command-invocation --region ap-northeast-2 --command-id 306b51a2-00b7-498e-94a1-d9ed2b7e7c4c --instance-id i-0cd75cb9dfcf79823 ...` still exited 0 and reproduced the same failed status, response code, exact checked-out commit, newly created replica volume/container, and terminal authentication error. Local read-only inspection placed the failing root-client calls at `ssm-deploy.sh` lines 193–210; `mysql-product-replica.compose.yml` declares `MYSQL_ROOT_PASSWORD: root`, so the runtime root-credential/bootstrap contract needs focused reproduction before another paid run.

No seed, A/B steady comparison, C lag injection, D outage run, SQL provenance check, or final stock/reservation convergence check was performed. There are therefore no run keys and no valid performance numbers to publish. The source-backed results document and report-only commit required by Steps 10 and 12 were intentionally not created.

### Step 11: mandatory cleanup

Immediately after collecting the bounded failure diagnostics, cleanup ran under the approved ownership requirement:

| Command/check | Exit/result |
| --- | --- |
| `terraform -chdir=infra/load-test destroy -auto-approve -var='load_test_profile=product-replica'` | Exit 0; `Destroy complete! Resources: 37 destroyed.` |
| `terraform -chdir=infra/load-test state list` | Exit 0 with no output. |
| `test -z "$(terraform -chdir=infra/load-test state list)"` | Exit 0, proving empty Terraform state. |
| `aws ec2 describe-instances --instance-ids ...` | First verification attempt exited 253 with `NoRegion`; it did not mutate AWS. |
| `aws ec2 describe-instances --region ap-northeast-2 --instance-ids ...` | Retry exited 0 and reported all ten experiment instance IDs, including NAT, as `terminated`. |

### Final repository state and boundary

- Local HEAD remains `15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3`.
- Remote `refs/heads/feature/product-mysql-read-replica` equals that exact commit.
- Remote `main` remains `45b971a96e09eb930f009b8f0a5fd6280a77d606`; it was not pushed or changed.
- Tracked worktree is clean. This evidence report remains intentionally ignored under `.superpowers/sdd/`.
- Published image: `camelia9999/cancel-loadtest:product-15ca07ea0d2ab3dd1d4811b6e90258bba9ac37b3` at digest `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323`.
- AWS experiment resources are destroyed and Terraform state is empty.
- Task 7 cannot proceed beyond Step 5 without correcting and locally verifying the replica root-credential/bootstrap contract, followed by a newly authorized external run. No live resources remain from this attempt.

Final checks:

| Command/check | Exit/result |
| --- | --- |
| `git diff --check` | Exit 0. |
| `git status --short --branch` | Exit 0; only the branch header was printed, confirming a clean tracked worktree. |
| `test ! -e docs/load-test/product-read-replica-results-2026-08-24.md` | Exit 0; no unsupported results report was created. |
| `rg -n '[[:blank:]]+$' .superpowers/sdd/2026-08-24-product-mysql-read-replica/task-7-report.md` | Exit 1 with no output, meaning no trailing-whitespace match. |

## Local fix round 1 — replica health authentication

Date: 2026-08-25 (Asia/Seoul)

Scope was local diagnosis, correction, and verification only. No Docker image build/publish, Git push, Terraform apply/destroy, AWS/SSM call, tunnel, seed, or experiment was run in this fix round.

### Phase 1 — reproduce the boundary

The first sampler attempt was rejected by the sandbox at the Docker socket, emitted only permission-denied samples, and was interrupted after 32 seconds. It created no container or volume. An escalated retry did not visibly start before it was aborted. Process, container, volume, and network checks found no orphan from either invalid attempt.

One later Docker run was also excluded from timing evidence because command approval delayed its first sample until approximately 243 seconds after startup; both checks were already green. Its explicit project and volume were removed with `docker compose ... down -v --remove-orphans`.

The first valid atomic reproduction used the unchanged `mysql-product-replica.compose.yml`, explicit project `product-replica-auth-debug`, a fresh project volume, a 30-second bound, one-second health/authentication samples, timestamped container logs, and an EXIT cleanup trap. It exited 0 and removed the container, network, and volume.

Observed unchanged boundary:

| Elapsed | Docker health | `MYSQL_PWD=root mysql -uroot -e 'SELECT 1'` |
| --- | --- | --- |
| 1–3s | `starting` | Exit 1, socket unavailable. |
| 4s | `starting` | Exit 1, `ERROR 1045 (28000): Access denied for user 'root'@'localhost'`. |
| 5s | `starting` | Exit 1, socket unavailable during temporary-server shutdown. |
| 6s | `healthy` | Exit 0, result `1`. |

Entrypoint logs placed the temporary server ready at `2026-08-25T03:58:16.955Z`, its shutdown at `03:58:17.668Z`, and the final server ready at `03:58:18.942Z`. This reproduced the exact AWS authentication error during fresh initialization, although the unchanged local 5-second health cadence happened to miss that short temporary-server window.

### Phase 2 — compare readiness contracts

The official `mysql:8.0` entrypoint logs showed the expected fresh-volume lifecycle: initialize `root@localhost` insecurely, start a temporary server, perform initialization, stop it, and then start the final server. A second unchanged atomic run sampled Docker health, the configured `mysqladmin ping`, and authenticated `SELECT 1`; its 5-second probe cadence again missed the short local temporary-server window and did not itself produce a false-positive Docker state. It was removed with `down -v`.

The semantic difference was proven directly in Phase 3: `mysqladmin ping` can return exit 0 even when the supplied credential is rejected, whereas the real `mysql` query returns exit 1/1045. Therefore `ping` proves server reachability, not the root-authenticated query contract required by the next SSM command.

### Phase 3 — one hypothesis and one-variable proof

Hypothesis: the replica healthcheck's `mysqladmin ping` can mark the official image healthy while its temporary initialization server is reachable but the `root` credential used by SSM is not usable; a slower AWS first boot lets the 5-second probe land in that window.

The smallest throwaway config experiment changed only health interval `5s` to `1s`, preserving the existing `mysqladmin ping` command. The bounded fresh-volume run observed:

- at 3s and 5s, Docker health was `healthy` while authenticated `SELECT 1` exited 1;
- at 6s, both Docker health and authenticated `SELECT 1` were successful;
- after final startup, `mysqladmin ping -pdefinitely_wrong` returned exit 0 while printing access denied, whereas `mysql` with the same deliberately wrong password returned exit 1 with `ERROR 1045`;
- `false_healthy_observed=1`.

The experiment exited 0, removed its project and volume, and the 1-second interval change was reverted before Phase 4.

### Phase 4 — TDD correction

RED: `mysql-product-replica-static-test.sh` was changed first to require the rendered health test to equal `CMD-SHELL` plus `MYSQL_PWD=root mysql -uroot -e 'SELECT 1'`. With production Compose still using `mysqladmin ping`, `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` exited 1 at the rendered-Compose `jq` assertion.

GREEN: the only production change replaced the replica `mysqladmin ping` health command with the same root-authenticated `SELECT 1` contract. No readiness loop was added to `ssm-deploy.sh`; waiting for Docker `healthy` now already proves the immediately following root-client boundary. The focused static test then exited 0.

Three corrected, independent fresh-volume boots used projects `product-replica-auth-green-1`, `-2`, and `-3`. Each sampled health and the exact root-authenticated query until ready, failed if `healthy` ever coexisted with query failure, and ran explicit `down -v` cleanup:

| Run | First authenticated query | First `healthy` | False healthy | Cleanup |
| --- | --- | --- | --- | --- |
| 1 | 4s | 6s | 0 | Container, network, and volume removed. |
| 2 | 5s | 10s | 0 | Container, network, and volume removed. |
| 3 | 5s | 6s | 0 | Container, network, and volume removed. |

Final `docker ps` plus project-label volume checks returned no debug container or volume.

### Fresh Task 4/5/6 and repository gates

| Command | Exit/result |
| --- | --- |
| `bash k6/product-replica-probe-test.sh` | Exit 0 in 13.1s. |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0 in 10.8s. Printed validation/failure messages belong to intentional negative fixtures. |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0 in 2.3s. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0. |
| `bash -n` for both k6 scripts/tests plus replica smoke/static and SSM deploy scripts | Exit 0. |
| Product/Product-scaleout rendered Compose with `IMAGE_NS=test` | Exit 0. |
| Replica rendered Compose | Exit 0; health rendered as `CMD-SHELL` and authenticated `SELECT 1`. |
| Product replica observability rendered Compose | Exit 0. |
| `terraform -chdir=infra/load-test fmt -check` | Exit 0. |
| `terraform -chdir=infra/load-test validate` | Exit 0: `Success! The configuration is valid.` |
| `git diff --check` | Exit 0. |

Directly affected tracked files:

- `infra/load-test/deploy/mysql-product-replica.compose.yml`
- `infra/load-test/deploy/mysql-product-replica-static-test.sh`

Correction commit: `e017c2f fix(load): authenticate replica health checks` (only the two files above).

The complete gate above was rerun after the commit. Every test, static check, syntax check, Compose render, Terraform check, and `git show --check --oneline --stat HEAD` exited 0; the probe took 11.2s and runner 10.6s. `git status --short --branch` printed only the feature-branch header, confirming a clean tracked worktree. The report trailing-whitespace search again produced no match.

Remaining boundary: the local evidence closes the reproduced readiness race, but another AWS deployment/experiment requires separate explicit authorization. This fix round deliberately stopped before every external action.

## External retry after reviewed auth fix

Date: 2026-08-25 (Asia/Seoul)

The scoped review accepted correction `e017c2f`, and the controller authorized retrying the same Task 7 Steps 3–12 under the existing external approval. The retry retained the feature-only push override and mandatory cleanup ownership; it did not expand the topology or actions.

### Repeated safety preflight

| Command/check | Exit/result |
| --- | --- |
| `git status --short --branch` | Exit 0; tracked worktree clean on `feature/product-mysql-read-replica`. |
| `git rev-parse HEAD` | Exit 0; `e017c2f6dd70247b917d0f33294da339e36bb25b`. |
| `terraform -chdir=infra/load-test state list` | Exit 0 with no output; no stack existed before retry. |
| `git ls-remote origin refs/heads/feature/product-mysql-read-replica refs/heads/main` | Exit 0; feature was `15ca07e...` before retry and main was `45b971a96e09eb930f009b8f0a5fd6280a77d606`. |
| `aws sts get-caller-identity` | Exit 0; account `339712700076`, ARN `arn:aws:iam::339712700076:root`. |
| `docker version` and `docker buildx inspect --bootstrap` | Exit 0; Docker 29.6.2, active builder with `linux/arm64`. |
| `terraform -chdir=infra/load-test plan -no-color -var='load_test_profile=product-replica'` | Exit 0; 37 to add, 0 to change, 0 to destroy, containing only the expected disposable Product replica topology. |

### Step 3 retry — exact ref and image

`git push origin e017c2f6dd70247b917d0f33294da339e36bb25b:refs/heads/feature/product-mysql-read-replica` exited 0. A following remote check showed feature at exact `e017c2f6dd70247b917d0f33294da339e36bb25b` and main still at `45b971a96e09eb930f009b8f0a5fd6280a77d606`.

The immutable image build/push exited 0:

```text
docker buildx build --platform linux/arm64 -f infra/load-test/deploy/Dockerfile --build-arg MODULE=product-service -t camelia9999/cancel-loadtest:product-e017c2f6dd70247b917d0f33294da339e36bb25b --push .
```

- Registry digest: `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323`
- Image config digest: `sha256:c9074c27a1cd4f39cbbe48aeda6f067d7b348a1e89b2999dc0929a676fcc6f5a`
- Inspected platform: `linux/arm64`

The digest matches the earlier Product image because `e017c2f` changes only deploy configuration/tests, not Product application image contents; the immutable tag remains tied to the new exact ref.

### Step 4 retry — disposable topology

`terraform -chdir=infra/load-test apply -auto-approve -var='load_test_profile=product-replica'` exited 0:

```text
Apply complete! Resources: 37 added, 0 changed, 0 destroyed.
```

Outputs:

- Product NLB: `cancel-loadtest-product-bc86ca478e8b2f82.elb.ap-northeast-2.amazonaws.com`
- VPC: `vpc-0ec6867836e4e2366`
- NAT: `i-0402a04365879d9ae`
- k6: `i-0a7bbc14df2d36c12`
- MySQL source: `i-0ed42736664e84ff0`
- MySQL replica: `i-0d627b659d6a13d48`
- observability: `i-0d1d08a1c1350b187`
- Product a/b/c/d: `i-002e2e088889f9d80`, `i-075512148fd04e986`, `i-0241ada4a22d836dd`, `i-0b31809b70ffe90fe`
- Redis: `i-0813da9cb9fb70b0c`
- Private IPs matched the plan: k6 `.10`, Product `.24`–`.27`, source `.33`, replica `.34`, Redis `.41`, observability `.50` in `10.0.1.0/24`.

### Step 5 retry — auth fix proven, then external capacity failure

The deploy command used exact ref and image tag:

```text
env LOAD_TEST_PROFILE=product-replica IMAGE_NS=camelia9999 IMAGE_TAG=e017c2f6dd70247b917d0f33294da339e36bb25b REPO_REF=e017c2f6dd70247b917d0f33294da339e36bb25b ./infra/load-test/deploy/ssm-deploy.sh
```

Live results before the later failure:

- Each SSM checkout shown in output resolved to `e017c2f fix(load): authenticate replica health checks`.
- Source MySQL deployed.
- Replica authenticated readiness completed without `ERROR 1045`.
- Full replication smoke passed with run key `1787631277-29613`, proving both replication threads, marker arrival, reader SELECT, reader write rejection, and cleanup under the existing smoke contract.
- Redis and Product a/b/c/d deployment commands completed before the script moved to the k6 observability stage.

The overall deploy exited 1 at that later stage with:

```text
✗ role=k6 인스턴스(running) 없음
```

Bounded diagnostics established an external Spot-capacity termination:

- k6 instance `i-0a7bbc14df2d36c12` was `terminated` with transition reason `Service initiated (2026-08-25 04:15:29 GMT)`.
- Spot request `sir-p8hfj1hm` was closed with code `instance-terminated-no-capacity`.
- AWS message: `Your Spot instance was terminated because there is no Spot capacity available that matches your request.`
- The other workload and NAT instances were still running at diagnosis time.
- Terraform still listed the complete managed stack before destroy; refresh during destroy recognized the already-terminated k6 resource and planned the 36 remaining resources for deletion.

This was not a recurrence of the replica-authentication defect. Per the mandatory failure rule, no infrastructure retry or topology change was attempted.

### Experiment and reporting boundary

The failure occurred before seeding and before the baseline run. Therefore:

- Step 6 seed and count assertions were not run;
- A/B/C/D run keys do not exist;
- SQL provenance, error, fallback, lag, stock, reservation, and convergence checks were not run;
- no performance numbers are valid or published;
- `docs/load-test/product-read-replica-results-2026-08-24.md` was not created;
- no results-report commit exists.

The replica smoke run key is deployment correctness evidence only, not a benchmark run key.

### Mandatory cleanup proof

Immediately after the bounded k6 diagnostics:

| Command/check | Exit/result |
| --- | --- |
| `terraform -chdir=infra/load-test destroy -auto-approve -var='load_test_profile=product-replica'` | Exit 0; `Destroy complete! Resources: 36 destroyed.` The 37th applied resource, k6, had already been terminated externally and was absent after refresh. |
| `terraform -chdir=infra/load-test state list` | Exit 0 with no output. |
| `test -z "$(terraform -chdir=infra/load-test state list)"` | Exit 0. |
| `aws ec2 describe-instances --region ap-northeast-2 --instance-ids ...` | Exit 0; all ten instance IDs, including NAT and k6, reported `terminated`. |

Final checks:

- Local and remote feature ref: `e017c2f6dd70247b917d0f33294da339e36bb25b`.
- Remote main: unchanged at `45b971a96e09eb930f009b8f0a5fd6280a77d606`.
- Tracked worktree: clean.
- Terraform state: empty.
- Live experiment instances: all terminated.
- External retry status: blocked before seed/benchmarks by AWS Spot capacity; no valid performance report or report-only commit can be produced from this attempt.

## Third external attempt — On-Demand nodes

Date: 2026-08-25 (Asia/Seoul)

The controller authorized a third attempt of the same Task 7 topology with On-Demand workload nodes. Every Terraform plan, apply, destroy, and matching post-destroy plan used both `-var='load_test_profile=product-replica'` and `-var='use_spot=false'`. No topology or benchmark action was expanded.

### Safety preflight and immutable inputs

| Command/check | Exit/result |
| --- | --- |
| `git status --short --branch` | Exit 0; tracked worktree clean on `feature/product-mysql-read-replica`. |
| `git rev-parse HEAD` | Exit 0; exact reviewed ref `e017c2f6dd70247b917d0f33294da339e36bb25b`. |
| `terraform -chdir=infra/load-test state list` | Exit 0 with no output. |
| `git ls-remote origin refs/heads/feature/product-mysql-read-replica refs/heads/main` | Exit 0; feature was exact `e017c2f6dd70247b917d0f33294da339e36bb25b`, while main remained `45b971a96e09eb930f009b8f0a5fd6280a77d606`. |
| Registry image inspect | Exit 0; immutable tag `camelia9999/cancel-loadtest:product-e017c2f6dd70247b917d0f33294da339e36bb25b` remained `linux/arm64`, registry digest `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323`, config digest `sha256:c9074c27a1cd4f39cbbe48aeda6f067d7b348a1e89b2999dc0929a676fcc6f5a`. No rebuild occurred. |
| `terraform -chdir=infra/load-test plan -var='load_test_profile=product-replica' -var='use_spot=false' -out=/tmp/task7-product-replica-ondemand.tfplan` | Exit 0; exactly 37 add, 0 change, 0 destroy. |
| Saved-plan JSON inspection from `infra/load-test` | Exit 0; all ten `aws_instance` planned values had `instance_market_options=null`, and action counts were `{create:37, update:0, delete:0}`. |

### Apply and deployment evidence

`terraform -chdir=infra/load-test apply -auto-approve -var='load_test_profile=product-replica' -var='use_spot=false'` exited 0:

```text
Apply complete! Resources: 37 added, 0 changed, 0 destroyed.
```

Outputs:

- Product NLB: `cancel-loadtest-product-c1265f0f7a5b3a80.elb.ap-northeast-2.amazonaws.com`
- VPC: `vpc-05b4712e0501acaa4`
- NAT: `i-0db85f8d9e5afaf13`
- k6: `i-0ef640f94501a293d`
- MySQL source: `i-0607b24e587e698c6`
- MySQL replica: `i-0ecded2f6f21ca35e`
- observability: `i-0350b44eafb9f9abe`
- Product a/b/c/d: `i-03d05a9faa389036b`, `i-0cd612a70fdb25169`, `i-0aa4e01f32ff441b3`, `i-001a1db3ba16060cc`
- Redis: `i-0671947265f629d5c`

The exact-ref SSM deployment exited 0. Source and replica containers started, authenticated replica readiness passed, and the full replication smoke passed with run key `1787632179-29620`. Redis, Product a/b/c/d, k6 node exporter, and the observability stack deployment commands also completed.

### Confirmed deploy-order defect at the Step 6 boundary

The source tunnel opened successfully on local port 13306 with SSM session `root-hn37lltgt2qp4jn3gixibg3osq`. The first and only seed attempt was:

```text
env MYSQL_PORT=13306 SEED_COUNT=1000 ./k6/seed/product-detail-seed.sh
```

It exited 1 before inserting seed rows:

```text
ERROR 1146 (42S02) at line 69: Table 'product_db.category' doesn't exist
```

Bounded diagnostics proved this was a deterministic deployment-order defect rather than a transient seed or AWS failure:

- `SHOW TABLES` on the authenticated source connection exited 0 and returned only `loadtest_replication_smoke`.
- A 10-second NLB `/actuator/health` request timed out.
- SSM command `11ee6ff1-24b7-4b4a-bc5e-2076c840a98c` collected the last 120 Product a logs and container state `restarting/1`.
- Product a repeatedly reached Flyway validation, then failed with `Found non-empty schema(s) product_db but no schema history table`.
- `infra/load-test/deploy/mysql-product-replica-smoke.sh` creates `product_db.loadtest_replication_smoke` before Product deployment. Although its marker rows are deleted, the table remains. That makes the schema non-empty before Flyway can create its history table and migrations.

The tunnel was closed immediately after diagnostics. No second seed, A/B/C/D run, live workaround, schema mutation, or benchmark was attempted. This attempt therefore has no valid benchmark run keys or publishable performance numbers, and `docs/load-test/product-read-replica-results-2026-08-24.md` was not created.

### Mandatory cleanup proof

| Command/check | Exit/result |
| --- | --- |
| `terraform -chdir=infra/load-test destroy -auto-approve -var='load_test_profile=product-replica' -var='use_spot=false'` | Exit 0; plan was 0 add, 0 change, 37 destroy and final output was `Destroy complete! Resources: 37 destroyed.` |
| `terraform -chdir=infra/load-test state list` | Exit 0 with no output. |
| `terraform -chdir=infra/load-test plan -detailed-exitcode -var='load_test_profile=product-replica' -var='use_spot=false' -out=/tmp/task7-postdestroy-ondemand.tfplan` | Expected exit 2 for a non-empty proposed-create diff; exactly 37 add, 0 change, 0 destroy, proving no managed resource remained under the matching variable set. |
| `aws ec2 describe-instances` for the nine workload IDs plus NAT | Exit 0; all ten reported `terminated`. |

Final tracked worktree remained clean at `e017c2f6dd70247b917d0f33294da339e36bb25b`. Remote main was not changed. Task 7 is blocked before seeding and experiment execution by the verified replication-smoke/Flyway ordering defect; it requires a local TDD correction and review before another external attempt.

## Local fix round 2 — remove smoke-owned temporary DDL

Date: 2026-08-25 (Asia/Seoul)

This round was local only. It made no registry, Git remote, Terraform, AWS, SSM, or other external call.

### Root cause and TDD contract

The third attempt already isolated the owner and failure boundary: a successful pre-application replication smoke deleted its marker row but retained `product_db.loadtest_replication_smoke`; this was the only source table, and Product Flyway rejected the non-empty schema before creating its history table. The intended temporary-DDL pattern is therefore that the smoke owner removes its table, not merely its row.

The regression contract in `mysql-product-replica-static-test.sh` was changed first to require all of the following before a successful smoke exits:

- source issues `DROP TABLE IF EXISTS product_db.loadtest_replication_smoke`;
- the existing bounded wait checks `information_schema.tables` on the replica;
- replica table count reaches zero before the EXIT trap is cleared.

RED command:

```text
bash infra/load-test/deploy/mysql-product-replica-static-test.sh
```

It exited 1 against the untouched row-only implementation with the expected message:

```text
successful replication smoke must remove its temporary table from source and replica
```

### Minimal production correction and GREEN

Only `mysql-product-replica-smoke.sh` changed in production. Its failure trap now performs a bounded source `DROP TABLE IF EXISTS`. On the successful path, it performs the same source drop and waits through the existing bounded replica query helper until `information_schema.tables` reports the table absent. The thread checks, unique marker, reader SELECT, reader write-rejection proof, command timeouts, and failure trap remain intact. No Flyway baseline setting or deployment reordering was introduced.

The focused static test then exited 0.

### Fresh isolated source/replica behavior proof

An isolated Compose project named `product-replica-table-cleanup` started fresh MySQL 8 source and replica containers from the reviewed source, replication, and replica Compose files. Both named volumes were newly created. Authenticated `SELECT 1` succeeded on both containers, then local GTID auto-position replication was configured with source hostname `mysql-product`, the replica was set persistently read-only/super-read-only, and the corrected real smoke ran as:

```text
env SOURCE_HOST=mysql-product SMOKE_TIMEOUT_SECONDS=30 SMOKE_COMMAND_TIMEOUT_SECONDS=10 SMOKE_RUN_KEY=local-table-cleanup bash infra/load-test/deploy/mysql-product-replica-smoke.sh
```

It exited 0 and printed `replication smoke passed: local-table-cleanup`. Immediate authenticated metadata queries returned:

```text
source  0
replica 0
```

for `information_schema.tables` rows named `product_db.loadtest_replication_smoke`. `docker compose ... down -v --remove-orphans` then removed both containers, both named volumes, and the isolated network. Final project-label container and volume listings were empty.

### Fresh Task 4/5/6 and repository gates

| Command | Exit/result |
| --- | --- |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0 in 2.1s. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash k6/product-replica-probe-test.sh` | Exit 0 in 12.7s. |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0 in 10.3s; printed validation/failure messages were intentional negative fixtures. |
| `bash -n` for the replica smoke/static test, SSM deploy, replica probe, and AWS runner scripts | Exit 0. |
| Product scale-out rendered Compose with test image values | Exit 0. |
| Replica rendered Compose | Exit 0. |
| Product replica observability rendered Compose | Exit 0. |
| `terraform -chdir=infra/load-test fmt -check` | Exit 0. |
| `terraform -chdir=infra/load-test validate` | Exit 0: `Success! The configuration is valid.` |
| `git diff --check` | Exit 0. |

A final combined rerun reached Terraform validation after all preceding tests/checks had passed but got one provider-process startup error (`Failed to load plugin schemas`; provider produced no handshake output). The same isolated `terraform -chdir=infra/load-test validate` command was rerun immediately and exited 0 with `Success! The configuration is valid.`; the subsequent diff and report-whitespace checks also exited 0. The earlier independent validation in this round had likewise exited 0.

Directly affected tracked files are only:

- `infra/load-test/deploy/mysql-product-replica-smoke.sh`
- `infra/load-test/deploy/mysql-product-replica-static-test.sh`

Correction commit: `cacdde8 fix(load): remove replication smoke table` (only the two files above).

No Task 7 external retry is authorized or performed in this local correction round.

## Fourth external attempt — reviewed cleanup fix on On-Demand

Date: 2026-08-25 (Asia/Seoul)

### Exact ref, image, and preflight

The approved reviewed ref was `cacdde8d9a237f52814ac78f1778f8d64c505e1c`. The tracked worktree was clean and Terraform state was empty. The exact ref was pushed only to `refs/heads/feature/product-mysql-read-replica`; the remote feature ref then matched `cacdde8...`, while remote main remained unchanged at `45b971a96e09eb930f009b8f0a5fd6280a77d606`.

The immutable image `camelia9999/cancel-loadtest:product-cacdde8d9a237f52814ac78f1778f8d64c505e1c` was built and pushed for `linux/arm64`. Inspection returned registry digest `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323` and config/image digest `sha256:c9074c27a1cd4f39cbbe48aeda6f067d7b348a1e89b2999dc0929a676fcc6f5a`. Matching the prior app image was expected because `cacdde8` changed only deploy smoke/test files.

The saved Terraform plan used both `-var='load_test_profile=product-replica'` and `-var='use_spot=false'`. Human and JSON proofs showed exactly 37 creates, zero updates/deletes, ten `aws_instance` resources, and `instance_market_options=null` on all ten. A first sandboxed `terraform show -json` hit the known provider handshake restriction; the same read-only render outside the sandbox exited 0 and all assertions evaluated true.

### Apply, staged deploy, and schema gates

Apply with both required variables exited 0: `Apply complete! Resources: 37 added, 0 changed, 0 destroyed.` Outputs included NLB `cancel-loadtest-product-f15197db54e16ab6.elb.ap-northeast-2.amazonaws.com`, VPC `vpc-0400c0bce552b010e`, NAT `i-07096efc79edd5384`, and workload instances listed in the cleanup table below.

Deployment was deliberately staged so Product could not race the pre-Flyway gate:

1. Source, replica, and Redis deployed first from exact `cacdde8`.
2. Authenticated replica smoke passed with key `1787633343-29672`.
3. SSM command `3d5cb6ec-609a-48b8-bdf4-2f610b5bfe15` asserted `source_product_db_user_tables=0` before Product started. An earlier read-only diagnostic command `e5aa827c-5743-4630-8728-9ea96be519f6` failed only because its JSON escape encoded the SQL literal incorrectly; it made no database mutation.
4. Product a/b/c/d and observability then deployed.
5. AWS reported the internal NLB active and all four targets healthy. Local HTTP polling was stopped once the internal scheme was confirmed; it was not used as a health conclusion.
6. Authenticated source gate `158f981a-6e49-4ee6-9ba8-d21add3f106f` printed both `category` and `flyway_schema_history` before seeding.

The source SSM tunnel session was `root-7ed3aotu2jxvf5iejqiukbc8py`. The first sandboxed seed process could not reach the local forwarding socket and exited with `ERROR 2003` before writing. The same approved seed outside the sandbox succeeded once. `productIds.json` had length 1,000 and the exact source counts were `1000` Product rows and `9000` SKU rows. The tunnel was then closed cleanly.

### Baseline A correctness failure and ruling

Product a/b/c/d were redeployed with `PRODUCT_DATASOURCE_REPLICA_ENABLED=false`; all NLB targets were healthy. Baseline run key `20260825T045819Z-product-stock-mix-8519` ran all four 180-second stages from `2026-08-25T04:58:27Z` through `05:10:42Z` with 8,255,567 completed and zero interrupted iterations. SSM command was `1d3eab6b-a0f9-4bae-bd41-7ac4bc2332b4`.

The runner exited 1 because the zero-server-error threshold failed:

- `stock_server_error_rate`: 202 / 366,871 writes, rate `0.0005506022552886437`;
- overall request failures: 1,248 / 8,438,943, rate `0.0001478858193496508`;
- unexpected client-error rate: zero;
- all four Product containers remained running with exit 0 and zero restarts.

Bounded logs from all four Product nodes showed the same causal boundary: `OrphanReservationRecoveryScheduler` attempted payment existence GETs at `http://localhost:8080/v1/payments/.../exists`, received `Connection refused`, and surfaced `ResourceAccessException`. The Product/Product-scaleout/Product-replica topology has no payment service.

Final authenticated source diagnostics proved a correctness failure, not merely a performance threshold:

```text
product_stock: rows=9000 sum=899913 min=97 max=100
stock_reservation: RELEASED=183289 RESERVED=87
```

The expected stock sum was 900,000 and expected remaining RESERVED count was zero. The deficit of 87 exactly matched the stranded RESERVED rows. Therefore the run provenance/correctness gate was invalid. B steady, C lag, D outage, SQL routing interpretation, performance comparison, source-backed results document, and results commit were not attempted. No performance number from this run is publishable.

Local artifact evidence was retained under the ignored `k6/results` path. The bundle SHA-256 was `43a2e03d899963473bcf5259e122af32011dfce914e26e5ee4ae78462e5db599` (44,473 bytes); summary SHA-256 was `7f60478b2cd081a5ee9ded38892e4f8bbc2dd17362d11c2188b6d7cda84c5dd8` (3,106 bytes).

Controller ruling: stop external execution, destroy first, and correct locally so full keeps orphan recovery enabled while `product`, `product-scaleout`, and `product-replica` disable only orphan recovery. Do not disable the cancel-restore scheduler.

### Mandatory matching-variable cleanup

Destroy used both `-var='load_test_profile=product-replica'` and `-var='use_spot=false'` and exited 0: `Destroy complete! Resources: 37 destroyed.` `terraform state list` then exited 0 with no output. A matching-variable post-destroy detailed plan returned expected exit 2 with exactly 37 add, 0 change, 0 destroy, proving no managed resources remained.

All exact instance IDs reported `terminated`:

| Role | Instance |
| --- | --- |
| NAT | `i-07096efc79edd5384` |
| k6 | `i-0f839fbc75067d6aa` |
| MySQL source | `i-07dd3a8cd4dc7ebdb` |
| MySQL replica | `i-0dc98198f004d3ee4` |
| observability | `i-0efcf6cd437c83eb1` |
| Product a/b/c/d | `i-0838679c8adbe02af`, `i-0c7dc5bbc1463f8fb`, `i-0fbd4ab65b61f37a0`, `i-005f6408625abf544` |
| Redis | `i-006eb551daccc755d` |

AWS work stopped after this proof. The tracked worktree remained clean at `cacdde8`; only ignored evidence files and this SDD ledger were written.

## Local fix round 3 — disable payment-dependent orphan recovery in Product-only profiles

Date: 2026-08-25 (Asia/Seoul)

This round was local only. It made no Git remote, image registry, Terraform, AWS, SSM, or other external call.

### Failure evidence, cleanup state, and ruling

Baseline A `20260825T045819Z-product-stock-mix-8519` failed its correctness gate with 202 stock server errors, 87 remaining `RESERVED` rows, and a stock deficit of 87. All four Product-node logs showed `OrphanReservationRecoveryScheduler` calling the absent payment service at `http://localhost:8080/v1/payments/.../exists` and receiving connection refused. The three Product-only profiles (`product`, `product-scaleout`, and `product-replica`) intentionally contain no payment service, while the full profile does.

Before this local correction began, the approved matching-variable cleanup had already completed: Terraform destroyed 37 resources; `terraform state list` was empty; the matching post-destroy plan was exactly 37 add, 0 change, 0 destroy; and all ten exact EC2 instance IDs were `terminated`. AWS work then stopped.

Controller ruling: retain orphan recovery as enabled by default for the full topology, disable only orphan recovery in the three Product-only profiles, and do not disable `CancelRestoreRedriveScheduler`.

### Traced configuration boundary

The runtime call chain is `OrphanReservationRecoveryScheduler.run()` → `OrphanReservationRecoveryService.recoverAll()` → `PaymentQueryPort.exists()` → the payment-service HTTP endpoint. `CancelRestoreRedriveScheduler` is a separate component and does not depend on that endpoint.

Deployment routing was traced end to end:

- full uses `product.compose.yml` without a Product profile override;
- product uses `product.compose.yml` plus `product-readonly.compose.yml`;
- product-scaleout and product-replica Product nodes use `product.compose.yml` plus `product-scaleout.compose.yml`;
- `ssm-deploy.sh` already owns and tests those exact override selections;
- no new SSM export machinery is necessary because the selected Compose override is the profile boundary and directly supplies the Spring environment variable.

### TDD RED evidence

The smallest application contract was added first in `OrphanReservationRecoverySchedulerConditionTest`: the scheduler bean must exist by default, must be absent when `product.orphan-recovery.enabled=false`, and `CancelRestoreRedriveScheduler` must remain present in both cases.

```text
./gradlew :product-service:test --tests com.example.product.infrastructure.scheduler.OrphanReservationRecoverySchedulerConditionTest
```

After correcting only the test fixture's required `@Value` properties, it exited 1 against the untouched scheduler with exactly one intended failure: `disablingOrphanRecoveryDoesNotDisableCancelRestore`; 2 tests completed, 1 failed. The default/full assertion passed.

The deploy contract was added first to `product-only-static-test.sh`. It requires the default-enabled application property, fixed `false` in both Product-only Compose overrides, the existing SSM override selections, and rendered Compose proof that full has no disabling environment variable while product and scaleout/replica resolve it to `false`.

```text
bash infra/load-test/product-only-static-test.sh
```

It exited 1 against the untouched configuration with `missing PRODUCT_ORPHAN_RECOVERY_ENABLED:true`.

### Minimal correction

- `OrphanReservationRecoveryScheduler` is conditional on `product.orphan-recovery.enabled=true` with `matchIfMissing=true`.
- `application.yml` maps `PRODUCT_ORPHAN_RECOVERY_ENABLED` with default `true`, preserving the full topology and normal application default.
- `product-readonly.compose.yml` sets the value to `false` for product.
- `product-scaleout.compose.yml` sets the value to `false` for both product-scaleout and product-replica Product nodes.
- `CancelRestoreRedriveScheduler` was not changed and the unit contract proves it remains registered when orphan recovery is disabled.

### GREEN and complete local gate

| Command | Exit/result |
| --- | --- |
| `./gradlew :product-service:test --tests com.example.product.infrastructure.scheduler.OrphanReservationRecoverySchedulerConditionTest` | Exit 0; 2 focused tests passed. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0; rendered full/product/scaleout contracts passed. |
| `./gradlew :product-service:test --rerun-tasks` | Exit 0; fresh full Product suite `BUILD SUCCESSFUL in 3m 43s`; XML aggregate: 135 tests, 0 failures, 0 errors, 0 skipped. Testcontainers created MySQL containers throughout the run; the prior baseline stall did not recur. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0. |
| `bash k6/product-replica-probe-test.sh` | Exit 0 in 17.0s. |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0 in 14.3s; printed error messages were intentional negative fixtures. |
| `terraform -chdir=infra/load-test fmt -check` | Exit 0. |
| `terraform -chdir=infra/load-test validate` | Exit 0: `Success! The configuration is valid.` |
| `bash -n infra/load-test/deploy/ssm-deploy.sh infra/load-test/product-only-static-test.sh` | Exit 0. |
| `git diff --check` | Exit 0. |

No external retry is part of this correction round. The correction must be reviewed before any later push, image publication, Terraform apply, SSM deployment, seeding, or experiment.

Correction commit: `12d524e fix(product): disable orphan recovery without payment` (the six directly affected application, Compose, and regression-test files only).

## Local fix round 4 — Product scaleout datasource pool capacity

Date: 2026-08-25 (Asia/Seoul)

This round was local only. It made no Git remote, image registry, Terraform apply/destroy, AWS, SSM, seed, or benchmark call.

### Ruling and traced root cause

Attempt 5 used exact ref `12d524e` and its immutable Product image (digest prefix `d92f3f`). Apply, staged deploy, replication smoke, schema gates, and source seed completed. Baseline A run key `20260825T061435Z-product-stock-mix-18034` then failed the correctness gate with 86 stock server errors out of 427,365 stock writes and 685 overall failures out of 10,074,086 requests. Product logs on all four nodes showed the same saturation: the primary Hikari pool was `total=10`, `active=10`, `idle=0`, with up to 134 threads awaiting a connection until the 30-second timeout. Final source checks found stock sum `899971` and 29 `RESERVED` rows; B/C/D were skipped. Matching-variable destroy removed 37 resources, Terraform state was empty, and all ten instance IDs were terminated before local work began.

The runtime binding is direct: `ProductDataSourceConfig` binds `spring.datasource.hikari` to the primary Hikari pool and `product.datasource.replica.hikari` to the optional replica pool. The Product scaleout Compose override is selected for all four Product nodes in both `product-scaleout` and `product-replica`; full and single-node `product` do not select it. Therefore the smallest root-boundary correction is two fixed override values in that existing file, not an application default or runner change.

`50` is equal for primary and replica so A/B use identical per-database capacity. Four Product nodes can open at most 200 connections to the source and 200 to the replica, each below MySQL's configured 500 maximum; it also remains below the four nodes' aggregate default Tomcat request capacity. Full and single-node Product retain Hikari defaults because neither receives this Compose override.

### TDD and local evidence

The rendered-Compose assertion in `infra/load-test/product-only-static-test.sh` was added before configuration changes. It required full and single-node Product to omit both pool variables and scaleout to render both values as `50`; it exited 1 before the Compose edit. Adding only `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "50"` and `PRODUCT_DATASOURCE_REPLICA_HIKARI_MAXIMUM_POOL_SIZE: "50"` to `product-scaleout.compose.yml` made the focused static test and an explicit replica-enabled rendered Compose assertion exit 0.

| Command | Result |
| --- | --- |
| `./gradlew :product-service:test --rerun-tasks` | Exit 0; fresh, non-overlapping run: 135 tests, 0 failures, 0 errors, 0 skipped. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0; proves full and single-node omit both values and scaleout resolves both to `50`. |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash k6/product-replica-probe-test.sh` | Exit 0. |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0; expected negative-fixture diagnostics printed. |
| `terraform -chdir=infra/load-test fmt -check` and `validate` | Both exit 0. |
| `git diff --check` | Exit 0 before commit. |

No runner correctness gates, production defaults, or external state changed. The next external attempt remains separately gated by review and authorization.

Correction commit: `39b62dc fix(load): size product scaleout pools` (only `product-scaleout.compose.yml` and `product-only-static-test.sh`).

## External attempt 6 and local fix round 5 — workload metric identity labels

Date: 2026-08-25 (Asia/Seoul)

### Attempt 6 evidence and mandatory cleanup

The reviewed pool-50 topology reached a correctness-clean baseline A. Run `20260825T065516Z-product-stock-mix-25142` completed 8,963,373 requests with zero failed requests. `stock_server_error_rate` was 0/646,832 writes, final source stock sum was exactly 900,000, and no reservations remained `RESERVED`.

The remote k6 workload itself succeeded. The runner failed only during post-run Prometheus artifact validation. RPS aggregation returned two series labeled only by `workload`. Each of the four stages' p95, p99, and error-rate responses returned exactly two nonempty series with the real custom-metric shape:

```text
{__name__, run, scenario, workload}
scenario == workload
run == 20260825T065516Z-product-stock-mix-25142
```

The then-current `WORKLOAD_RESULT_JQ` rejected every such custom series because it allowed only `__name__` and `workload`. This was a runner provenance-validator defect after a valid workload, not a k6, Product, database, or correctness failure. B steady, C lag, and D outage were skipped, and no performance comparison was published.

Matching-variable destroy removed 37 resources. Terraform state was empty afterward, and all ten exact EC2 instance IDs reported `terminated`. AWS work stopped before this local-only correction.

### Systematic reproduction and TDD

The actual stage-1 p95 artifact was passed directly to the local validator with its exact run key; it reproduced exit 1. The smallest regression fixture then mirrored the complete real metric object and likewise exited 1 before production change. Existing RPS `{workload}` input remained valid, isolating the failure to raw custom-metric identity labels.

A second RED cycle proved that merely using jq's null-coalescing operator would incorrectly accept `run:null` and `scenario:null`: the malformed fixture unexpectedly passed before the stricter presence/value check, and the runner test exited 1.

The final validator keeps the original exact cardinality and workload-set contract:

- exactly two mixed-workload series;
- exactly one `read` and one `write` workload;
- nonempty values and successful Prometheus response;
- allowed metric keys only: `__name__`, `run`, `scenario`, and `workload`;
- if `scenario` is present, it must equal `workload`;
- if `run` is present, it must equal the runner's exact `RUN_KEY`.

Unexpected operation labels, added/split subseries, wrong runs, scenario/workload mismatches, present-null labels, missing workloads, empty values, and failed responses remain rejected. No stock, HTTP error, replica, outage, or final-convergence correctness gate changed.

### GREEN evidence

All 16 retained attempt-6 stage files (`rps`, `p95`, `p99`, and `error_rate` across four stages) passed the corrected validator with the exact run key.

| Command | Exit/result |
| --- | --- |
| `bash k6/product-stock-mix-runner-test.sh` | Exit 0; positive actual-label fixtures passed and all malformed/cardinality fixtures were rejected. |
| `./gradlew :product-service:test --rerun-tasks` | Exit 0; `BUILD SUCCESSFUL in 3m 6s`; XML aggregate 135 tests, 0 failures, 0 errors, 0 skipped. |
| `bash infra/load-test/product-only-static-test.sh` | Exit 0. |
| `bash infra/load-test/product-replica-static-test.sh` | Exit 0. |
| `bash infra/load-test/deploy/mysql-product-replica-static-test.sh` | Exit 0. |
| `bash k6/product-replica-probe-test.sh` | Exit 0. |
| `terraform -chdir=infra/load-test fmt -check` | Exit 0. |
| `terraform -chdir=infra/load-test validate` | Exit 0: `Success! The configuration is valid.` |
| shell syntax and `git diff --check` | Exit 0. |

Correction commit: `dafe41494600f5bdea01413c95c30b066f8c8901 fix(load): accept workload metric identity labels` (only the runner and its regression test).

This is fix round 5/5 and requires scoped review. No external retry was performed in this local correction round.

## External attempt 7 — terminal invalid/inconclusive run

Date: 2026-08-25 (Asia/Seoul)

### Immutable inputs and pre-workload gates

- Exact ref: `dafe41494600f5bdea01413c95c30b066f8c8901`.
- Immutable linux/arm64 Product image digest: `sha256:d92f3fd6cc3332d1456f9b7a8b484ad09284b7ddbb4ee0f3740c851f598ea454`.
- Matching `load_test_profile=product-replica` and `use_spot=false` plan/apply: 37 resources; all ten instance market options null.
- Replication smoke marker: `1787642942-29621`.
- Four Product NLB targets healthy. All four Product containers had replica routing false, primary and replica Hikari maximum pools 50/50, and orphan recovery false.
- Source/seed gate: 1,000 Product rows, 9,000 SKU rows, 9,000 stock rows, stock sum 900,000, and zero reservation rows.

### Baseline A failed strict correctness

Run `20260825T073501Z-product-stock-mix-34476` completed 9,739,954 requests and 9,413,388 iterations over the four 180-second stages. Observed overall latency was p95 186.08 ms and p99 310.70 ms. These latency values are retained only as run evidence and are not interpreted as performance because correctness failed.

The invalidating evidence was:

- 166 overall HTTP failures;
- 15 stock HTTP 5xx responses out of 653,143 stock writes;
- zero unexpected stock client errors;
- Product-c primary Hikari timeout at `total=50 active=50 idle=0 waiting=103`;
- final source stock: 9,000 rows, sum 899,996, min 99, max 100;
- final reservations: `RELEASED=326562`, `RESERVED=4`.

The four stranded reservations exactly matched the four-unit stock deficit. A therefore failed the strict zero-stock-server-error and final convergence gates. Per the approved gate, B steady, C lag, and D outage were not run. No A/B performance comparison, replica lag/outage result, SQL digest routing proof, or adopt/defer conclusion is publishable.

### Mandatory cleanup proof

Matching-variable Terraform destroy reported 37 resources destroyed. `terraform state list` was empty, and the matching post-destroy plan was exactly 37 add, 0 change, 0 destroy.

All ten exact instance IDs reported `terminated`:

| Role | Instance ID |
| --- | --- |
| NAT | `i-046fd97678778f252` |
| k6 | `i-04b0bebca64c93469` |
| MySQL source | `i-00cc04852ad1887a6` |
| MySQL replica | `i-0fc6fb7326b428aa9` |
| observability | `i-041b5fb2338a4de68` |
| Product a/b/c/d | `i-0c7499524b3839a55`, `i-0e0f1ae8a365494fd`, `i-00e041cfcd90490ec`, `i-0211b1cbf468d045e` |
| Redis | `i-0b0a3ab89d2a116d4` |

### Terminal ruling

The Task 7 correction budget is exhausted at 5/5. The required result document records attempt 7 as invalid and inconclusive without interpreting its performance values. No further correction or external retry is authorized by the current plan. Any new investigation, implementation change, image publication, AWS topology, or experiment requires new authority and a revised plan.
