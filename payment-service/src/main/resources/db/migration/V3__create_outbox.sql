-- =============================================================
-- V3__create_outbox.sql
-- Kafka 발행 보장: cancel_event_outbox
-- =============================================================

CREATE TABLE cancel_event_outbox
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT       NOT NULL,
    payload           JSON         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at      DATETIME(3)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_cancel_request_id (cancel_request_id),
    INDEX idx_outbox_status (status),
    INDEX idx_outbox_status_created_at (status, created_at)
);