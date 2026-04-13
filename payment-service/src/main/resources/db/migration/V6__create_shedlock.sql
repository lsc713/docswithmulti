-- =============================================================
-- V6__create_shedlock.sql
-- 분산 스케줄러 중복 실행 방어: shedlock
-- =============================================================

CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until DATETIME(3)  NOT NULL,
    locked_at  DATETIME(3)  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,

    PRIMARY KEY (name)
);