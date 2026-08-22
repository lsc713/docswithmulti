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
  (3, 'local + 분산 single-flight', 5156131, 5728.92, 17.30, 27.70, 0, 145987, 36.278),
  (4, '트랜잭션 경계 최적화', 7379676, 8199.54, 11.25, 15.21, 0, 150065, 27.175);

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

CREATE TEMP TABLE transaction_stages (
  vu INTEGER,
  rps_before REAL,
  rps_after REAL,
  p95_before_ms REAL,
  p95_after_ms REAL,
  mysql_before_pct REAL,
  mysql_after_pct REAL,
  product_after_pct REAL,
  redis_after_pct REAL
);

INSERT INTO transaction_stages VALUES
  (10, 1265.17, 1855.33, 18.54, 17.13, 15.81, 3.20, 18.89, 11.30),
  (25, 2530.02, 4178.89, 10.55, 8.94, 29.53, 3.56, 27.94, 17.30),
  (50, 5364.85, 8545.51, 10.64, 8.46, 56.11, 4.04, 51.50, 26.31),
  (75, 8229.11, 10784.90, 11.14, 9.62, 79.76, 8.34, 67.61, 34.14),
  (100, 9476.14, 13241.43, 13.60, 10.85, 90.73, 13.33, 80.76, 37.26);

CREATE TEMP TABLE transaction_sql (
  statement TEXT,
  before_count INTEGER,
  after_count INTEGER,
  before_seconds REAL,
  after_seconds REAL
);

INSERT INTO transaction_sql VALUES
  ('SET autocommit', 10341871, 96289, 309.716, 3.267),
  ('SET TRANSACTION READ ONLY', 5171083, 48140, 147.663, 2.085),
  ('SET TRANSACTION READ WRITE', 5170889, 48145, 141.661, 1.368),
  ('COMMIT', 5170708, 48142, 127.909, 1.948),
  ('stock availability SELECT', 145987, 150065, 36.278, 27.175);

SELECT * FROM aggregate ORDER BY ordering;
SELECT *,
       rps_local / rps_distributed - 1 AS rps_delta,
       p95_local_ms / p95_distributed_ms - 1 AS p95_delta
FROM stages ORDER BY vu;
SELECT *, rps_after / rps_before - 1 AS rps_delta
FROM transaction_stages ORDER BY vu;
SELECT *, CAST(after_count AS REAL) / before_count - 1 AS count_delta
FROM transaction_sql;
