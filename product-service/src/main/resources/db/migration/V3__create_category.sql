-- category 택소노미 (대·중·소 3단계 트리, adjacency list) — spec §3
-- 적용 후 불변 — 변경은 새 버전으로만. Phase 1 순수 추가: product.category_id ALTER는 Phase 2(V4).

CREATE TABLE category (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT       NULL,                                -- NULL = 대분류(level 1)
    name       VARCHAR(100) NOT NULL,
    level      TINYINT      NOT NULL,                            -- 1=대 2=중 3=소 (parent로부터 유도)
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 형제 이름 유일을 대분류(parent_id NULL)에도 강제: NULL은 UNIQUE에서 DISTINCT 취급되므로
    -- IFNULL(parent_id,0)로 접어 parent_key=0 그룹에서 루트끼리도 충돌시킨다.
    parent_key BIGINT AS (IFNULL(parent_id, 0)) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_parent_name (parent_key, name),       -- 형제간 이름 유일 (루트 포함)
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
