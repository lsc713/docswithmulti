# SDD ledger — plan: docs/superpowers/plans/2026-08-24-product-mysql-read-replica.md

Worktree: `/Users/juho/Documents/docswithmulti/.worktrees/product-mysql-read-replica`
Branch: `feature/product-mysql-read-replica`
Spec: `docs/superpowers/specs/2026-08-24-product-mysql-read-replica-design.md`

## Setup

- Baseline: full `./gradlew :product-service:test` was interrupted after Testcontainers MySQL stayed in initialization for more than five minutes without producing a test result. Focused non-container baseline passed: `ProductQueryTransactionBoundaryTest`, `ProductStockSnapshotCacheServiceTest`, and `StockServiceTest` (`BUILD SUCCESSFUL`, 2026-08-24).
- Existing main-worktree user changes are outside this worktree and untouched.

## Pre-flight scan

| Task(s) | Producer / consumer or internal consistency | Finding / ruling |
|---|---|---|
| 1 | Test names match `ReplicaRoute`, context and routing DataSource implementation | Consistent. |
| 1 → 2 | Task 1 produces route/context/pools; Task 2 consumes them in the aspect | Consistent. |
| 2 | Approved boundaries are detail loader and stock cache loader; exclusions are explicit | Consistent. |
| 2 → 5 | Task 2 consumes replica properties; Task 5 supplies them in deploy | Consistent. |
| 3 | Profile, IP, NLB and NAT conditions are covered by one static test | Consistent. |
| 3 → 4 | Task 3 produces the replica role/IP; Task 4 deploys it | Consistent. |
| 3 → 5 | Task 3 produces profile/IP; Task 5 wires app and observation | Consistent after observation ruling below. |
| 3 → 7 | Task 7 applies only the profile produced by Task 3 | Consistent. |
| 4 | Base source Compose was to gain GTID globally, conflicting with unchanged existing profiles | Ruling: put source GTID/binlog/init mounts in a `mysql-product-replication.compose.yml` override selected only for `product-replica`. Cost if wrong: existing performance baselines would silently change. |
| 4 → 5 | Both modify deploy ordering/overrides | Sequential edits; Task 5 preserves Task 4's profile branch and adds app/obs env only. |
| 4 → 6 | Task 4 produces GTID replica and control commands; Task 6 pauses/restarts them | Consistent. |
| 4 → 7 | Task 7 consumes smoke and health contract | Consistent. |
| 5 | Adding replica exporter/target to base product observation would make existing profiles show dead targets | Ruling: create replica-only Compose/Prometheus overrides selected only for `product-replica`; preserve `product-only` files. Cost if wrong: existing profiles gain noisy/down targets. |
| 5 → 6 | Both modify runner; Task 5 provides route/host queries, Task 6 adds modes/artifacts | Sequential and compatible. |
| 5 → 7 | Task 7 consumes route metrics and replica resource series | Consistent. |
| 6 | Probe test seams, four modes, four artifacts, recovery trap and stale SKU are internally aligned | Consistent. |
| 6 → 7 | Task 7 consumes exact modes and artifacts produced by Task 6 | Consistent. |
| 7 | Verification precedes publish/apply; correctness gates precede interpretation; destroy is last | Consistent. Push, image publish, Terraform apply and AWS billing require an execution-time user approval before Step 3/4. |

## Rulings

- Ruling: source GTID/binlog configuration is a replica-profile-only Compose override — required by the spec's promise that existing profiles remain unchanged — wrong choice would invalidate historical A/B comparisons.
- Ruling: replica exporter and scrape targets use replica-profile-only observation files — required to avoid dead targets and extra containers in existing profiles — cost is one small Prometheus config copy.
- Ruling: proceed from the green focused baseline and rerun the complete Product suite at Task 2/7 — the environment's Testcontainers MySQL startup, not a reported assertion failure, blocked the pre-flight full suite — cost if wrong is discovering a pre-existing integration failure later, before AWS execution.
- Ruling: do not enable replica-side binary logging/ROW format in Task 4. The approved design requires ROW binary logging only on the source, and explicitly excludes promotion and chained replicas; adding replica binlogs would add I/O without serving this experiment. Revisit only if promotion or downstream replication enters scope.

## Task ledger

- Task 1 base: `193ac80`
- Task 1 implementer: `/root/task1_impl`
- Task 1: fix round 1/5 (2 addressed, 0 open — final production class; success/nested restoration coverage; commits `34ccb2c..b3994e7`)
- Task 1: complete (commits `193ac80..b3994e7`, review clean)
- Task 2 base: `b3994e7`
- Task 2 implementer: `/root/task2_impl`
- Task 2: fix round 1/5 (3 addressed, 0 open — real lazy routing/fallback/cache-hit composition coverage; commit `2cca953..a68b042`)
- Task 2: complete (commits `b3994e7..a68b042`, review clean; full Product Testcontainers suite remains environment-unverified and is gated again in Task 7)
- Task 3 base: `a68b042`
- Task 3 implementer: `/root/task3_impl`
- Task 3: fix round 1/5 (2 addressed, 0 open — NAT/profile/NLB/gp3 static gates; commit `df3d520..73ba37d`)
- Task 3: complete (commits `a68b042..73ba37d`, review clean)
- Task 4 base: `73ba37d`
- Task 4 implementer: `/root/task4_impl`
- Task 4: fix round 1/5 (4 addressed, 0 open — writable first boot/read-only transition, fresh-source provenance, process deadlines, stronger static/runtime smoke; commit `41ee0a9..b921195`; replica-side binlog finding rejected by binding ruling)
- Task 4: fix round 2/5 (2 addressed, 0 open — partial-role source provenance preflight and persistent read-only settings with restart smoke; commit `b921195..d2c8003`)
- Task 4: complete (commits `73ba37d..d2c8003`, final re-review clean; live fresh-volume and restart persistence smoke passed)
- Task 5 base: `d2c8003`
- Task 5 implementer: `/root/task5_impl`
- Task 5: minor (deferred): runner test checks `mysql-product-replica` presence broadly rather than tying it specifically to the CPU/memory host regex; production regex is correct.
- Task 5: complete (commits `d2c8003..6fcc83c`, review clean with 1 deferred minor)
- Task 6 base: `6fcc83c`
- Task 6 implementer: `/root/task6_impl`
- Task 6: fix round 1/5 (8 addressed, 2 open — reusable-stack heartbeat schema upgrade and second-boundary status correlation; commits `939e679..9db8aa2`)
- Task 6: fix round 2/5 (2 addressed, 0 open — disposable heartbeat schema transition and microsecond pause boundaries; commits `9db8aa2..b395301`)
- Task 6: complete (commits `6fcc83c..b395301`, review clean after 2 fix rounds)
- Task 7 base: `b395301`
- Task 7 local verifier: `/root/task7_local`
- Task 7 Step 1 correction: `15ca07e` (duplicate query-count proxy regression; pending review)
- Task 7 incident: an accidental bare `git push` from report-shell backtick expansion failed before remote contact because the feature branch has no upstream; `origin/main` remains `45b971a` with last reflog update 2026-08-22.
- Task 7 Steps 1–2: complete (correction `15ca07e`, focused review clean; independent fresh `:product-service:cleanTest :product-service:test` and all local gates exit 0)
- Task 7 Ruling: publish the exact feature branch ref instead of running the plan's `git push origin main` — the implementation is isolated on `feature/product-mysql-read-replica`, and pushing local `main` would omit it or require an unapproved merge; cost if wrong is deployment tooling expecting the commit specifically on `origin/main`, though SSM consumes an exact reachable SHA and a pushed feature ref makes it reachable.
- Task 7 external boundary: awaiting explicit approval for feature-branch push, Docker image publish, Terraform apply/AWS billing, SSM deployment, seeding/load experiments, and final Terraform destroy.
- Task 7 external approval: user approved the full external scope on 2026-08-25; proceed with exact feature ref, immutable image, disposable AWS experiment, and mandatory destroy.
- Task 7 external attempt 1: apply 37 resources succeeded; replica deployment failed before smoke/seed/runs with root `ERROR 1045`; diagnostics retained; destroy 37 succeeded; state empty and all 10 EC2 instances terminated.
- Task 7 fix round 1/5 (1 addressed, 0 open — authenticated replica healthcheck closes temporary-server false readiness; commit `15ca07e..e017c2f`, scoped review clean)
- Task 7 Ruling: retry the same disposable external experiment under the user's existing full-scope approval — the first stack failed before smoke or workload and the retry does not expand topology/actions; cost if wrong is a second short-lived round of AWS billing beyond the first attempted stack.
- Task 7 external attempt 2: apply 37 succeeded; replica auth fix and replication smoke `1787631277-29613` passed; AWS terminated k6 Spot for `instance-terminated-no-capacity` before seed; destroy remaining 36 succeeded; state empty and all 10 EC2 instances terminated.
- Task 7 external boundary: a third attempt with existing `use_spot=false` would avoid the observed capacity failure but materially increases AWS billing; awaiting explicit approval for that cost change.
- Task 7 on-demand approval: user explicitly approved a third attempt with `use_spot=false` on 2026-08-25; apply and destroy must use the same `product-replica`/on-demand variables.
- Task 7 external attempt 3: on-demand apply 37 and deploy/replication smoke `1787632179-29620` succeeded; seed failed because pre-app smoke left a table that blocked Flyway; destroy 37 succeeded with matching vars; state empty and all 10 instances terminated.
- Task 7 fix round 2/5 (1 addressed, 0 open — replication smoke drops its temporary table and waits replica DDL convergence; commit `e017c2f..cacdde8`, scoped review clean)
- Task 7 Ruling: retry the same on-demand experiment after the reviewed deploy-order fix under the explicit on-demand approval — topology/capacity/cost class are unchanged; cost if wrong is another short-lived on-demand stack before workload evidence.
- Task 7 external attempt 4: exact reviewed ref `cacdde8` was pushed only to the feature ref; remote main remained `45b971a`. The immutable arm64 image tag resolved to registry digest `sha256:597bd93308e0f583047ac1ede4859caf4268818576bf30a92188f8d4aa39e323`. Matching `product-replica` plus `use_spot=false` plan/apply created exactly 37 resources with no instance market options. Replication smoke `1787633343-29672` passed, the authenticated pre-Product source gate proved zero user tables, all four internal Product NLB targets became healthy, Flyway created `flyway_schema_history` and `category`, and the one source seed produced exactly 1,000 products and 9,000 SKUs.
- Task 7 attempt 4 baseline evidence: run `20260825T045819Z-product-stock-mix-8519` completed all four 180-second stages with 8,255,567 completed and zero interrupted iterations, but failed correctness: stock server errors were 202/366,871; total failed requests were 1,248/8,438,943; final stock was 9,000 rows with sum 899,913 instead of 900,000; and 87 reservations remained `RESERVED`. All four Product nodes stayed running with zero restarts.
- Task 7 attempt 4 invalidation/root cause: bounded logs on all four Product nodes showed `OrphanReservationRecoveryScheduler` calling `http://localhost:8080/v1/payments/.../exists` and receiving connection refused. The `product`, `product-scaleout`, and `product-replica` topologies contain no payment service. The 87 stranded reservations exactly matched the stock deficit, so baseline provenance/correctness was invalid; B/C/D and performance interpretation/reporting were not run.
- Task 7 attempt 4 mandatory cleanup: matching-variable destroy completed with `Destroy complete! Resources: 37 destroyed.`; `terraform state list` was empty; the matching post-destroy plan was exactly 37 add, 0 change, 0 destroy; and all ten exact EC2 instances (NAT, k6, source, replica, observability, Product a/b/c/d, Redis) reported `terminated`. AWS work stopped after this proof.
- Task 7 Ruling: full keeps orphan recovery enabled by default; only `product`, `product-scaleout`, and `product-replica` disable orphan recovery because payment is absent. `CancelRestoreRedriveScheduler` must remain enabled. Reuse the existing profile-owned Compose overrides; do not add duplicate SSM readiness/export machinery. No external retry occurs until this local correction is reviewed.
- Task 7 fix round 3 RED: focused context contract first proved default/full registers both schedulers, then failed exactly `disablingOrphanRecoveryDoesNotDisableCancelRestore` because orphan recovery remained registered with `product.orphan-recovery.enabled=false` (2 tests, 1 failed). The deploy contract also failed first with missing `PRODUCT_ORPHAN_RECOVERY_ENABLED:true` before any production edit.
- Task 7 fix round 3 GREEN: `OrphanReservationRecoveryScheduler` gained a default-on conditional; `application.yml` maps `PRODUCT_ORPHAN_RECOVERY_ENABLED` default true; `product-readonly.compose.yml` sets false for product; `product-scaleout.compose.yml` sets false for product-scaleout/product-replica; CancelRestore code is unchanged and the context test proves it remains registered. Fresh full Product suite passed 135 tests with 0 failures/errors/skips in 3m43s. Product-only/product-replica/replica-deploy static tests, replica probe, stock-mix runner, Terraform fmt/validate, shell syntax, Compose rendering, and diff checks all exited 0.
- Task 7 fix round 3/5 (1 addressed, 0 open locally — payment-dependent orphan recovery disabled only in Product-only profiles; commit `cacdde8..12d524e`, pending scoped review).
- Task 7 fix round 3 review: scoped review clean at exact commit `12d524ee2aba11bdc5f1e2dd927ee39db73f4397`.
- Task 7 Ruling: execute external attempt 5 in the already approved identical On-Demand `product-replica` scope using exact fix3 ref; every Terraform plan/apply/destroy/post-destroy check uses both `load_test_profile=product-replica` and `use_spot=false`; mandatory cleanup ownership begins after apply.
- Task 7 external attempt 5: exact ref `12d524e` and immutable linux/arm64 image digest `sha256:d92f3fd6cc3332d1456f9b7a8b484ad09284b7ddbb4ee0f3740c851f598ea454` were published. Matching On-Demand plan/apply created exactly 37 resources and ten EC2 instances with null market options. Replication smoke `1787637988-29617`, Flyway/schema gates, four healthy NLB targets, and the one source seed (1,000 Product, 9,000 SKU, stock sum 900,000, zero reservations) passed.
- Task 7 attempt 5 baseline evidence: run `20260825T061435Z-product-stock-mix-18034` completed 9,860,432 iterations but failed the zero-error correctness gate with 86/427,365 stock server errors and 685/10,074,086 total failed requests. All four nodes showed primary Hikari `total=10 active=10 idle=0`, up to 134 waiters, and 30-second connection acquisition timeouts. Final source stock sum was 899,971 with 29 `RESERVED` rows, so A was invalid and B/C/D were skipped.
- Task 7 attempt 5 mandatory cleanup: source tunnel closed; matching-variable destroy completed with 37 destroyed; Terraform state was empty; all ten exact EC2 instances reported `terminated`. AWS work stopped before local correction.
- Task 7 Ruling: keep production/full and single-node Product Hikari defaults unchanged; in the existing `product-scaleout.compose.yml` override only, set equal primary/replica pools to 50. This gives A/B identical per-database capacity and caps each MySQL at 4 x 50 = 200 connections, below its configured 500 maximum. Do not weaken runner correctness gates.
- Task 7 fix round 4 RED/GREEN: rendered Compose contract failed before the pool override, then passed after adding only `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50` and `PRODUCT_DATASOURCE_REPLICA_HIKARI_MAXIMUM_POOL_SIZE=50`. Fresh Product suite passed 135 tests with zero failures; all Task 7 static/probe/runner/Terraform/diff gates passed. Commit `12d524e..39b62dc`; scoped review clean.
- Task 7 external attempt 6: pool-50 baseline A run `20260825T065516Z-product-stock-mix-25142` completed 8,963,373 requests with zero failed requests and zero stock errors out of 646,832 writes. Final source stock sum was exactly 900,000 with no `RESERVED` rows. The k6 workload itself and correctness gates succeeded.
- Task 7 attempt 6 runner invalidation: the post-run SSM/runner step failed only at `WORKLOAD_RESULT_JQ`. RPS aggregation returned the expected two `{workload}` series, while every stage p95/p99/error artifact returned exactly two nonempty read/write series with actual custom-metric labels `{__name__,run,scenario,workload}` and `scenario == workload`. The validator incorrectly allowed only `__name__` and `workload`, so valid artifacts were rejected. B/C/D were skipped and no performance comparison was published.
- Task 7 attempt 6 mandatory cleanup: matching-variable destroy completed with 37 destroyed; Terraform state was empty; and all ten exact EC2 instances reported `terminated`. AWS work stopped before local correction.
- Task 7 Ruling: preserve exact mixed-workload length 2 and the exact read/write set; accept only optional `run`/`scenario` identity labels in addition to `__name__`/`workload`; when present require `run == RUN_KEY` and `scenario == workload`; continue rejecting operation labels, split subseries, empty samples, missing workloads, and failed Prometheus responses. Do not weaken any stock, HTTP, replica, or correctness gate.
- Task 7 fix round 5 RED/GREEN: the real attempt-6 p95 artifact reproduced validator exit 1. A test using the complete actual label shape failed before production change; a second malformed-null-label fixture also failed the strengthened contract before its correction. The minimal validator now passes the expected run to jq, permits only the four identity keys, and validates optional run/scenario values. All 16 real attempt-6 stage RPS/p95/p99/error artifacts passed; wrong run, scenario mismatch, null labels, operation labels, extra subseries, missing workloads, empty values, and error status remain rejected.
- Task 7 fix round 5 verification: fresh Product suite passed 135 tests with 0 failures/errors/skips in 3m06s. Product-only/product-replica/replica-deploy static tests, replica probe, stock-mix runner, Terraform fmt/validate, shell syntax, and diff checks all exited 0.
- Task 7 fix round 5/5 (1 addressed, 0 open locally — accept verified Prometheus workload identity labels without weakening cardinality/correctness gates; commit `39b62dc..dafe414`, pending scoped review).
- Task 7 fix round 5 review: scoped review clean at exact commit `dafe41494600f5bdea01413c95c30b066f8c8901`; real optional run/scenario labels are strictly validated and all existing correctness gates remain intact.
- Task 7 external attempt 7: exact ref `dafe41494600f5bdea01413c95c30b066f8c8901` and immutable linux/arm64 image digest `sha256:d92f3fd6cc3332d1456f9b7a8b484ad09284b7ddbb4ee0f3740c851f598ea454` were used. Matching On-Demand plan/apply created exactly 37 resources and ten instances with null market options. Replication smoke `1787642942-29621`, four healthy Product NLB targets, baseline env (`replica=false`, pools `50/50`, orphan recovery `false`), and seed/source gates (1,000 Product, 9,000 SKU, 9,000 stock rows, sum 900,000, reservations 0) passed.
- Task 7 attempt 7 invalid baseline: A run `20260825T073501Z-product-stock-mix-34476` completed 9,739,954 requests and 9,413,388 iterations, but had 166 overall HTTP failures and 15 stock 5xx out of 653,143 stock writes. Final source was 9,000 stock rows, sum 899,996, min 99, max 100, with `RELEASED=326562` and `RESERVED=4`. Product-c showed primary Hikari `total=50 active=50 idle=0 waiting=103` and a connection timeout. A failed the strict zero-server-error and final-convergence gates; B/C/D were skipped and no performance, lag, outage, SQL-routing, or adoption conclusion is valid.
- Task 7 attempt 7 mandatory cleanup: matching-variable destroy completed with 37 destroyed; Terraform state was empty; matching post-destroy plan was 37 add, 0 change, 0 destroy; and exact instances `i-046fd97678778f252`, `i-04b0bebca64c93469`, `i-00cc04852ad1887a6`, `i-0fc6fb7326b428aa9`, `i-041b5fb2338a4de68`, `i-0c7499524b3839a55`, `i-0e0f1ae8a365494fd`, `i-00e041cfcd90490ec`, `i-0211b1cbf468d045e`, and `i-0b0a3ab89d2a116d4` all reported `terminated`.
- Task 7 terminal Ruling: correction budget 5/5 is exhausted. Attempt 7 is invalid/inconclusive, so the required result report records observations without interpreting performance and makes no adopt/defer recommendation. No additional correction, image publication, AWS apply, or retry is authorized; another investigation or experiment requires new authority and a revised plan.
