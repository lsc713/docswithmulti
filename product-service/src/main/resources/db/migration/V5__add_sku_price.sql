-- SKU 단위 판매가(KRW 원, 정수). 기존 행은 0 백필 → 시드 재적용 시 실가격.
ALTER TABLE product_sku ADD COLUMN price BIGINT NOT NULL DEFAULT 0;
