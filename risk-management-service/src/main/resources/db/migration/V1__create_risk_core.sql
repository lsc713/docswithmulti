-- 가맹점 일일 소진 내역 (가맹점+날짜당 1행)
CREATE TABLE merchant_cancel_usage (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    kst_date    DATE          NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL,
    used_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_cancel_usage_merchant_id_kst_date (merchant_id, kst_date)
);

-- 차감 이력 (이중 차감 방어 — cancelRequestId UK)
CREATE TABLE cancel_usage_history (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)   NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    kst_date          DATE          NOT NULL,
    cancel_amount     DECIMAL(19,2) NOT NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_usage_history_cancel_request_id (cancel_request_id)
);

-- 보상 멱등성 (cancelRequestId UK)
CREATE TABLE cancel_usage_compensation (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    cancel_request_id VARCHAR(64)   NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    restore_amount    DECIMAL(19,2) NOT NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_usage_compensation_cancel_request_id (cancel_request_id)
);
