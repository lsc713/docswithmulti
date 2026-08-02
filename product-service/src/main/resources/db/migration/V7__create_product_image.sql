CREATE TABLE product_image (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    product_id  BIGINT       NOT NULL,
    s3_key      VARCHAR(512) NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_product_image_product (product_id, sort_order),
    UNIQUE KEY uk_product_image_key (s3_key),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
