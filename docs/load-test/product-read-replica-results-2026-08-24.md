# Product MySQL read-replica experiment: invalid and inconclusive

Date: 2026-08-25

Planned comparison: A primary-only, B steady replica, C replica lag, D replica outage

Executed comparison: A only; A failed the strict correctness gate

## Technical summary

- **This experiment does not support a read-replica performance or adoption conclusion.** Baseline A returned 15 stock HTTP 5xx responses and ended with four `RESERVED` rows and a stock deficit of four. The run therefore failed the predeclared zero-server-error and final-convergence gates.
- **B, C, and D were intentionally not run.** There is no valid primary-versus-replica comparison, replica-lag result, outage-fallback result, SQL-routing comparison, or adoption/defer recommendation.
- **The observed A latency and throughput values below are diagnostic records only.** They must not be interpreted as a valid baseline or compared with earlier attempts because this run did not preserve correctness.
- **The disposable environment was removed.** Terraform destroyed all 37 resources, state was empty, the matching post-destroy plan proposed only 37 creates, and all ten EC2 instances reached `terminated`.

## A failed correctness before any replica comparison

Run `20260825T073501Z-product-stock-mix-34476` executed the four planned 180-second stages from 07:35:08Z to 07:47:26Z. k6 completed the workload, but its stock-server-error threshold failed.

| Evidence | Observed result | Gate interpretation |
| --- | ---: | --- |
| HTTP requests | 9,739,954 | Diagnostic volume only |
| Completed iterations | 9,413,388 | Diagnostic volume only |
| Overall HTTP failures | 166 / 9,739,954 | Nonzero; run not clean |
| Stock HTTP 5xx | 15 / 653,143 | **Strict zero-error gate failed** |
| Unexpected stock client errors | 0 / 653,143 | Passed, but does not override the 5xx failure |
| Overall HTTP duration p95 | 186.08 ms | Recorded only; do not interpret |
| Overall HTTP duration p99 | 310.70 ms | Recorded only; do not interpret |
| Final stock rows | 9,000 | Expected row count |
| Final stock sum | 899,996 | **Expected 900,000; deficit 4** |
| Final stock range | min 99, max 100 | Four units remained unavailable |
| Reservation states | `RELEASED=326562`, `RESERVED=4` | **Final convergence gate failed** |

The four stranded reservations exactly match the stock deficit of four. Product-c diagnostics also showed a saturated primary Hikari pool at the failure boundary: `total=50`, `active=50`, `idle=0`, and `waiting=103`, followed by a connection-acquisition timeout. This is relevant diagnostic evidence, but the single failed run does not establish a unique causal explanation or justify another configuration change by itself.

No chart is included. A chart of an invalid A-only run would visually invite a performance comparison that the evidence cannot support.

## Scope and provenance were controlled before A

| Control | Evidence |
| --- | --- |
| Exact Git ref | `dafe41494600f5bdea01413c95c30b066f8c8901` |
| Immutable Product image | `camelia9999/cancel-loadtest:product-dafe41494600f5bdea01413c95c30b066f8c8901` |
| Registry digest | `sha256:d92f3fd6cc3332d1456f9b7a8b484ad09284b7ddbb4ee0f3740c851f598ea454` |
| Terraform profile | `load_test_profile=product-replica`, `use_spot=false` |
| Pre-apply plan | 37 add, 0 change, 0 destroy; all ten instance market options null |
| Apply | 37 resources added |
| Replication smoke | Marker `1787642942-29621` passed |
| Product health | Four internal NLB targets healthy |
| Baseline Product environment | replica routing `false`; primary/replica pools `50/50`; orphan recovery `false` |
| Seed/source gate | 1,000 products; 9,000 SKUs; 9,000 stock rows; stock sum 900,000; reservations 0 |

These controls establish what was deployed and seeded. They do not rescue the run after the correctness failure.

## B, C, and D have no result

| Mode | Status | Missing decision evidence |
| --- | --- | --- |
| A — primary-only | Invalid | Failed stock 5xx and final convergence gates |
| B — steady replica | Not run | No read-offload benefit, latency, resource, or SQL-routing comparison |
| C — lag injection | Not run | No 5/30/60-second stale-window or catch-up evidence |
| D — outage | Not run | No fallback count, success rate, latency delta, or recovery evidence |

Because B/C/D do not exist, this report makes no statement about source CPU relief, replica query routing, replication lag tolerance, outage fallback behavior, oversell safety under replica reads, or whether the read-replica design should be adopted or deferred.

## Evidence inventory and reproducibility notes

The following local artifacts are intentionally ignored by Git and were not added to the documentation commit. Their hashes identify the inspected evidence precisely.

| Artifact | SHA-256 |
| --- | --- |
| `k6/results/20260825T073501Z-product-stock-mix-34476.summary.json` | `d0dca2b17d8590e004ab67737729c9c1c20014166a89650276467d47d3102324` |
| `k6/results/20260825T073501Z-product-stock-mix-34476.console.log` | `4dfceacb1fc696d3d25e94b66f24782044231a9574f7ccd35c9925896663043f` |
| `k6/results/20260825T073501Z-product-stock-mix-34476.timing.json` | `fc381f83277ddc3dba050772c9924980b9321da032a23fb291efce1fde4b1a30` |
| `k6/results/20260825T073501Z-product-stock-mix-34476.stage-plan` | `bbae056de710d3d1473b461d46d617511de6fe333d44d6b9ede80618638ae9b3` |
| `k6/results/20260825T073501Z-product-stock-mix-34476.tgz` | `ddd72efb93ee8773c2cf61bde26a9ce23df354b5c3d0385c7b114602cfe447e2` |

Deployment, SSM, database, Product-node diagnostic, Terraform, and termination evidence is recorded in `.superpowers/sdd/2026-08-24-product-mysql-read-replica/task-7-report.md` and the canonical `progress.md` ledger.

## Cleanup proof

Destroy used the same `product-replica` and `use_spot=false` variables as plan/apply and reported 37 resources destroyed. `terraform state list` was empty. A matching-variable post-destroy plan was 37 add, 0 change, 0 destroy.

| Role | Terminated instance ID |
| --- | --- |
| NAT | `i-046fd97678778f252` |
| k6 | `i-04b0bebca64c93469` |
| MySQL source | `i-00cc04852ad1887a6` |
| MySQL replica | `i-0fc6fb7326b428aa9` |
| Observability | `i-041b5fb2338a4de68` |
| Product a | `i-0c7499524b3839a55` |
| Product b | `i-0e0f1ae8a365494fd` |
| Product c | `i-00e041cfcd90490ec` |
| Product d | `i-0211b1cbf468d045e` |
| Redis | `i-0b0a3ab89d2a116d4` |

## Terminal ruling and next step

Task 7 has consumed the approved five local correction rounds. Attempt 7 is invalid and inconclusive, and no further code/configuration correction or paid retry is authorized by the current plan. A new authority and revised plan are required before investigating another correction or creating another AWS topology.

That revised plan would need to define a bounded investigation for the remaining primary-pool saturation/correctness boundary and explicitly reauthorize any implementation change, image publication, AWS billing, or experiment retry. Until then, the read-replica adoption decision remains open.
