ALTER TABLE payment_item
    ADD COLUMN sku_id   BIGINT NOT NULL DEFAULT 0 AFTER product_auto_id,
    ADD COLUMN quantity INT    NOT NULL DEFAULT 1 AFTER sku_id;
