CREATE TABLE category (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    parent_id  BIGINT       NULL,
    depth      INT          NOT NULL DEFAULT 0,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_category_parent_id (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id)
);

CREATE TABLE product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_product_merchant_id (merchant_id),
    INDEX idx_product_category_id (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id)
);

CREATE TABLE product_version (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id     BIGINT         NOT NULL,
    name           VARCHAR(100)   NOT NULL,
    price          DECIMAL(19,2)  NOT NULL,
    discount_price DECIMAL(19,2)  NULL,
    attributes     JSON           NULL,
    version        INT            NOT NULL DEFAULT 1,
    is_current     BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_product_version_product_id (product_id),
    CONSTRAINT fk_product_version_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE TABLE product_sku (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_version_id BIGINT       NOT NULL,
    color              VARCHAR(30)  NOT NULL,
    size               VARCHAR(10)  NOT NULL,
    sku_code           VARCHAR(50)  NOT NULL UNIQUE,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_sku_product_version_id (product_version_id),
    CONSTRAINT fk_sku_product_version FOREIGN KEY (product_version_id) REFERENCES product_version(id)
);

CREATE TABLE product_stock (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id     BIGINT  NOT NULL UNIQUE,
    quantity   INT     NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_stock_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id)
);

CREATE TABLE processed_stock_event (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    cancel_request_id BIGINT NOT NULL UNIQUE,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
