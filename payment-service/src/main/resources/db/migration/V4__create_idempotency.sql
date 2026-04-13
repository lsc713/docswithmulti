-- =============================================================
-- V4__create_idempotency.sql
-- API 멱등성 보장: idempotency_key
-- =============================================================

CREATE TABLE idempotency_key
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    idem_key        VARCHAR(64)   NOT NULL,
    request_path    VARCHAR(255)  NOT NULL,
    response_status INT           NOT NULL,
    response_body   JSON          NOT NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at      DATETIME(3)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_idem_key (idem_key),
    INDEX idx_idempotency_expires_at (expires_at)
);