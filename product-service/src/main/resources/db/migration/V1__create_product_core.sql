-- product-service 핵심 스키마 (SKU 재고 수명주기 spec §4)
-- 적용 후 불변 — 변경은 새 버전(V2+)으로만. 후속 phase(payment reserve, 취소 restore)의 계약.

CREATE TABLE product (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE product_sku (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    product_id     BIGINT       NOT NULL,
    sku_code       VARCHAR(64)  NOT NULL,
    option_summary VARCHAR(255) NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_sku_code (sku_code),
    KEY idx_product_sku_product (product_id),
    CONSTRAINT fk_product_sku_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE product_stock (
    sku_id        BIGINT      NOT NULL,
    available_qty INT         NOT NULL, -- 오버셀 방지 원자 조건부 UPDATE 대상
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sku_id),
    CONSTRAINT fk_product_stock_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE stock_reservation (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key VARCHAR(255) NOT NULL,
    sku_id      BIGINT       NOT NULL,
    qty         INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL, -- RESERVED / RELEASED
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_paymentkey_sku (payment_key, sku_id), -- 멱등 (Plan 02에서 활용)
    KEY idx_reservation_status_created (status, created_at),        -- orphan 스캔
    CONSTRAINT fk_stock_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
