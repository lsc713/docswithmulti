-- V10__replace_outbox_with_failed_kafka_event.sql
-- cancel_event_outbox 제거 → failed_kafka_event 추가

DROP TABLE IF EXISTS cancel_event_outbox;

CREATE TABLE failed_kafka_event
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT       NOT NULL,
    topic             VARCHAR(100) NOT NULL,
    payload           JSON         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_failed_kafka_cancel_request_id (cancel_request_id),
    INDEX idx_failed_kafka_status (status),
    INDEX idx_failed_kafka_status_created (status, created_at)
);
