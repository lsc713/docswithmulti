-- =============================================================
-- V1__create_payment_core.sql
-- 결제 핵심 테이블: payment, payment_item, payment_history
-- =============================================================

CREATE TABLE payment
(
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    payment_key  VARCHAR(64)    NOT NULL,
    merchant_id  BIGINT         NOT NULL,
    user_id      BIGINT         NOT NULL,
    pg_type      VARCHAR(20)    NOT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    currency     VARCHAR(3)     NOT NULL DEFAULT 'KRW',
    status       VARCHAR(30)    NOT NULL,
    created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_payment_key (payment_key),
    INDEX idx_payment_merchant_id (merchant_id),
    INDEX idx_payment_user_id (user_id)
);

-- -------------------------------------------------------------

CREATE TABLE payment_item
(
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    payment_id       BIGINT         NOT NULL,
    order_item_id    BIGINT         NOT NULL,
    product_id       BIGINT         NOT NULL,
    product_auto_id  BIGINT         NOT NULL,
    item_name        VARCHAR(100)   NOT NULL,
    item_amount      DECIMAL(19, 2) NOT NULL,
    cancelled_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    status           VARCHAR(30)    NOT NULL,
    created_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_payment_item_payment_id (payment_id),
    INDEX idx_payment_item_order_item_id (order_item_id),
    INDEX idx_payment_item_product_id (product_id)
);

-- -------------------------------------------------------------

CREATE TABLE payment_history
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id   BIGINT      NOT NULL,
    from_status  VARCHAR(30) NOT NULL,
    to_status    VARCHAR(30) NOT NULL,
    cause        VARCHAR(50) NOT NULL,
    caused_by_id BIGINT      NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    INDEX idx_payment_history_payment_id (payment_id)
);