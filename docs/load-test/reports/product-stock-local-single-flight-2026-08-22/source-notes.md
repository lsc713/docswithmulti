# Report source notes

- Audience: technical.
- Primary comparison: local + distributed run versus transaction-boundary optimized run.
- Original no-single-flight run is retained as a secondary reference, not a randomized control.
- Sources: four k6 summary JSON files, their Prometheus stage observation directories, and MySQL `performance_schema.events_statements_summary_by_digest`; exact paths are listed in `comparison-data.json`.
- `report-source.sql` is the executable SQLite transformation used by the portable report; `comparison-data.json` retains raw run provenance and the reviewed values used to author it.
- Filters: read-only, uniform 1,000-product population, 10→25→50→75→100 VU, 3 minutes per stage, Product 4대/shared Redis/MySQL 1대.
- Metric definitions: RPS is completed HTTP requests per second; p95/p99 use successful HTTP duration; stage CPU is the arithmetic mean of 15-second samples; stock SELECT uses Performance Schema `COUNT_STAR` for the availability query.
- Chart contracts: (1) prior local single-flight HTTP p95 comparison and (2) transaction-boundary MySQL CPU comparison; each is a grouped `bar` with 10 rows at VU × configuration grain, two categorical roots plus labels, and a full-width HTML report block.
- Validation: 7,379,676 HTTP requests equal iterations; error, server-error, unexpected-client-error, and interrupted-iteration counts are zero; all five stages and required read series are present. Stage windows contain 12–13 Prometheus samples because boundary samples fall into the adjacent 3-minute window.
- Reconciliation: stock cache writes 149,451 versus stock SELECT 150,065 (0.41% gap); detail query digests span 48,134–48,144 and match COMMIT 48,142.
- The report keeps audit tables because exact stage values, CPU, query counts, and cross-resource trade-offs matter more than a smooth trend shape.
- Technical report structure maps directly to summary, findings, definitions, methodology, limitations, next steps, and further questions; no required section was omitted.
