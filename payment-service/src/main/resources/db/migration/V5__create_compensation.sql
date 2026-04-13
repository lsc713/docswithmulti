-- =============================================================
-- V5__create_compensation.sql
-- 보상 트랜잭션 재시도: compensation_retry
-- =============================================================

CREATE TABLE compensation_retry
(
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)    NOT NULL,
    merchant_id       BIGINT         NOT NULL,
    restore_amount    DECIMAL(19, 2) NOT NULL,
    attempt_count     INT            NOT NULL DEFAULT 0,
    next_retry_at     DATETIME(3)    NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    last_error        VARCHAR(500)   NULL,
    created_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_compensation_cancel_request_id (cancel_request_id),
    INDEX idx_compensation_status_next_retry_at (status, next_retry_at)
);