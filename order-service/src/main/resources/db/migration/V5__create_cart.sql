CREATE TABLE cart_item (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    sku_id         BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL,
    item_name      VARCHAR(255) NOT NULL,
    option_summary VARCHAR(255) NULL,
    unit_price     BIGINT       NOT NULL,
    quantity       INT          NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_user_sku (user_id, sku_id)
);
