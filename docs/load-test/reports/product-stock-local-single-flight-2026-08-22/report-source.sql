-- Reproducible report transformation from the reviewed k6, Prometheus, and MySQL values.
CREATE TEMP TABLE aggregate (
  ordering INTEGER,
  configuration TEXT,
  requests INTEGER,
  rps REAL,
  p95_ms REAL,
  p99_ms REAL,
  failure_rate REAL,
  stock_selects INTEGER,
  stock_select_seconds REAL
);

INSERT INTO aggregate VALUES
  (1, 'single-flight 없음', 6009482, 6677.09, 14.02, 23.30, 0, 789155, 174.334),
  (2, '분산 single-flight', 4949311, 5499.12, 22.61, 31.89, 0, 147437, 39.388),
  (3, 'local + 분산 single-flight', 5156131, 5728.92, 17.30, 27.70, 0, 145987, 36.278);

CREATE TEMP TABLE stages (
  vu INTEGER,
  rps_distributed REAL,
  rps_local REAL,
  p95_distributed_ms REAL,
  p95_local_ms REAL,
  p99_distributed_ms REAL,
  p99_local_ms REAL,
  mysql_distributed_pct REAL,
  mysql_local_pct REAL,
  redis_distributed_pct REAL,
  redis_local_pct REAL
);

INSERT INTO stages VALUES
  (10, 1238.45, 1265.17, 21.53, 18.54, 32.06, 30.59, 13.96, 15.81, 14.50, 9.84),
  (25, 2501.68, 2530.02, 15.21, 10.55, 21.92, 18.48, 26.63, 29.53, 22.80, 15.04),
  (50, 5272.93, 5364.85, 16.46, 10.64, 23.15, 13.69, 51.15, 56.11, 31.38, 21.82),
  (75, 8043.57, 8229.11, 16.88, 11.14, 25.00, 14.05, 74.08, 79.76, 32.07, 25.81),
  (100, 8817.71, 9476.14, 19.53, 13.60, 27.50, 21.37, 78.78, 90.73, 24.78, 22.96);

SELECT * FROM aggregate ORDER BY ordering;
SELECT *,
       rps_local / rps_distributed - 1 AS rps_delta,
       p95_local_ms / p95_distributed_ms - 1 AS p95_delta
FROM stages ORDER BY vu;
