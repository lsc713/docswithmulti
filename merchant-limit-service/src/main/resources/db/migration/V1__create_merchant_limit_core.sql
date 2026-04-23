CREATE TABLE merchant
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    merchant_key       VARCHAR(64)  NOT NULL,
    name               VARCHAR(255) NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    cancel_period_days INT          NOT NULL DEFAULT 90,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_key (merchant_key)
);

CREATE TABLE merchant_cancel_limit
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    daily_limit DECIMAL(19,2) NOT NULL,
    updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_cancel_limit_merchant_id (merchant_id)
);

CREATE TABLE merchant_cancel_limit_history
(
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT        NOT NULL,
    old_limit   DECIMAL(19,2) NULL,
    new_limit   DECIMAL(19,2) NOT NULL,
    reason      VARCHAR(500)  NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_limit_history_merchant_id (merchant_id)
);

CREATE TABLE limit_event_outbox
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    merchant_id  BIGINT      NOT NULL,
    payload      JSON        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3) NULL,

    PRIMARY KEY (id),
    INDEX idx_limit_outbox_status (status),
    INDEX idx_limit_outbox_status_created_at (status, created_at)
);
