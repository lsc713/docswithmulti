# Report source notes

- Audience: technical.
- Primary comparison: distributed-only run versus local + distributed run.
- Original no-single-flight run is retained as a secondary reference, not a randomized control.
- Sources: three k6 summary JSON files, their Prometheus stage observation directories, and MySQL `performance_schema.events_statements_summary_by_digest`; exact paths are listed in `comparison-data.json`.
- `report-source.sql` is the executable SQLite transformation used by the portable report; `comparison-data.json` retains raw run provenance and the reviewed values used to author it.
- Filters: read-only, uniform 1,000-product population, 10→25→50→75→100 VU, 3 minutes per stage, Product 4대/shared Redis/MySQL 1대.
- Metric definitions: RPS is completed HTTP requests per second; p95/p99 use successful HTTP duration; stage CPU is the arithmetic mean of 15-second samples; stock SELECT uses Performance Schema `COUNT_STAR` for the availability query.
- Chart contract: compare HTTP p95 at five ordered VU stages; grouped `bar`, 10 rows at VU × configuration grain; two categorical roots plus labels; full-width HTML report block.
- The report keeps audit tables because exact stage values, CPU, query counts, and cross-resource trade-offs matter more than a smooth trend shape.
- Technical report structure maps directly to summary, findings, definitions, methodology, limitations, next steps, and further questions; no required section was omitted.
