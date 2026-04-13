-- =============================================================
-- V2__create_cancel.sql
-- 취소 요청 테이블: cancel_request, cancel_request_item
-- =============================================================

CREATE TABLE cancel_request
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    payment_id            BIGINT         NOT NULL,
    idempotency_key       VARCHAR(64)    NOT NULL,
    cancel_amount         DECIMAL(19, 2) NOT NULL,
    cancel_reason         VARCHAR(255)   NULL,
    canceller_type        VARCHAR(20)    NOT NULL,
    cancelled_by          BIGINT         NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    processing_started_at DATETIME(3)    NULL,
    completed_at          DATETIME(3)    NULL,
    failed_reason         VARCHAR(500)   NULL,
    created_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at            DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_request_idempotency_key (idempotency_key),
    INDEX idx_cancel_request_payment_id (payment_id),
    INDEX idx_cancel_request_status (status),
    INDEX idx_cancel_request_status_created_at (status, created_at)
);

-- -------------------------------------------------------------

CREATE TABLE cancel_request_item
(
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT         NOT NULL,
    payment_item_id   BIGINT         NOT NULL,
    cancel_amount     DECIMAL(19, 2) NOT NULL,
    created_at        DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_cancel_request_item_cancel_request_id (cancel_request_id),
    INDEX idx_cancel_request_item_payment_item_id (payment_item_id)
);