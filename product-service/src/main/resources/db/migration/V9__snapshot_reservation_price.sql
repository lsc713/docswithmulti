ALTER TABLE stock_reservation ADD COLUMN unit_price BIGINT NULL AFTER qty;

UPDATE stock_reservation r
JOIN product_sku s ON s.id = r.sku_id
SET r.unit_price = s.price;

ALTER TABLE stock_reservation MODIFY unit_price BIGINT NOT NULL;
