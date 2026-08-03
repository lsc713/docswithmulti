-- 속성/변형 정규화 (spec §3). product 최신 V7 다음 = V8. V1~V7 무변경.
-- 5 테이블 한 번에 생성. product_descriptive_value 는 Phase 2(서술) 용 — 이번 Phase 미사용.

CREATE TABLE attribute (
    id   BIGINT       NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_name (name)                       -- 색상·사이즈·소재… 전역 유일
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE attribute_value (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    attribute_id BIGINT       NOT NULL,
    value        VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attribute_value (attribute_id, value),      -- 색상→화이트 유일
    KEY idx_attribute_value_attr (attribute_id),
    CONSTRAINT fk_attribute_value_attr FOREIGN KEY (attribute_id) REFERENCES attribute (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 상품이 쓰는 속성 + 역할(변형/서술)
CREATE TABLE product_attribute (
    product_id   BIGINT  NOT NULL,
    attribute_id BIGINT  NOT NULL,
    is_variant   BOOLEAN NOT NULL,                            -- true=변형(SKU 정의), false=서술(상품 태그)
    PRIMARY KEY (product_id, attribute_id),
    KEY idx_product_attribute_attr (attribute_id),
    CONSTRAINT fk_product_attribute_product FOREIGN KEY (product_id)   REFERENCES product (id),
    CONSTRAINT fk_product_attribute_attr    FOREIGN KEY (attribute_id) REFERENCES attribute (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 변형: SKU 값 조합 (변형 속성별 1값)
CREATE TABLE sku_attribute_value (
    sku_id             BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    PRIMARY KEY (sku_id, attribute_value_id),
    KEY idx_sku_attr_value_val (attribute_value_id),
    CONSTRAINT fk_sku_attr_value_sku FOREIGN KEY (sku_id)             REFERENCES product_sku (id),
    CONSTRAINT fk_sku_attr_value_val FOREIGN KEY (attribute_value_id) REFERENCES attribute_value (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 서술: 상품 레벨 값 (다속성·다값 허용) — Phase 2 서술 경로용, 이번 Phase 미사용
CREATE TABLE product_descriptive_value (
    product_id         BIGINT NOT NULL,
    attribute_value_id BIGINT NOT NULL,
    PRIMARY KEY (product_id, attribute_value_id),
    KEY idx_product_desc_val_val (attribute_value_id),
    CONSTRAINT fk_product_desc_val_product FOREIGN KEY (product_id)         REFERENCES product (id),
    CONSTRAINT fk_product_desc_val_val     FOREIGN KEY (attribute_value_id) REFERENCES attribute_value (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
