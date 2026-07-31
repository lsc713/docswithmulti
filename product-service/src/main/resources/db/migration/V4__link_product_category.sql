-- product ↔ category(leaf) 연결 — spec §5 (Phase 2 PLINK-02)
-- 세 단계: nullable 추가 → 레거시 행 미분류 대>중>소 backfill(멱등) → NOT NULL+FK+index.
-- Flyway 는 파일을 단일 커넥션에서 실행하므로 @vars 로 id 를 이어붙인다.

-- Step 1 — nullable 컬럼 추가
ALTER TABLE product ADD COLUMN category_id BIGINT NULL;

-- Step 2 — 레거시 행이 있을 때만 미분류 대>중>소 체인 생성 후 orphan 을 leaf 로 연결.
-- CRITICAL: 세 INSERT 모두 EXISTS(product…) 가드를 달아야 빈 DB(테스트/신규)에서 아무것도 발화하지 않는다.
-- root 만 EXISTS 가드를 달면 빈 DB 에서 mid/leaf 가 parent NULL 로 삽입되어
-- uk_category_parent_name(parent_key=0,'미분류') 충돌 → V4 실패 → 전체 부팅 실패.
INSERT INTO category (parent_id, name, level)
SELECT NULL, '미분류', 1 FROM DUAL
WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM category WHERE parent_key = 0 AND name = '미분류');

SET @root = (SELECT id FROM category WHERE parent_key = 0 AND name = '미분류');

INSERT INTO category (parent_id, name, level)
SELECT @root, '미분류', 2 FROM DUAL
WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL)
  AND @root IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE parent_id = @root AND name = '미분류');

SET @mid = (SELECT id FROM category WHERE parent_id = @root AND name = '미분류');

INSERT INTO category (parent_id, name, level)
SELECT @mid, '미분류', 3 FROM DUAL
WHERE EXISTS (SELECT 1 FROM product WHERE category_id IS NULL)
  AND @mid IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM category WHERE parent_id = @mid AND name = '미분류');

SET @leaf = (SELECT id FROM category WHERE parent_id = @mid AND name = '미분류');

UPDATE product SET category_id = @leaf WHERE category_id IS NULL AND @leaf IS NOT NULL;

-- Step 3 — NOT NULL 제약 + FK + 인덱스 (신규/빈 DB 는 orphan 이 없어 자명하게 통과)
ALTER TABLE product
    MODIFY category_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
    ADD KEY idx_product_category (category_id);
